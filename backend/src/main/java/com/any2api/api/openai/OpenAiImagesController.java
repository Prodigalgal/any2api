package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyFeature;
import com.any2api.auth.ApiKeyProtocol;
import com.any2api.media.MediaAssetService;
import com.any2api.media.MediaCoordinator;
import com.any2api.media.MediaOperation;
import com.any2api.media.MediaInput;
import com.any2api.media.MediaInputValidation;
import com.any2api.media.MediaRequest;
import com.any2api.routing.ProviderRouteResolver;
import com.any2api.config.Any2ApiProperties;
import com.any2api.observability.RequestIdWebFilter;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public final class OpenAiImagesController {
    private static final int MAX_IMAGES = 10;
    private final ProviderRouteResolver routes;
    private final MediaCoordinator coordinator;
    private final MediaAssetService assets;
    private final ObjectMapper mapper;
    private final Any2ApiProperties properties;
    private final ApiKeyAuthorization authorization;

    public OpenAiImagesController(
        ProviderRouteResolver routes,
        MediaCoordinator coordinator,
        MediaAssetService assets,
        ObjectMapper mapper,
        Any2ApiProperties properties,
        ApiKeyAuthorization authorization
    ) {
        this.routes = routes;
        this.coordinator = coordinator;
        this.assets = assets;
        this.mapper = mapper;
        this.properties = properties;
        this.authorization = authorization;
    }

    @PostMapping(
        path = {
            "/v1/images/edits",
            "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/images/edits"
        },
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<Map<String, Object>> edit(ServerWebExchange exchange) {
        var grant = authorization.grant(exchange);
        authorization.requireFeatures(grant, java.util.Set.of(ApiKeyFeature.FILE_UPLOADS));
        return exchange.getMultipartData().flatMap(parts -> {
            var model = form(parts.getFirst("model"), "");
            var route = routes.resolve(exchange.getRequest().getPath().value(), model);
            routeAttributes(exchange, route.providerId(), route.upstreamModel());
            authorization.require(
                grant, ApiKeyProtocol.IMAGES, route.providerId(), route.upstreamModel());
            return readInputs(parts.get("image")).flatMap(inputs -> {
                var request = parseEdit(
                    RequestIdWebFilter.get(exchange), route.providerId(),
                    route.upstreamModel(), parts, inputs);
                return coordinator.execute(request, grant.keyId())
                    .flatMap(result -> encode(request, result, exchange));
            });
        });
    }

    @PostMapping({
        "/v1/images/generations",
        "/{provider:[a-z][a-z0-9_-]{1,31}}/v1/images/generations"
    })
    public Mono<Map<String, Object>> generate(
        @RequestBody ObjectNode body,
        ServerWebExchange exchange
    ) {
        var route = routes.resolve(
            exchange.getRequest().getPath().value(), body.path("model").asText(""));
        routeAttributes(exchange, route.providerId(), route.upstreamModel());
        var grant = authorization.grant(exchange);
        authorization.require(
            grant, ApiKeyProtocol.IMAGES, route.providerId(), route.upstreamModel());
        var request = parse(
            RequestIdWebFilter.get(exchange), route.providerId(), route.upstreamModel(), body);
        return coordinator.execute(request, grant.keyId())
            .flatMap(result -> encode(request, result, exchange));
    }

    @GetMapping("/v1/media/images/{id:[0-9a-fA-F-]{36}}")
    public Mono<ResponseEntity<byte[]>> image(@PathVariable UUID id) {
        return assets.find(id).map(result -> result
            .map(asset -> ResponseEntity.ok()
                .contentType(safeMediaType(asset.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(asset.content()))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    private MediaRequest parse(
        String requestId,
        String providerId,
        String model,
        ObjectNode body
    ) {
        var prompt = body.path("prompt").asText("").trim();
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        if (prompt.length() > 32000) {
            throw new IllegalArgumentException("prompt must not exceed 32000 characters");
        }
        var count = body.path("n").isMissingNode() ? 1 : body.path("n").asInt(-1);
        if (count < 1 || count > MAX_IMAGES) {
            throw new IllegalArgumentException("n must be between 1 and 10");
        }
        var format = switch (body.path("response_format").asText("url")) {
            case "url" -> MediaRequest.ResponseFormat.URL;
            case "b64_json" -> MediaRequest.ResponseFormat.B64_JSON;
            default -> throw new IllegalArgumentException(
                "response_format must be url or b64_json");
        };
        var options = new LinkedHashMap<String, Object>();
        body.properties().forEach(entry -> {
            if (!java.util.Set.of("model", "prompt", "n", "response_format")
                .contains(entry.getKey())) {
                options.put(entry.getKey(), entry.getValue());
            }
        });
        return new MediaRequest(
            requestId, providerId, model,
            MediaOperation.IMAGE_GENERATION, prompt, count, format, options, body);
    }

    private MediaRequest parseEdit(
        String requestId,
        String providerId,
        String model,
        org.springframework.util.MultiValueMap<String,
            org.springframework.http.codec.multipart.Part> parts,
        java.util.List<MediaInput> inputs
    ) {
        var prompt = form(parts.getFirst("prompt"), "").trim();
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        var count = integer(parts.getFirst("n"), 1, "n");
        if (count < 1 || count > MAX_IMAGES) {
            throw new IllegalArgumentException("n must be between 1 and 10");
        }
        var format = switch (form(parts.getFirst("response_format"), "url")) {
            case "url" -> MediaRequest.ResponseFormat.URL;
            case "b64_json" -> MediaRequest.ResponseFormat.B64_JSON;
            default -> throw new IllegalArgumentException(
                "response_format must be url or b64_json");
        };
        var options = new LinkedHashMap<String, Object>();
        for (var entry : parts.entrySet()) {
            if (java.util.Set.of("model", "prompt", "n", "response_format", "image")
                .contains(entry.getKey())) continue;
            var value = entry.getValue().isEmpty() ? null : entry.getValue().getFirst();
            if (value instanceof FormFieldPart field) options.put(entry.getKey(), field.value());
            else throw new IllegalArgumentException(
                "unsupported multipart field: " + entry.getKey());
        }
        var raw = mapper.createObjectNode()
            .put("model", model).put("prompt", prompt).put("n", count)
            .put("response_format", format == MediaRequest.ResponseFormat.URL
                ? "url" : "b64_json");
        var rawInputs = raw.putArray("images");
        inputs.forEach(input -> rawInputs.addObject()
            .put("filename", input.filename())
            .put("content_type", input.contentType())
            .put("base64", Base64.getEncoder().encodeToString(input.content())));
        return new MediaRequest(
            requestId, providerId, model,
            MediaOperation.IMAGE_EDITING, prompt, count, format,
            inputs, options, raw);
    }

    private Mono<java.util.List<MediaInput>> readInputs(
        java.util.List<org.springframework.http.codec.multipart.Part> parts
    ) {
        if (parts == null || parts.isEmpty() || parts.size() > 8) {
            return Mono.error(new IllegalArgumentException(
                "image must contain between 1 and 8 files"));
        }
        return Flux.fromIterable(parts).concatMap(part -> {
            if (!(part instanceof FilePart file)) {
                return Mono.error(new IllegalArgumentException("image must be a file"));
            }
            var contentType = file.headers().getContentType();
            var normalized = contentType == null ? "application/octet-stream"
                : contentType.toString().split(";", 2)[0].toLowerCase();
            if (!normalized.startsWith("image/")
                && !"application/octet-stream".equals(normalized)) {
                return Mono.error(new IllegalArgumentException(
                    "image file content type must be image/*"));
            }
            return DataBufferUtils.join(file.content(), 12 << 20).map(buffer -> {
                var bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                if (bytes.length == 0) {
                    throw new IllegalArgumentException("image file must not be empty");
                }
                var detected = MediaInputValidation.requireRasterImage(normalized, bytes);
                return new MediaInput(file.filename(), detected, bytes);
            }).onErrorMap(DataBufferLimitException.class, error ->
                new IllegalArgumentException("image file must not exceed 12 MiB", error));
        }).collectList();
    }

    private String form(org.springframework.http.codec.multipart.Part part, String fallback) {
        return part instanceof FormFieldPart field ? field.value().trim() : fallback;
    }

    private int integer(
        org.springframework.http.codec.multipart.Part part,
        int fallback,
        String name
    ) {
        var value = form(part, "");
        if (value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private Mono<Map<String, Object>> encode(
        MediaRequest request,
        MediaCoordinator.ExecutionResult execution,
        ServerWebExchange exchange
    ) {
        if (request.responseFormat() == MediaRequest.ResponseFormat.B64_JSON) {
            var data = execution.result().items().stream()
                .map(item -> item("b64_json",
                    Base64.getEncoder().encodeToString(item.content()), item.revisedPrompt()))
                .toList();
            return Mono.just(response(data));
        }
        return Flux.fromIterable(execution.result().items())
            .concatMap(item -> assets.save(
                    request.providerId(), execution.accountId(), item)
                .map(id -> item("url", assetUrl(exchange, id), item.revisedPrompt())))
            .collectList()
            .map(this::response);
    }

    private Map<String, Object> response(java.util.List<Map<String, Object>> data) {
        return Map.of("created", Instant.now().getEpochSecond(), "data", data);
    }

    private Map<String, Object> item(String key, String value, String revisedPrompt) {
        var item = new LinkedHashMap<String, Object>();
        item.put(key, value);
        if (revisedPrompt != null && !revisedPrompt.isBlank()) {
            item.put("revised_prompt", revisedPrompt);
        }
        return Map.copyOf(item);
    }

    private String assetUrl(ServerWebExchange exchange, UUID id) {
        var configured = properties.getMedia().getPublicBaseUrl().trim();
        if (!configured.isBlank()) {
            URI base;
            try {
                base = URI.create(configured);
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("media public base URL is invalid", error);
            }
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null) {
                throw new IllegalStateException(
                    "media public base URL must be an HTTPS origin or base path");
            }
            return configured.replaceAll("/+$", "") + "/v1/media/images/" + id;
        }
        URI request = exchange.getRequest().getURI();
        return request.getScheme() + "://" + request.getRawAuthority()
            + "/v1/media/images/" + id;
    }

    private MediaType safeMediaType(String value) {
        try {
            var mediaType = MediaType.parseMediaType(value);
            return "image".equals(mediaType.getType())
                ? mediaType : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private void routeAttributes(
        ServerWebExchange exchange,
        String providerId,
        String model
    ) {
        exchange.getAttributes().put(RequestIdWebFilter.PROVIDER_ATTRIBUTE, providerId);
        exchange.getAttributes().put(RequestIdWebFilter.MODEL_ATTRIBUTE, model);
    }
}
