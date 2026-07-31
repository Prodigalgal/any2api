package com.any2api.provider.grok_web;

import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.BrowserClearanceCoordinator;
import com.any2api.media.MediaInput;
import com.any2api.media.MediaInputValidation;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class GrokWebProtocolClient {
    private static final byte[] ACCEPT_TERMS_BODY = {
        0, 0, 0, 0, 2, 0x10, 1
    };
    private static final byte[] ENABLE_NSFW_BODY = {
        0, 0, 0, 0, 0x20,
        0x0a, 0x02, 0x10, 0x01,
        0x12, 0x1a, 0x0a, 0x18,
        'a', 'l', 'w', 'a', 'y', 's', '_', 's', 'h', 'o', 'w', '_',
        'n', 's', 'f', 'w', '_', 'c', 'o', 'n', 't', 'e', 'n', 't'
    };
    private static final String BIRTH_DATE_LOCKED =
        "[WKE=account:birth-date-change-limit-reached]";
    private static final Pattern COOKIE_NAME = Pattern.compile(
        "[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,128}");
    private static final Pattern CODE_SEVEN = Pattern.compile(
        "(?s).*\\\"code\\\"\\s*:\\s*7.*");
    private static final Pattern MODE_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private final BrowserTransportClient transport;
    private final BrowserClearanceCoordinator clearance;
    private final GrokWebStatsigSigner signer;
    private final GrokWebProperties properties;
    private final ObjectMapper mapper;
    private final GrokWebGatewayChat gatewayChat;

    GrokWebProtocolClient(
        BrowserTransportClient transport,
        BrowserClearanceCoordinator clearance,
        GrokWebStatsigSigner signer,
        GrokWebProperties properties,
        ObjectMapper mapper
    ) {
        this.transport = transport;
        this.clearance = clearance;
        this.signer = signer;
        this.properties = properties;
        this.mapper = mapper;
        this.gatewayChat = new GrokWebGatewayChat(transport, properties, mapper);
    }

    Flux<byte[]> chat(
        JsonNode credential,
        ObjectNode body,
        String conversationId,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return chat(credential, body, conversationId, proxyPool, affinityKey,
            ignored -> {});
    }

    Flux<byte[]> chat(
        JsonNode credential,
        ObjectNode body,
        String conversationId,
        Map<String, Object> proxyPool,
        String affinityKey,
        java.util.function.Consumer<JsonNode> credentialPatchSink
    ) {
        var origin = origin();
        var command = sessionCommand(credential, proxyPool, affinityKey);
        return Flux.usingWhen(
            transport.open(command),
            session -> gatewayChat.stream(session, body, conversationId),
            session -> closeSession(session, credentialPatchSink),
            (session, ignored) -> closeSession(session, credentialPatchSink),
            session -> closeSession(session, credentialPatchSink));
    }

    private Mono<Void> closeSession(
        BrowserTransportClient.Session session,
        java.util.function.Consumer<JsonNode> credentialPatchSink
    ) {
        return transport.close(session.id())
            .doOnNext(result -> credentialPatchSink.accept(result.contextPatch()))
            .then();
    }

    Mono<List<DownloadedImage>> generateLiteImages(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        int count
    ) {
        return generateLiteImages(
            credential, proxyPool, affinityKey, prompt, count, ignored -> {});
    }

    Mono<List<DownloadedImage>> generateLiteImages(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        int count,
        java.util.function.Consumer<JsonNode> credentialPatchSink
    ) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("image prompt is required"));
        }
        if (count < 1 || count > 10) {
            return Mono.error(new IllegalArgumentException("image count must be between 1 and 10"));
        }
        return transport.open(mediaSessionCommand(credential, proxyPool, affinityKey))
            .flatMap(session -> Flux.range(0, count)
                .concatMap(ignored -> generateLiteImage(session, prompt))
                .collectList()
                .flatMap(images -> closeSession(session, credentialPatchSink).thenReturn(images))
                .onErrorResume(error -> closeSession(session, credentialPatchSink)
                    .then(Mono.error(error))));
    }

    Mono<List<DownloadedImage>> generateImagineImages(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        int count,
        String aspectRatio,
        String resolution
    ) {
        return generateImagineImages(credential, proxyPool, affinityKey, prompt,
            count, aspectRatio, resolution, ignored -> {});
    }

    Mono<List<DownloadedImage>> generateImagineImages(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        int count,
        String aspectRatio,
        String resolution,
        java.util.function.Consumer<JsonNode> credentialPatchSink
    ) {
        var nativeCount = count > 8 ? 12 : count > 4 ? 8 : 4;
        var collector = new GrokWebImagineCollector(mapper);
        return transport.open(mediaSessionCommand(credential, proxyPool, affinityKey))
            .flatMap(session -> transport.openWebSocket(
                    session.id(), "/ws/imagine/listen", origin(), 30)
                .flatMap(websocket -> transport.sendWebSocket(
                        session.id(), websocket.id(), imagineResetMessage())
                    .then(transport.sendWebSocket(session.id(), websocket.id(),
                        imagineRequestMessage(
                            prompt, aspectRatio, "2k".equals(resolution), nativeCount)))
                    .thenMany(Flux.range(0, 512)
                        .concatMap(ignored -> transport.receiveWebSocket(
                            session.id(), websocket.id()))
                        .filter(frame -> (frame.flags() & 1) != 0)
                        .takeUntil(frame -> collector.accept(
                            frame.body(), count, nativeCount)))
                    .then(Mono.fromCallable(() -> collector.images(count)))
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(image -> image.blob().isBlank()
                        ? downloadImage(session, image.url())
                        : Mono.fromCallable(() -> decodeImageBlob(image.blob())))
                    .collectList()
                    .flatMap(images -> {
                        if (images.size() < count) {
                            return Mono.error(new IllegalStateException(
                                "Grok Imagine returned fewer usable images than requested"));
                        }
                        return transport.closeWebSocket(session.id(), websocket.id())
                            .thenReturn(images);
                    })
                    .onErrorResume(error -> transport.closeWebSocket(
                            session.id(), websocket.id())
                        .then(Mono.error(error))))
                .flatMap(images -> closeSession(session, credentialPatchSink).thenReturn(images))
                .onErrorResume(error -> closeSession(session, credentialPatchSink)
                    .then(Mono.error(error))));
    }

    Mono<DownloadedImage> editImage(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        List<MediaInput> inputs,
        String aspectRatio
    ) {
        return editImage(credential, proxyPool, affinityKey, prompt,
            inputs, aspectRatio, ignored -> {});
    }

    Mono<DownloadedImage> editImage(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey,
        String prompt,
        List<MediaInput> inputs,
        String aspectRatio,
        java.util.function.Consumer<JsonNode> credentialPatchSink
    ) {
        if (inputs.isEmpty() || inputs.size() > 8) {
            return Mono.error(new IllegalArgumentException(
                "Grok Web image editing requires between 1 and 8 images"));
        }
        return transport.open(mediaSessionCommand(credential, proxyPool, affinityKey))
            .flatMap(session -> index(session).cache().flatMap(indexHtml ->
                Flux.fromIterable(inputs)
                    .concatMap(input -> uploadImage(session, indexHtml, input))
                    .concatMap(uploaded -> createMediaPost(session, indexHtml, uploaded.uri())
                        .map(postId -> new UploadedPost(uploaded.uri(), postId)))
                    .collectList()
                    .flatMap(posts -> {
                        var references = posts.stream().map(UploadedPost::uri).toList();
                        var parentId = posts.getFirst().postId();
                        var decoder = new GrokWebImageEventDecoder(mapper);
                        return send(session, "/rest/app-chat/conversations/new",
                                imageEditBody(prompt, references, parentId, aspectRatio), false)
                            .takeUntil(decoder::decode)
                            .then(Mono.fromCallable(decoder::finish))
                            .flatMap(url -> downloadImage(session, url));
                    }))
                .flatMap(image -> closeSession(session, credentialPatchSink).thenReturn(image))
                .onErrorResume(error -> closeSession(session, credentialPatchSink)
                    .then(Mono.error(error))));
    }

    Mono<ProbeResult> keepalive(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return transport.open(sessionCommand(credential, proxyPool, affinityKey))
            .flatMap(session -> gatewayChat.probe(session, probeBody())
                .then(index(session).flatMap(html -> quotaMetadata(session, html)
                    .onErrorReturn(mapper.createObjectNode())))
                .map(ProbeResult::healthy)
                .flatMap(result -> transport.close(session.id())
                    .map(closed -> result.withCredentialPatch(closed.contextPatch())))
                .onErrorResume(error -> transport.close(session.id())
                    .then(Mono.error(error))));
    }

    Mono<AccountSettingsResult> applyAccountSettings(
        JsonNode credential,
        Map<String, Object> proxyPool,
        AccountSettings command,
        String affinityKey
    ) {
        if (!command.acceptTerms() && command.birthDate() == null && !command.enableNsfw()) {
            return Mono.error(new IllegalArgumentException(
                "at least one Grok Web account setting is required"));
        }
        var metadata = mapper.createObjectNode();
        return transport.open(accountSettingsSessionCommand(
                credential, proxyPool, affinityKey))
            .flatMap(session -> {
                Mono<Void> chain = Mono.empty();
                var indexHtml = index(session).cache();
                if (command.acceptTerms()) {
                    chain = chain.then(accountTerms(session))
                        .then(indexHtml.flatMap(html -> productTerms(session, html)))
                        .doOnSuccess(ignored -> {
                            metadata.put("web_terms_version", properties.getTermsVersion());
                            metadata.put("web_terms_accepted_at", Instant.now().toString());
                        });
                }
                if (command.birthDate() != null) {
                    chain = chain.then(indexHtml.flatMap(html ->
                            birthDate(session, html, command.birthDate())))
                        .doOnSuccess(ignored -> metadata.put(
                            "web_birth_date_set_at", Instant.now().toString()));
                }
                if (command.enableNsfw()) {
                    chain = chain.then(indexHtml.flatMap(html -> enableNsfw(session, html)))
                        .doOnSuccess(ignored -> metadata.put(
                            "web_nsfw_enabled_at", Instant.now().toString()));
                }
                return chain.thenReturn(metadata)
                    .flatMap(patch -> transport.close(session.id()).map(closed ->
                        new AccountSettingsResult(patch, closed.contextPatch())))
                    .onErrorResume(error -> transport.close(session.id())
                        .then(Mono.error(error)));
            });
    }

    private Mono<Void> accountTerms(BrowserTransportClient.Session session) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/grpc-web+proto");
        headers.put("x-grpc-web", "1");
        headers.put("x-user-agent", "connect-es/2.1.1");
        return transport.request(session.id(), BrowserTransportClient.Request.binary(
                "POST", "/auth_mgmt.AuthManagement/SetTosAcceptedVersion",
                Map.copyOf(headers), BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
                ACCEPT_TERMS_BODY, 30, accountsOrigin(), "/accept-tos"))
            .flatMap(response -> validateSettingResponse(response, true, false));
    }

    private Mono<Void> productTerms(
        BrowserTransportClient.Session session,
        String indexHtml
    ) {
        return signedRequest(session, "POST", "/rest/auth/set-tos-accepted",
                mapper.createObjectNode().put("tosVersion", properties.getTermsVersion()),
                indexHtml, 30)
            .flatMap(response -> validateSettingResponse(response, false, false));
    }

    private Mono<Void> birthDate(
        BrowserTransportClient.Session session,
        String indexHtml,
        LocalDate birthDate
    ) {
        var body = mapper.createObjectNode().put(
            "birthDate", birthDate + "T16:00:00.000Z");
        return signedRequest(session, "POST", "/rest/auth/set-birth-date", body, indexHtml, 30)
            .flatMap(response -> validateSettingResponse(response, false, true));
    }

    private Mono<Void> enableNsfw(
        BrowserTransportClient.Session session,
        String indexHtml
    ) {
        var path = "/auth_mgmt.AuthManagement/UpdateUserFeatureControls";
        var headers = requestHeaders();
        headers.put("Content-Type", "application/grpc-web+proto");
        headers.put("x-grpc-web", "1");
        headers.put("x-statsig-id", signer.sign("POST", path, indexHtml));
        return transport.request(session.id(), BrowserTransportClient.Request.binary(
                "POST", path, Map.copyOf(headers),
                BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
                ENABLE_NSFW_BODY, 30, origin(), "/"))
            .flatMap(response -> validateSettingResponse(response, true, false));
    }

    private Mono<Void> validateSettingResponse(
        BrowserTransportClient.BufferedResponse response,
        boolean grpcWeb,
        boolean birthDate
    ) {
        if (response.status() == 401) {
            return Mono.error(new AccountSettingsException(401, "Grok Web SSO expired"));
        }
        if (birthDate && response.status() == 429
            && response.text().contains(BIRTH_DATE_LOCKED)) {
            return Mono.empty();
        }
        if (!response.successful()) {
            return Mono.error(new AccountSettingsException(
                response.status(), "Grok Web account setting returned HTTP " + response.status()));
        }
        if (grpcWeb) validateGrpcWeb(response.body());
        return Mono.empty();
    }

    private void validateGrpcWeb(byte[] body) {
        var offset = 0;
        while (offset + 5 <= body.length) {
            var flags = body[offset] & 0xff;
            var length = ByteBuffer.wrap(body, offset + 1, 4).getInt();
            if (length < 0 || offset + 5L + length > body.length) {
                throw new AccountSettingsException(502,
                    "Grok Web returned a malformed gRPC-Web frame");
            }
            if ((flags & 0x80) != 0) {
                var trailer = new String(body, offset + 5, length, StandardCharsets.US_ASCII);
                for (var line : trailer.split("\\r?\\n")) {
                    if (line.toLowerCase().startsWith("grpc-status:")) {
                        var status = line.substring("grpc-status:".length()).trim();
                        if (!status.isBlank() && !"0".equals(status)) {
                            throw new AccountSettingsException(502,
                                "Grok Web account setting gRPC status " + status);
                        }
                    }
                }
            }
            offset += 5 + length;
        }
        if (offset != body.length) {
            throw new AccountSettingsException(502,
                "Grok Web returned a truncated gRPC-Web frame");
        }
    }

    private Flux<byte[]> send(
        BrowserTransportClient.Session session,
        String path,
        ObjectNode body,
        boolean retry
    ) {
        return send(session, path, body, retry, false);
    }

    private Flux<byte[]> send(
        BrowserTransportClient.Session session,
        String path,
        ObjectNode body,
        boolean statsigRetried,
        boolean clearanceRetried
    ) {
        return index(session)
            .flatMapMany(html -> {
                var headers = requestHeaders();
                headers.put("x-statsig-id", signer.sign("POST", path, html));
                var preflight = new GrokWebStreamPreflight(mapper);
                return transport.stream(session.id(), new BrowserTransportClient.Request(
                    "POST", path, Map.copyOf(headers),
                    BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH, body, 180))
                    .concatMapIterable(preflight::accept)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(preflight.finish())));
            })
            .onErrorResume(error -> !statsigRetried && isCodeSeven(error),
                ignored -> send(session, path, body, true, clearanceRetried))
            .onErrorResume(error -> !clearanceRetried && isCloudflareChallenge(error),
                ignored -> clearance.recover(session)
                    .thenMany(send(session, path, body, statsigRetried, true)));
    }

    private Mono<DownloadedImage> generateLiteImage(
        BrowserTransportClient.Session session,
        String prompt
    ) {
        return Mono.defer(() -> {
            var decoder = new GrokWebImageEventDecoder(mapper);
            return send(session, "/rest/app-chat/conversations/new",
                    imageLiteBody(prompt), false)
                .takeUntil(decoder::decode)
                .then(Mono.fromCallable(decoder::finish))
                .flatMap(url -> downloadImage(session, url));
        });
    }

    private Mono<DownloadedImage> downloadImage(
        BrowserTransportClient.Session session,
        String rawUrl
    ) {
        var assets = assetsOrigin();
        URI url;
        try {
            url = rawUrl.startsWith("/") ? assets.resolve(rawUrl) : URI.create(rawUrl);
        } catch (IllegalArgumentException error) {
            return Mono.error(new IllegalArgumentException(
                "Grok Web returned an invalid image URL", error));
        }
        var targetOrigin = trustedAssetOrigins().stream()
            .filter(origin -> origin.getHost().equalsIgnoreCase(url.getHost()))
            .findFirst().orElse(null);
        if (!"https".equalsIgnoreCase(url.getScheme())
            || targetOrigin == null
            || url.getUserInfo() != null || url.getFragment() != null) {
            return Mono.error(new IllegalArgumentException(
                "Grok Web returned an image URL outside the configured asset origin"));
        }
        var path = url.getRawPath();
        if (url.getRawQuery() != null) path += "?" + url.getRawQuery();
        return transport.request(session.id(), new BrowserTransportClient.Request(
                "GET", path, Map.of("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.8"),
                BrowserTransportClient.FingerprintProfile.NONE, null, 120, targetOrigin))
            .map(response -> {
                if (!response.successful()) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        response.status(),
                        "Grok Web image download returned HTTP " + response.status());
                }
                var contentType = response.contentType().split(";", 2)[0]
                    .trim().toLowerCase();
                if (!contentType.startsWith("image/") || response.body().length == 0) {
                    throw new IllegalArgumentException(
                        "Grok Web image download returned unsupported content");
                }
                var detected = MediaInputValidation.requireRasterImage(
                    contentType, response.body());
                return new DownloadedImage(detected, response.body());
            });
    }

    private Mono<UploadedFile> uploadImage(
        BrowserTransportClient.Session session,
        String indexHtml,
        MediaInput input
    ) {
        var multipart = directUploadBody(input);
        return transport.request(session.id(), BrowserTransportClient.Request.binary(
                "POST", "/http/upload-file-v2/direct",
                Map.of("Content-Type", multipart.contentType()),
                BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
                multipart.body(), 120, null, "/imagine"))
            .flatMap(response -> {
                if (Set.of(404, 405, 410, 501).contains(response.status())) {
                    return legacyUpload(session, indexHtml, input);
                }
                if (!response.successful()) {
                    return Mono.error(new BrowserTransportClient.BrowserTransportException(
                        response.status(),
                        "Grok Web direct file upload returned HTTP " + response.status()));
                }
                return Mono.just(parseUploadedFile(response.body()));
            });
    }

    private Mono<UploadedFile> legacyUpload(
        BrowserTransportClient.Session session,
        String indexHtml,
        MediaInput input
    ) {
        var body = mapper.createObjectNode()
            .put("fileName", safeFilename(input.filename()))
            .put("fileMimeType", input.contentType())
            .put("content", Base64.getEncoder().encodeToString(input.content()));
        return signedRequest(session, "POST", "/rest/app-chat/upload-file",
                body, indexHtml, 120)
            .map(response -> {
                if (!response.successful()) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        response.status(),
                        "Grok Web legacy file upload returned HTTP " + response.status());
                }
                return parseUploadedFile(response.body());
            });
    }

    private UploadedFile parseUploadedFile(byte[] body) {
        var root = mapper.readTree(body);
        var metadata = root.path("fileMetadata");
        if (!metadata.isObject()) metadata = root;
        var uri = metadata.path("fileUri").asText("").trim();
        if (uri.isBlank()) {
            throw new IllegalArgumentException(
                "Grok Web upload succeeded without a file URI");
        }
        return new UploadedFile(absoluteAssetUrl(uri));
    }

    private Mono<String> createMediaPost(
        BrowserTransportClient.Session session,
        String indexHtml,
        String mediaUri
    ) {
        var body = mapper.createObjectNode()
            .put("mediaType", "MEDIA_POST_TYPE_IMAGE")
            .put("mediaUrl", mediaUri);
        return signedRequest(session, "POST", "/rest/media/post/create",
                body, indexHtml, 60)
            .map(response -> {
                if (!response.successful()) {
                    throw new BrowserTransportClient.BrowserTransportException(
                        response.status(),
                        "Grok Web media post returned HTTP " + response.status());
                }
                var id = mapper.readTree(response.body()).path("post").path("id")
                    .asText("").trim();
                if (id.isBlank()) {
                    throw new IllegalArgumentException(
                        "Grok Web media post response did not contain an id");
                }
                return id;
            });
    }

    private MultipartBody directUploadBody(MediaInput input) {
        var boundary = "----Any2API" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        writeAscii(body, "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + safeFilename(input.filename()) + "\"\r\n"
            + "Content-Type: " + input.contentType() + "\r\n\r\n");
        body.writeBytes(input.content());
        writeAscii(body, "\r\n--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file_source\"\r\n\r\n"
            + "IMAGINE_SELF_UPLOAD_FILE_SOURCE\r\n"
            + "--" + boundary + "--\r\n");
        return new MultipartBody(body.toByteArray(),
            "multipart/form-data; boundary=" + boundary);
    }

    private void writeAscii(ByteArrayOutputStream target, String value) {
        target.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String safeFilename(String value) {
        var safe = value == null ? "image" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "image" : safe.substring(0, Math.min(180, safe.length()));
    }

    private String absoluteAssetUrl(String value) {
        if (value.startsWith("https://")) return value;
        return assetsOrigin().resolve(value.startsWith("/") ? value : "/" + value).toString();
    }

    private Mono<String> index(BrowserTransportClient.Session session) {
        return index(session, false);
    }

    private Mono<String> index(
        BrowserTransportClient.Session session,
        boolean clearanceRetried
    ) {
        return transport.request(session.id(), new BrowserTransportClient.Request(
                "GET", "/index", Map.of(),
                BrowserTransportClient.FingerprintProfile.NAVIGATION, null, 90))
            .map(response -> {
                if (!response.successful()) {
                    var reason = response.status() == 403
                        && isCloudflareChallenge(response.text())
                        ? "Grok Web index returned a Cloudflare challenge"
                        : "Grok Web index returned HTTP " + response.status();
                    throw new BrowserTransportClient.BrowserTransportException(
                        response.status(), reason);
                }
                return response.text();
            })
            .onErrorResume(error -> !clearanceRetried && isCloudflareChallenge(error),
                ignored -> clearance.recover(session).then(index(session, true)));
    }

    private Mono<JsonNode> quotaMetadata(
        BrowserTransportClient.Session session,
        String indexHtml
    ) {
        var modesPath = "/rest/modes";
        return signedRequest(session, "POST", modesPath,
                mapper.createObjectNode().put("locale", "en-US"), indexHtml, 60)
            .flatMap(response -> {
                if (!response.successful()) {
                    return Mono.error(new IllegalStateException(
                        "Grok Web modes sync returned HTTP " + response.status()));
                }
                var root = mapper.readTree(response.body());
                var raw = root.path("modes").isArray() ? root.path("modes") : root;
                if (!raw.isArray()) {
                    return Mono.error(new IllegalArgumentException(
                        "Grok Web modes response has an unsupported shape"));
                }
                var modes = new ArrayList<String>();
                for (var item : raw) {
                    var mode = item.path("id").asText("").trim();
                    if (MODE_ID.matcher(mode).matches() && !modes.contains(mode)) modes.add(mode);
                    if (modes.size() == 16) break;
                }
                if (modes.isEmpty()) {
                    return Mono.error(new IllegalStateException(
                        "Grok Web modes response contained no modes"));
                }
                return reactor.core.publisher.Flux.fromIterable(modes)
                    .concatMap(mode -> quota(session, mode, indexHtml))
                    .collectMap(ModeQuota::mode, ModeQuota::quota)
                    .map(quotas -> metadata(modes, quotas));
            });
    }

    private Mono<ModeQuota> quota(
        BrowserTransportClient.Session session,
        String mode,
        String indexHtml
    ) {
        var path = "/rest/rate-limits";
        return signedRequest(session, "POST", path,
                mapper.createObjectNode().put("modelName", mode), indexHtml, 60)
            .flatMap(response -> {
                if (!response.successful()) return Mono.empty();
                var value = mapper.readTree(response.body());
                if (!value.isObject()) return Mono.empty();
                var quota = mapper.createObjectNode();
                for (var field : List.of(
                    "windowSizeSeconds", "remainingQueries", "waitTimeSeconds",
                    "totalQueries", "remainingTokens", "totalTokens",
                    "lowEffortRateLimits", "highEffortRateLimits", "preGenerationDelayMs"
                )) {
                    if (value.has(field)) quota.set(field, value.path(field));
                }
                return Mono.just(new ModeQuota(mode, quota));
            });
    }

    private JsonNode metadata(List<String> modes, Map<String, ObjectNode> quotas) {
        var result = mapper.createObjectNode()
            .put("tier", modes.contains("heavy") ? "heavy"
                : modes.contains("auto") || modes.contains("expert") ? "super" : "basic")
            .put("quota_synced_at", Instant.now().toString());
        result.set("available_modes", mapper.valueToTree(modes));
        result.set("quota", mapper.valueToTree(quotas));
        return result;
    }

    private Mono<BrowserTransportClient.BufferedResponse> signedRequest(
        BrowserTransportClient.Session session,
        String method,
        String path,
        JsonNode body,
        String indexHtml,
        int timeoutSeconds
    ) {
        var headers = requestHeaders();
        headers.put("x-statsig-id", signer.sign(method, path, indexHtml));
        return transport.request(session.id(), new BrowserTransportClient.Request(
            method, path, Map.copyOf(headers),
            BrowserTransportClient.FingerprintProfile.SAME_ORIGIN_FETCH,
            body, timeoutSeconds));
    }

    private BrowserTransportClient.OpenCommand sessionCommand(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var origin = origin();
        return new BrowserTransportClient.OpenCommand(
            origin,
            cookies(credential),
            List.of("." + origin.getHost()),
            credential.path("user_agent").asText(properties.getUserAgent()),
            credential.path("browser_profile").asText("chrome136"),
            "v2",
            proxyPool == null ? Map.of() : proxyPool,
            240, List.of(), affinityKey, !affinityKey.isBlank(),
            clearanceRevision(credential));
    }

    private BrowserTransportClient.OpenCommand accountSettingsSessionCommand(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        return new BrowserTransportClient.OpenCommand(
            origin(), cookies(credential), properties.getCookieDomains(),
            credential.path("user_agent").asText(properties.getUserAgent()),
            credential.path("browser_profile").asText("chrome136"), "v2",
            proxyPool == null ? Map.of() : proxyPool, 240, List.of(accountsOrigin()),
            affinityKey, !affinityKey.isBlank(), clearanceRevision(credential));
    }

    private BrowserTransportClient.OpenCommand mediaSessionCommand(
        JsonNode credential,
        Map<String, Object> proxyPool,
        String affinityKey
    ) {
        var origin = origin();
        return new BrowserTransportClient.OpenCommand(
            origin, cookies(credential), List.of("." + origin.getHost()),
            credential.path("user_agent").asText(properties.getUserAgent()),
            credential.path("browser_profile").asText("chrome136"), "v2",
            proxyPool == null ? Map.of() : proxyPool, 600, trustedAssetOrigins(),
            affinityKey, !affinityKey.isBlank(), clearanceRevision(credential));
    }

    private String clearanceRevision(JsonNode credential) {
        var refreshed = credential.path("clearance_refreshed_at").asText("").trim();
        if (!refreshed.isBlank()) return refreshed;
        return credential.path("clearance_expires_at").asText("").trim();
    }

    private ObjectNode imageLiteBody(String prompt) {
        var body = mapper.createObjectNode()
            .put("disableMemory", true)
            .put("disableSearch", false)
            .put("disableSelfHarmShortCircuit", false)
            .put("disableTextFollowUps", false)
            .put("enableImageGeneration", true)
            .put("enableImageStreaming", true)
            .put("enableSideBySide", true)
            .put("forceConcise", false)
            .put("forceSideBySide", false)
            .put("imageGenerationCount", 2)
            .put("isAsyncChat", false)
            .put("message", "Drawing: " + prompt)
            .put("modeId", "fast")
            .put("returnImageBytes", false)
            .put("returnRawGrokInXaiRequest", false)
            .put("sendFinalMetadata", true)
            .put("temporary", true);
        body.set("collectionIds", mapper.createArrayNode());
        body.set("disabledConnectorIds", mapper.createArrayNode());
        body.set("fileAttachments", mapper.createArrayNode());
        body.set("imageAttachments", mapper.createArrayNode());
        body.set("responseMetadata", mapper.createObjectNode());
        body.set("deviceEnvInfo", mapper.createObjectNode()
            .put("darkModeEnabled", false).put("devicePixelRatio", 2)
            .put("screenHeight", 1328).put("screenWidth", 2056)
            .put("viewportHeight", 1083).put("viewportWidth", 2056));
        return body;
    }

    private ObjectNode imagineResetMessage() {
        var content = mapper.createArrayNode().add(
            mapper.createObjectNode().put("type", "reset"));
        return mapper.createObjectNode()
            .put("type", "conversation.item.create")
            .put("timestamp", System.currentTimeMillis())
            .set("item", mapper.createObjectNode()
                .put("type", "message")
                .set("content", content));
    }

    private ObjectNode imagineRequestMessage(
        String prompt,
        String aspectRatio,
        boolean pro,
        int generations
    ) {
        var propertiesNode = mapper.createObjectNode()
            .put("section_count", 0)
            .put("is_kids_mode", false)
            .put("enable_nsfw", properties.isAllowNsfw())
            .put("skip_upsampler", false)
            .put("enable_side_by_side", true)
            .put("is_initial", false)
            .put("aspect_ratio", aspectRatio)
            .put("enable_pro", pro)
            .put("num_generations", generations);
        var content = mapper.createArrayNode().add(mapper.createObjectNode()
            .put("requestId", "img_" + UUID.randomUUID().toString().replace("-", ""))
            .put("text", prompt)
            .put("type", "input_text")
            .set("properties", propertiesNode));
        return mapper.createObjectNode()
            .put("type", "conversation.item.create")
            .put("timestamp", System.currentTimeMillis())
            .set("item", mapper.createObjectNode()
                .put("type", "message")
                .set("content", content));
    }

    private ObjectNode imageEditBody(
        String prompt,
        List<String> references,
        String parentId,
        String aspectRatio
    ) {
        var config = mapper.createObjectNode()
            .put("parentPostId", parentId)
            .set("imageReferences", mapper.valueToTree(references));
        if (!aspectRatio.isBlank()) config.put("aspectRatio", aspectRatio);
        var modelMap = mapper.createObjectNode()
            .put("imageEditModel", "imagine")
            .set("imageEditModelConfig", config);
        var responseMetadata = mapper.createObjectNode()
            .set("modelConfigOverride", mapper.createObjectNode()
                .set("modelMap", modelMap));
        return mapper.createObjectNode()
            .put("temporary", true)
            .put("modelName", "imagine-image-edit")
            .put("message", prompt)
            .put("enableImageGeneration", true)
            .put("returnImageBytes", false)
            .put("returnRawGrokInXaiRequest", false)
            .put("enableImageStreaming", true)
            .put("imageGenerationCount", 2)
            .put("forceConcise", false)
            .put("enableSideBySide", true)
            .put("sendFinalMetadata", true)
            .put("isReasoning", false)
            .put("disableTextFollowUps", true)
            .put("disableMemory", false)
            .put("forceSideBySide", false)
            .set("responseMetadata", responseMetadata);
    }

    private DownloadedImage decodeImageBlob(String value) {
        var encoded = value.trim();
        var contentType = "image/jpeg";
        if (encoded.regionMatches(true, 0, "data:", 0, 5)) {
            var comma = encoded.indexOf(',');
            if (comma < 0 || !encoded.substring(0, comma).toLowerCase().contains(";base64")) {
                throw new IllegalArgumentException("Grok Imagine returned an invalid image blob");
            }
            var metadata = encoded.substring(5, comma).split(";", 2)[0].trim().toLowerCase();
            if (metadata.startsWith("image/")) contentType = metadata;
            encoded = encoded.substring(comma + 1);
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            content = Base64.getUrlDecoder().decode(encoded);
        }
        if (content.length == 0 || content.length > (12 << 20)) {
            throw new IllegalArgumentException("Grok Imagine image blob has an invalid size");
        }
        var detected = MediaInputValidation.requireRasterImage(
            contentType, content);
        return new DownloadedImage(detected, content);
    }

    private ObjectNode probeBody() {
        var body = mapper.createObjectNode()
            .put("temporary", true)
            .put("message", "Reply with OK.")
            .put("modeId", "fast")
            .put("disableMemory", true)
            .put("disableSearch", true)
            .put("enableImageGeneration", false)
            .put("enableImageStreaming", false)
            .put("enableSideBySide", false)
            .put("sendFinalMetadata", true);
        body.set("fileAttachments", mapper.createArrayNode());
        body.set("imageAttachments", mapper.createArrayNode());
        body.set("responseMetadata", mapper.createObjectNode());
        return body;
    }

    private LinkedHashMap<String, String> requestHeaders() {
        return new LinkedHashMap<>(Map.of(
            "Content-Type", "application/json",
            "x-xai-request-id", UUID.randomUUID().toString()));
    }

    private Map<String, String> cookies(JsonNode credential) {
        var cookies = new LinkedHashMap<String, String>();
        var sso = first(credential, "sso", "sso-rw", "sso_rw", "sso_token");
        if (sso.isBlank()) throw new IllegalArgumentException("Grok Web SSO token is missing");
        cookies.put("sso", sso);
        cookies.put("sso-rw", sso);
        var cloudflare = credential.path("cloudflare_cookies")
            .asText(credential.path("cf_cookies").asText(""));
        for (var part : cloudflare.split(";")) {
            var separator = part.indexOf('=');
            if (separator <= 0) continue;
            var name = part.substring(0, separator).trim();
            var value = part.substring(separator + 1).trim();
            if (COOKIE_NAME.matcher(name).matches() && validCookieValue(value)) {
                cookies.put(name, value);
            }
        }
        return Map.copyOf(cookies);
    }

    private String first(JsonNode credential, String... fields) {
        for (var field : fields) {
            var value = credential.path(field).asText("").trim();
            if (!value.isBlank()) return value.startsWith("sso=")
                ? value.substring(4).trim() : value;
        }
        return "";
    }

    private boolean validCookieValue(String value) {
        return !value.isBlank() && value.length() <= 8192
            && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private boolean isCodeSeven(Throwable error) {
        return error instanceof BrowserTransportClient.BrowserTransportException upstream
            && upstream.status() == 403
            && CODE_SEVEN.matcher(upstream.getMessage()).matches()
            && !upstream.getMessage().toLowerCase().contains("blocked-user")
            && !upstream.getMessage().toLowerCase().contains("user is blocked");
    }

    static boolean isCloudflareChallenge(Throwable error) {
        return error instanceof BrowserTransportClient.BrowserTransportException upstream
            && upstream.status() == 403
            && isCloudflareChallenge(upstream.getMessage());
    }

    static boolean isCloudflareChallenge(String value) {
        var normalized = value == null ? "" : value.toLowerCase();
        return normalized.contains("cloudflare challenge")
            || normalized.contains("challenge-platform")
            || normalized.contains("cf-chl-")
            || normalized.contains("just a moment")
            || normalized.contains("checking your browser");
    }

    private boolean isCodeSeven(BrowserTransportClient.BufferedResponse response) {
        if (response.status() != 403) return false;
        try {
            return mapper.readTree(response.body()).path("error").path("code").asInt(-1) == 7;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private URI origin() {
        return validatedOrigin(properties.getBaseUrl(), "Grok Web base URL");
    }

    private URI accountsOrigin() {
        return validatedOrigin(properties.getAccountsBaseUrl(), "Grok accounts base URL");
    }

    private URI assetsOrigin() {
        return validatedOrigin(properties.getAssetsBaseUrl(), "Grok assets base URL");
    }

    private List<URI> trustedAssetOrigins() {
        var values = new ArrayList<URI>();
        values.add(assetsOrigin());
        for (var value : properties.getImageAssetOrigins()) {
            var origin = validatedOrigin(value, "Grok image asset origin");
            if (!values.contains(origin)) values.add(origin);
        }
        return List.copyOf(values);
    }

    private URI validatedOrigin(URI value, String label) {
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
            || (value.getPath() != null && !value.getPath().isBlank()
                && !"/".equals(value.getPath()))) {
            throw new IllegalArgumentException(label + " must be an HTTPS origin");
        }
        return URI.create("https://" + value.getAuthority());
    }

    record AccountSettings(boolean acceptTerms, LocalDate birthDate, boolean enableNsfw) {}

    record AccountSettingsResult(JsonNode metadataPatch, JsonNode credentialPatch) {}

    record DownloadedImage(String contentType, byte[] content) {
        DownloadedImage { content = content.clone(); }
    }

    private record UploadedFile(String uri) {}
    private record UploadedPost(String uri, String postId) {}
    private record MultipartBody(byte[] body, String contentType) {}

    static final class AccountSettingsException extends RuntimeException {
        private final int status;

        AccountSettingsException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() { return status; }
    }

    record ProbeResult(
        boolean healthy,
        boolean authExpired,
        boolean terminal,
        String errorClass,
        JsonNode credentialPatch,
        JsonNode metadataPatch
    ) {
        static ProbeResult healthy(JsonNode metadataPatch) {
            return new ProbeResult(true, false, false, "",
                tools.jackson.databind.node.MissingNode.getInstance(), metadataPatch);
        }

        static ProbeResult failed(boolean authExpired, boolean terminal, String errorClass) {
            return new ProbeResult(false, authExpired, terminal, errorClass,
                tools.jackson.databind.node.MissingNode.getInstance(),
                tools.jackson.databind.node.MissingNode.getInstance());
        }

        ProbeResult withCredentialPatch(JsonNode patch) {
            return new ProbeResult(healthy, authExpired, terminal, errorClass,
                patch != null && patch.isObject()
                    ? patch : tools.jackson.databind.node.MissingNode.getInstance(),
                metadataPatch);
        }
    }

    private record ModeQuota(String mode, ObjectNode quota) {}
}
