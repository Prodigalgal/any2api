package com.any2api.provider.minmax;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.any2api.media.MediaInputValidation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class MinmaxMediaUploader {
    private final MinmaxTransportClient transport;
    private final MinmaxProperties properties;
    private final ObjectMapper mapper;

    MinmaxMediaUploader(
        MinmaxTransportClient transport,
        MinmaxProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.properties = properties;
        this.mapper = mapper;
    }

    Mono<List<ObjectNode>> upload(
        JsonNode credential,
        List<MinmaxMediaSource> sources,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        if (sources.isEmpty()) return Mono.just(List.of());
        return policy(credential, proxyPool, affinityKey)
            .flatMapMany(policy -> Flux.fromIterable(sources)
                .concatMap(source -> uploadOne(
                    credential, source, policy, proxyPool, affinityKey)))
            .collectList();
    }

    private Mono<UploadPolicy> policy(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return transport.request("GET", "/v1/api/files/request_policy", "", credential,
                proxyPool, affinityKey)
            .map(this::responseJson)
            .map(root -> UploadPolicy.from(root.path("data")));
    }

    private Mono<ObjectNode> uploadOne(
        JsonNode credential,
        MinmaxMediaSource source,
        UploadPolicy policy,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var loaded = decode(source);
        var generatedName = UUID.randomUUID().toString().replace("-", "")
            + extension(loaded.contentType());
        var objectName = policy.dir().replaceAll("/+$", "") + "/" + generatedName;
        return Mono.fromCallable(() -> putObject(policy, objectName, loaded))
            .subscribeOn(Schedulers.boundedElastic())
            .then(callback(credential, policy, generatedName, loaded, proxyPool, affinityKey))
            .map(path -> attachment(loaded, path));
    }

    private Void putObject(UploadPolicy policy, String objectName, LoadedImage loaded) {
        var credentials = new DefaultCredentialProvider(
            policy.accessKeyId(), policy.accessKeySecret(), policy.securityToken());
        var client = OSSClientBuilder.create()
            .endpoint(policy.endpoint())
            .credentialsProvider(credentials)
            .clientConfiguration(new ClientBuilderConfiguration())
            .build();
        var metadata = new ObjectMetadata();
        metadata.setContentType(loaded.contentType());
        metadata.setContentDisposition("attachment;filename=" + encode(loaded.filename()) + ";");
        try (var input = new ByteArrayInputStream(loaded.content())) {
            client.putObject(new PutObjectRequest(
                policy.bucketName(), objectName, input, metadata));
            return null;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("MinMax OSS upload failed", error);
        } catch (RuntimeException error) {
            throw new MinmaxUpstreamException(502, "MinMax media upload failed");
        } finally {
            client.shutdown();
        }
    }

    private Mono<String> callback(
        JsonNode credential,
        UploadPolicy policy,
        String generatedName,
        LoadedImage loaded,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var body = mapper.createObjectNode()
            .put("fileName", generatedName)
            .put("originFileName", loaded.filename())
            .put("dir", policy.dir())
            .put("endpoint", policy.endpoint())
            .put("bucketName", policy.bucketName())
            .put("size", Integer.toString(loaded.content().length))
            .put("mimeType", loaded.contentType())
            .put("fileMd5", md5(loaded.content()));
        return transport.request("POST", "/v1/api/files/policy_callback",
                mapper.writeValueAsString(body), credential, proxyPool, affinityKey)
            .map(this::responseJson)
            .map(root -> {
                var path = root.path("data").path("ossPath").asText("").trim();
                if (path.isBlank()) {
                    throw new MinmaxUpstreamException(502,
                        "MinMax media callback returned no ossPath");
                }
                return path;
            });
    }

    private JsonNode responseJson(MinmaxTransportClient.TransportResponse response) {
        if (response.status() < 200 || response.status() >= 300) {
            throw new MinmaxUpstreamException(response.status(),
                "MinMax media service returned HTTP " + response.status());
        }
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException error) {
            throw new MinmaxUpstreamException(502,
                "MinMax media service returned invalid JSON");
        }
    }

    private LoadedImage decode(MinmaxMediaSource source) {
        var value = source.dataUrl();
        if (value == null || !value.startsWith("data:image/") || !value.contains(";base64,")) {
            throw new IllegalArgumentException(
                "MinMax image input currently requires an inline base64 data URL");
        }
        var comma = value.indexOf(',');
        var declared = value.substring(5, value.indexOf(';', 5)).toLowerCase();
        byte[] content;
        try {
            content = Base64.getDecoder().decode(value.substring(comma + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("MinMax image data URL contains invalid base64", error);
        }
        if (content.length == 0 || content.length > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("MinMax image exceeds the configured upload limit");
        }
        var type = MediaInputValidation.requireRasterImage(declared, content);
        var filename = source.filename();
        if (filename == null || filename.isBlank()) {
            filename = "upload-" + UUID.randomUUID().toString().replace("-", "")
                + extension(type);
        }
        return new LoadedImage(filename, type, content);
    }

    private ObjectNode attachment(LoadedImage loaded, String path) {
        return mapper.createObjectNode()
            .put("type", "image")
            .put("file_path", loaded.filename())
            .put("file_name", loaded.filename())
            .put("mime_type", loaded.contentType())
            .put("data_url", path);
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

    private String md5(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("MD5 is unavailable", error);
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record LoadedImage(String filename, String contentType, byte[] content) {
        LoadedImage { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    private record UploadPolicy(
        String endpoint,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String bucketName,
        String dir
    ) {
        static UploadPolicy from(JsonNode value) {
            return new UploadPolicy(
                required(value, "endpoint"), required(value, "accessKeyId"),
                required(value, "accessKeySecret"), required(value, "securityToken"),
                required(value, "bucketName"), required(value, "dir"));
        }

        private static String required(JsonNode value, String field) {
            var result = value.path(field).asText("").trim();
            if (result.isBlank()) {
                throw new MinmaxUpstreamException(502,
                    "MinMax upload policy is missing " + field);
            }
            return result;
        }
    }
}
