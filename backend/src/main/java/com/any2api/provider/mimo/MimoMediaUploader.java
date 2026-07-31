package com.any2api.provider.mimo;

import com.any2api.media.MediaInputValidation;
import com.any2api.transport.BrowserTransportClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class MimoMediaUploader {
    private final WebClient client;
    private final BrowserTransportClient transport;
    private final MimoProperties properties;
    private final ObjectMapper mapper;

    MimoMediaUploader(
        WebClient.Builder builder,
        BrowserTransportClient transport,
        MimoProperties properties,
        ObjectMapper mapper
    ) {
        this.client = builder.baseUrl(properties.getBaseUrl()).build();
        this.transport = transport;
        this.properties = properties;
        this.mapper = mapper;
    }

    Mono<List<ObjectNode>> upload(
        BrowserTransportClient.Session session,
        MimoCredential credential,
        List<MimoMediaSource> sources,
        String model
    ) {
        return Flux.fromIterable(sources)
            .concatMap(source -> uploadOne(session, credential, source, model))
            .collectList();
    }

    private Mono<ObjectNode> uploadOne(
        BrowserTransportClient.Session session,
        MimoCredential credential,
        MimoMediaSource source,
        String model
    ) {
        var loaded = decode(source);
        var path = org.springframework.web.util.UriComponentsBuilder
            .fromPath("/open-apis/resource/genUploadInfo")
            .queryParam("xiaomichatbot_ph", credential.phase())
            .build().encode().toUriString();
        return transport.request(session.id(), request(
                "POST", path, mapper.createObjectNode().put("fileName", loaded.filename()), 120))
            .map(this::json)
            .flatMap(info -> {
                var data = info.path("data");
                var uploadUrl = data.path("uploadUrl").asText("").trim();
                var resourceUrl = data.path("resourceUrl").asText("").trim();
                var objectName = data.path("objectName").asText("").trim();
                if (info.path("code").asInt(-1) != 0 || uploadUrl.isBlank()
                    || resourceUrl.isBlank() || objectName.isBlank()) {
                    return Mono.error(new MimoUpstreamException(
                        502, "MiMo did not return complete media upload information"));
                }
                return client.put().uri(uploadUrl)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(loaded.content())
                    .retrieve().toBodilessEntity()
                    .then(parse(session, credential, resourceUrl, objectName, model, 0))
                    .map(parsed -> media(source.kind(), loaded, resourceUrl,
                        objectName, parsed));
            });
    }

    private Mono<JsonNode> parse(
        BrowserTransportClient.Session session,
        MimoCredential credential,
        String resourceUrl,
        String objectName,
        String model,
        int attempt
    ) {
        var path = org.springframework.web.util.UriComponentsBuilder
            .fromPath("/open-apis/resource/parse")
            .queryParam("fileUrl", resourceUrl)
            .queryParam("objectName", objectName)
            .queryParam("model", model)
            .queryParam("xiaomichatbot_ph", credential.phase())
            .build().encode().toUriString();
        return transport.request(session.id(), request(
                "POST", path, mapper.createObjectNode(), 120))
            .map(this::json)
            .flatMap(result -> {
                var data = result.path("data");
                if (result.path("code").asInt(-1) == 0
                    && !data.path("id").asText("").isBlank()) {
                    return Mono.just(data);
                }
                if (attempt >= 4) {
                    return Mono.error(new MimoUpstreamException(
                        502, "MiMo could not parse uploaded media"));
                }
                return Mono.delay(Duration.ofSeconds(2))
                    .then(parse(session, credential, resourceUrl, objectName,
                        model, attempt + 1));
            });
    }

    private BrowserTransportClient.Request request(
        String method,
        String path,
        JsonNode body,
        int timeout
    ) {
        return new BrowserTransportClient.Request(
            method, path, Map.of("x-timezone", properties.getTimezone()),
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body, timeout);
    }

    private JsonNode json(BrowserTransportClient.BufferedResponse response) {
        if (!response.successful()) {
            throw new MimoUpstreamException(response.status(),
                "MiMo media service returned HTTP " + response.status());
        }
        try {
            return mapper.readTree(response.text());
        } catch (RuntimeException error) {
            throw new MimoUpstreamException(502,
                "MiMo media service returned invalid JSON");
        }
    }

    private LoadedMedia decode(MimoMediaSource source) {
        var comma = source.dataUrl().indexOf(',');
        if (comma <= 5) throw new IllegalArgumentException("invalid media data URL");
        var metadata = source.dataUrl().substring(5, comma);
        if (!metadata.toLowerCase().contains(";base64")) {
            throw new IllegalArgumentException("media data URL must use base64 encoding");
        }
        var declaredType = metadata.split(";", 2)[0].trim().toLowerCase();
        byte[] content;
        try {
            content = Base64.getDecoder().decode(source.dataUrl().substring(comma + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("media data URL contains invalid base64", error);
        }
        if (content.length == 0 || content.length > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("MiMo media must be between 1 and "
                + properties.getMaxUploadBytes() + " bytes");
        }
        var contentType = "image".equals(source.kind())
            ? MediaInputValidation.requireRasterImage(declaredType, content)
            : declaredType;
        var filename = source.filename();
        if (filename == null || filename.isBlank()) {
            filename = UUID.randomUUID().toString().replace("-", "")
                + extension(contentType);
        }
        return new LoadedMedia(filename, contentType, content);
    }

    private ObjectNode media(
        String kind,
        LoadedMedia loaded,
        String resourceUrl,
        String objectName,
        JsonNode parsed
    ) {
        return mapper.createObjectNode()
            .put("mediaType", kind)
            .put("fileUrl", resourceUrl)
            .put("compressedVideoUrl", "")
            .put("audioTrackUrl", "")
            .put("name", loaded.filename())
            .put("size", loaded.content().length)
            .put("status", "completed")
            .put("objectName", objectName)
            .put("tokenUsage", parsed.path("tokenUsage").asLong(0))
            .put("url", parsed.path("id").asText());
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/avif" -> ".avif";
            default -> ".bin";
        };
    }

    private record LoadedMedia(String filename, String contentType, byte[] content) {
        LoadedMedia { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}
