package com.any2api.provider.grok_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.any2api.transport.BrowserTransportClient;
import com.any2api.transport.BrowserClearanceCoordinator;
import com.any2api.media.MediaInput;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class GrokWebImageProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void imageDecoderFindsFinalAssetAcrossArbitraryChunkBoundaries() {
        var decoder = new GrokWebImageEventDecoder(mapper);
        var bytes = ("noise{\"result\":{\"response\":{" +
            "\"streamingImageGenerationResponse\":{" +
            "\"progress\":100,\"imageUrl\":\"/generated/image.webp?token=x\"}}}}")
            .getBytes(StandardCharsets.UTF_8);

        for (var index = 0; index < bytes.length; index += 3) {
            decoder.decode(java.util.Arrays.copyOfRange(
                bytes, index, Math.min(bytes.length, index + 3)));
        }

        assertThat(decoder.finish()).isEqualTo("/generated/image.webp?token=x");
    }

    @Test
    void imageLiteUsesOneAffineSessionAndDownloadsProtectedAssetsBeforeClose() {
        var transport = mock(BrowserTransportClient.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var opens = new ArrayList<BrowserTransportClient.OpenCommand>();
        var requests = new ArrayList<BrowserTransportClient.Request>();
        when(transport.open(any())).thenAnswer(invocation -> {
            opens.add(invocation.getArgument(0));
            return Mono.just(new BrowserTransportClient.Session("session", "ua", "chrome136"));
        });
        when(transport.request(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            requests.add(request);
            if (request.path().equals("/index")) {
                return Mono.just(new BrowserTransportClient.BufferedResponse(
                    200, "text/html", "<html>index</html>".getBytes(StandardCharsets.UTF_8)));
            }
            return Mono.just(new BrowserTransportClient.BufferedResponse(
                200, "image/webp; charset=binary", webp()));
        });
        when(transport.stream(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            requests.add(request);
            var frame = ("{\"result\":{\"response\":{\"modelResponse\":{" +
                "\"generatedImageUrls\":[\"/generated/image.webp?token=signed\"]}}}}")
                .getBytes(StandardCharsets.UTF_8);
            return Flux.just(
                java.util.Arrays.copyOfRange(frame, 0, 11),
                java.util.Arrays.copyOfRange(frame, 11, frame.length));
        });
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(
                mapper.createObjectNode().put("cloudflare_cookies", "cf_clearance=fresh"))));
        when(signer.sign(anyString(), anyString(), anyString())).thenReturn("signed");
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class), signer,
            new GrokWebProperties(), mapper);

        var credentialPatch = new AtomicReference<tools.jackson.databind.JsonNode>();
        var images = protocol.generateLiteImages(
            mapper.createObjectNode().put("sso", "secret"), Map.of(),
            "identity-group", "a precise blueprint", 2, credentialPatch::set).block();

        assertThat(images).hasSize(2).allSatisfy(image -> {
            assertThat(image.contentType()).isEqualTo("image/webp");
            assertThat(image.content()).containsExactly(webp());
        });
        assertThat(credentialPatch.get().path("cloudflare_cookies").asText())
            .isEqualTo("cf_clearance=fresh");
        assertThat(opens).singleElement().satisfies(command -> {
            assertThat(command.origins()).containsExactly(
                URI.create("https://assets.grok.com"),
                URI.create("https://imagine-public.x.ai"),
                URI.create("https://imgen.x.ai"));
            assertThat(command.proxyAffinityKey()).isEqualTo("identity-group");
            assertThat(command.strictProxyAffinity()).isTrue();
        });
        assertThat(requests.stream().filter(request -> request.path().equals(
            "/rest/app-chat/conversations/new"))).hasSize(2);
        assertThat(requests.stream().filter(request -> request.origin() != null
            && request.origin().equals(URI.create("https://assets.grok.com"))))
            .hasSize(2)
            .allSatisfy(request -> assertThat(request.path())
                .isEqualTo("/generated/image.webp?token=signed"));
        assertThat(requests.stream()
            .filter(request -> request.path().equals("/rest/app-chat/conversations/new"))
            .map(request -> request.body().path("message").asText()))
            .containsOnly("Drawing: a precise blueprint");
    }

    @Test
    void imagineWebSocketUsesProviderMessagesAndStopsAfterRequestedFinalImage() {
        var transport = mock(BrowserTransportClient.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var sent = new ArrayList<tools.jackson.databind.JsonNode>();
        var receives = new AtomicInteger();
        when(transport.open(any())).thenReturn(Mono.just(
            new BrowserTransportClient.Session("session", "ua", "chrome136")));
        when(transport.openWebSocket("session", "/ws/imagine/listen",
            URI.create("https://grok.com"), 30)).thenReturn(Mono.just(
                new BrowserTransportClient.WebSocketHandle("websocket")));
        when(transport.sendWebSocket(anyString(), anyString(), any()))
            .thenAnswer(invocation -> {
                sent.add(invocation.getArgument(2));
                return Mono.empty();
            });
        when(transport.receiveWebSocket("session", "websocket")).thenAnswer(ignored -> {
            var body = receives.getAndIncrement() == 0
                ? "{\"type\":\"image\",\"image_id\":\"one\"," +
                    "\"url\":\"/generated/one.webp\",\"percentage_complete\":100}"
                : "{\"type\":\"json\",\"image_id\":\"one\"," +
                    "\"current_status\":\"completed\",\"moderated\":false}";
            return Mono.just(new BrowserTransportClient.WebSocketFrame(
                body.getBytes(StandardCharsets.UTF_8), 1));
        });
        when(transport.request(anyString(), any())).thenReturn(Mono.just(
            new BrowserTransportClient.BufferedResponse(
                200, "image/webp", webp())));
        when(transport.closeWebSocket("session", "websocket")).thenReturn(Mono.empty());
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(mapper.createObjectNode())));
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class), signer,
            new GrokWebProperties(), mapper);

        var images = protocol.generateImagineImages(
            mapper.createObjectNode().put("sso", "secret"), Map.of(),
            "identity", "city at night", 1, "16:9", "2k").block();

        assertThat(images).singleElement().satisfies(image ->
            assertThat(image.content()).containsExactly(webp()));
        assertThat(receives).hasValue(2);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).path("item").path("content").get(0)
            .path("type").asText()).isEqualTo("reset");
        var properties = sent.get(1).path("item").path("content").get(0)
            .path("properties");
        assertThat(properties.path("aspect_ratio").asText()).isEqualTo("16:9");
        assertThat(properties.path("enable_pro").asBoolean()).isTrue();
        assertThat(properties.path("num_generations").asInt()).isEqualTo(4);
    }

    @Test
    void imageEditKeepsUploadPostEditAndDownloadInOneSession() {
        var transport = mock(BrowserTransportClient.class);
        var signer = mock(GrokWebStatsigSigner.class);
        var requests = new ArrayList<BrowserTransportClient.Request>();
        when(transport.open(any())).thenReturn(Mono.just(
            new BrowserTransportClient.Session("session", "ua", "chrome136")));
        when(transport.request(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            requests.add(request);
            var body = switch (request.path()) {
                case "/index" -> "<html>index</html>";
                case "/http/upload-file-v2/direct" ->
                    "{\"fileMetadata\":{\"fileUri\":\"/users/u/input.png\"}}";
                case "/rest/media/post/create" -> "{\"post\":{\"id\":\"post-1\"}}";
                default -> "image";
            };
            var contentType = request.path().startsWith("/generated/")
                ? "image/png" : "application/json";
            var bytes = request.path().startsWith("/generated/")
                ? png() : body.getBytes(StandardCharsets.UTF_8);
            return Mono.just(new BrowserTransportClient.BufferedResponse(
                200, contentType, bytes));
        });
        when(transport.stream(anyString(), any())).thenAnswer(invocation -> {
            BrowserTransportClient.Request request = invocation.getArgument(1);
            requests.add(request);
            return Flux.just(("{\"result\":{\"response\":{" +
                "\"streamingImageGenerationResponse\":{\"progress\":100," +
                "\"imageUrl\":\"/generated/edited.png\"}}}}")
                .getBytes(StandardCharsets.UTF_8));
        });
        when(transport.close("session")).thenReturn(Mono.just(
            new BrowserTransportClient.CloseResult(mapper.createObjectNode())));
        when(signer.sign(anyString(), anyString(), anyString())).thenReturn("signed");
        var protocol = new GrokWebProtocolClient(
            transport, mock(BrowserClearanceCoordinator.class), signer,
            new GrokWebProperties(), mapper);

        var image = protocol.editImage(
            mapper.createObjectNode().put("sso", "secret"), Map.of(), "identity",
            "make it blue", List.of(new MediaInput(
                "input.png", "image/png", new byte[] {1, 2, 3})), "1:1").block();

        assertThat(image.content()).containsExactly(png());
        var upload = requests.stream().filter(request -> request.path().equals(
            "/http/upload-file-v2/direct")).findFirst().orElseThrow();
        assertThat(upload.rawBody()).containsSequence("IMAGINE_SELF_UPLOAD_FILE_SOURCE"
            .getBytes(StandardCharsets.US_ASCII));
        var edit = requests.stream().filter(request -> request.path().equals(
            "/rest/app-chat/conversations/new")).findFirst().orElseThrow();
        var config = edit.body().path("responseMetadata").path("modelConfigOverride")
            .path("modelMap").path("imageEditModelConfig");
        assertThat(config.path("parentPostId").asText()).isEqualTo("post-1");
        assertThat(config.path("imageReferences").get(0).asText())
            .isEqualTo("https://assets.grok.com/users/u/input.png");
    }

    private byte[] webp() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    }

    private byte[] png() {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0
        };
    }
}
