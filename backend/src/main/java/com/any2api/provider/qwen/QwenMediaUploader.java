package com.any2api.provider.qwen;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.any2api.media.MediaInputValidation;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.transport.BrowserTransportClient;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class QwenMediaUploader {
    private final BrowserTransportClient transport;
    private final QwenProperties properties;
    private final QwenTransportRequests requests;
    private final ObjectMapper mapper;

    QwenMediaUploader(
        BrowserTransportClient transport,
        QwenProperties properties,
        QwenTransportRequests requests,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.properties = properties;
        this.requests = requests;
        this.mapper = mapper;
    }

    Mono<List<QwenPreparedMessage>> prepare(
        BrowserTransportClient.Session session,
        List<JsonNode> messages,
        QwenCredential credential,
        ProviderExecutionContext context
    ) {
        return Flux.fromIterable(messages)
            .concatMap(message -> prepareMessage(session, message, credential, context))
            .collectList();
    }

    private Mono<QwenPreparedMessage> prepareMessage(
        BrowserTransportClient.Session session,
        JsonNode message,
        QwenCredential credential,
        ProviderExecutionContext context
    ) {
        var parsed = parseContent(message.path("content"));
        var text = parsed.text();
        if ("assistant".equalsIgnoreCase(message.path("role").asText(""))
            && message.path("tool_calls").isArray()) {
            text += "\n" + message.path("tool_calls");
        }
        var resolvedText = text;
        return Flux.fromIterable(parsed.images())
            .concatMap(image -> upload(session, image, credential, context))
            .collectList()
            .map(files -> new QwenPreparedMessage(
                message.path("role").asText("user"), resolvedText, files));
    }

    private Mono<ObjectNode> upload(
        BrowserTransportClient.Session session,
        InlineImage image,
        QwenCredential credential,
        ProviderExecutionContext context
    ) {
        var body = mapper.createObjectNode()
            .put("filename", image.filename())
            .put("filesize", Integer.toString(image.content().length))
            .put("filetype", "image");
        return requestJson(
                session, "/api/v2/files/getstsToken", body, credential, context)
            .flatMap(response -> {
                var sts = response.path("data");
                var values = StsUpload.from(sts);
                return Mono.fromCallable(() -> putObject(values, image.content()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .thenReturn(fileObject(values, image, credential.userId()));
            });
    }

    private Mono<JsonNode> requestJson(
        BrowserTransportClient.Session session,
        String path,
        ObjectNode body,
        QwenCredential credential,
        ProviderExecutionContext context
    ) {
        var bodyText = mapper.writeValueAsString(body);
        return requests.browserFetch(
                "POST", path, bodyText, credential, context, session.id(), "/", 120)
            .flatMap(response -> {
                    JsonNode value;
                    try {
                        value = mapper.readTree(response.text());
                    } catch (RuntimeException error) {
                        return Mono.error(new QwenUpstreamException(502,
                            "Qwen media upload token returned invalid JSON"));
                    }
                    if (!response.successful() || value.path("success").isBoolean()
                        && !value.path("success").asBoolean()) {
                        return Mono.error(new QwenUpstreamException(response.status(),
                            "Qwen media upload token request was rejected"));
                    }
                    return Mono.just(value);
                });
    }

    private Void putObject(StsUpload sts, byte[] content) {
        var configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        var credentials = new DefaultCredentialProvider(
            sts.accessKeyId(), sts.accessKeySecret(), sts.securityToken());
        var client = OSSClientBuilder.create()
            .endpoint(sts.endpoint())
            .credentialsProvider(credentials)
            .clientConfiguration(configuration)
            .region(sts.region())
            .build();
        try (var input = new ByteArrayInputStream(content)) {
            client.putObject(sts.bucket(), sts.objectName(), input);
            return null;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Qwen OSS upload failed", error);
        } catch (RuntimeException error) {
            // OSS service errors can contain the temporary access key and signature.
            throw new QwenUpstreamException(502, "Qwen media upload failed");
        } finally {
            client.shutdown();
        }
    }

    static String normalizeSigningRegion(String region) {
        var normalized = region == null ? "" : region.trim();
        return normalized.startsWith("oss-") ? normalized.substring(4) : normalized;
    }

    private ObjectNode fileObject(StsUpload sts, InlineImage image, String userId) {
        var now = Instant.now().toEpochMilli();
        var inner = mapper.createObjectNode();
        inner.put("created_at", now);
        inner.set("data", mapper.createObjectNode());
        inner.put("filename", image.filename());
        inner.putNull("hash");
        inner.put("id", sts.fileId());
        inner.put("user_id", userId);
        inner.set("meta", mapper.createObjectNode()
            .put("name", image.filename())
            .put("size", image.content().length)
            .put("content_type", image.contentType()));
        inner.put("update_at", now);
        inner.put("lastModified", now);
        inner.put("name", image.filename());
        inner.put("webkitRelativePath", "");
        inner.put("size", image.content().length);
        inner.put("type", image.contentType());
        var output = mapper.createObjectNode();
        output.put("type", "image");
        output.set("file", inner);
        output.put("id", sts.fileId());
        output.put("url", sts.fileUrl());
        output.put("name", image.filename());
        output.put("collection_name", "");
        output.put("progress", 100);
        output.put("status", "uploaded");
        output.put("greenNet", "success");
        output.put("size", image.content().length);
        output.put("error", "");
        output.put("itemId", UUID.randomUUID().toString());
        output.put("file_type", image.contentType());
        output.put("showType", "image");
        output.put("file_class", "vision");
        return output;
    }

    private ParsedContent parseContent(JsonNode content) {
        if (content.isTextual()) return new ParsedContent(content.asText(), List.of());
        if (!content.isArray()) return new ParsedContent("", List.of());
        var text = new ArrayList<String>();
        var images = new ArrayList<InlineImage>();
        for (var part : content) {
            if (part.isTextual()) {
                text.add(part.asText());
                continue;
            }
            var type = part.path("type").asText("");
            if (List.of("text", "input_text", "output_text").contains(type)) {
                text.add(part.path("text").asText(""));
                continue;
            }
            if (List.of("image_url", "input_image").contains(type)) {
                var image = part.path("image_url");
                var source = image.isTextual() ? image.asText() : image.path("url").asText("");
                images.add(decode(source));
                continue;
            }
            throw new IllegalArgumentException("unsupported Qwen content block type: " + type);
        }
        return new ParsedContent(String.join("\n", text), images);
    }

    private InlineImage decode(String source) {
        if (source == null || !source.startsWith("data:image/") || !source.contains(";base64,")) {
            throw new IllegalArgumentException(
                "Qwen image input currently requires an inline base64 data URL");
        }
        var comma = source.indexOf(',');
        var declared = source.substring(5, source.indexOf(';', 5)).toLowerCase();
        byte[] content;
        try {
            content = Base64.getDecoder().decode(source.substring(comma + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Qwen image data URL contains invalid base64", error);
        }
        if (content.length == 0 || content.length > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("Qwen image exceeds the configured upload limit");
        }
        var type = MediaInputValidation.requireRasterImage(declared, content);
        return new InlineImage(content, type,
            "upload-" + UUID.randomUUID().toString().replace("-", "") + extension(type));
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

    private record ParsedContent(String text, List<InlineImage> images) {}
    private record InlineImage(byte[] content, String contentType, String filename) {
        InlineImage { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    private record StsUpload(
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String bucket,
        String region,
        String endpoint,
        String fileId,
        String objectName,
        String fileUrl
    ) {
        static StsUpload from(JsonNode value) {
            return new StsUpload(
                required(value, "access_key_id"), required(value, "access_key_secret"),
                required(value, "security_token"), required(value, "bucketname"),
                normalizeSigningRegion(required(value, "region")), required(value, "endpoint"),
                required(value, "file_id"), required(value, "file_path"),
                required(value, "file_url"));
        }
        private static String required(JsonNode value, String field) {
            var result = value.path(field).asText("").trim();
            if (result.isBlank()) throw new IllegalStateException("Qwen STS response is missing " + field);
            return result;
        }
    }
}
