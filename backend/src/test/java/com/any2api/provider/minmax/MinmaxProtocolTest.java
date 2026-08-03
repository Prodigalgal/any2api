package com.any2api.provider.minmax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.RandomModelRole;
import com.any2api.proxy.ProxyPoolService;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MinmaxProtocolTest {
    @Test
    void excludesUnacceptedMultimodalModelFromRandomRouting() {
        var provider = new MinmaxProvider(
            mock(MinmaxTransportClient.class), mock(ProxyPoolService.class),
            mock(MinmaxRequestMapper.class), mock(MinmaxMediaUploader.class),
            new ObjectMapper());

        assertThat(provider.manifest().randomModelPreferences())
            .containsKey(RandomModelRole.TOP_TEXT)
            .doesNotContainKey(RandomModelRole.TOP_MULTIMODAL);
    }

    @Test
    void extractsRotatingRequestProfileFromOfficialFrontendCode() {
        var script = """
            let common={version_code:\"33333\"};
            headers[\"x-signature\"]=hash(`${seconds}rotating-signature-salt${body}`);
            function sign(e){let {hasSearchParamsPath:path}=e;
              return hash(`${encodeURIComponent(path)}_${body}${hash(time.toString())}tailv`)}
            """;

        var profile = MinmaxOfficialProfileRefresher.parseProfile(script);

        assertThat(profile.signatureSalt()).isEqualTo("rotating-signature-salt");
        assertThat(profile.yySalt()).isEqualTo("tailv");
        assertThat(profile.versionCode()).isEqualTo("33333");
    }

    @Test
    void signsControlAndStreamRequestsWithOfficialYyDialect() {
        var mapper = new ObjectMapper();
        var properties = new MinmaxProperties();
        var signer = new MinmaxSigner(properties, mapper);
        var credential = new MinmaxCredential(
            "test-token", "test-user", "12345678", "test-uuid", "", "");
        var body = "{\"model\":{\"model_id\":\"MiniMax-M3\"}}";

        var control = signer.signControl(properties.getBaseUrl(),
            "/archon/api/v1/agent/test/session", "POST", body, credential);
        var stream = signer.signStream(properties.getStreamBaseUrl(),
            "/archon/api/v1/session/test/message", "POST", body, credential);

        assertOfficialSignature(control, body, false);
        assertOfficialSignature(stream, body, true);
        assertThat(control.url()).doesNotContain("op_ticket");
        assertThat(stream.url()).doesNotContain("op_ticket");
    }

    @Test
    void signsWithTheDeviceAndRotatingProfileCapturedDuringRegistration() {
        var mapper = new ObjectMapper();
        var requestProfile = mapper.createObjectNode()
            .put("signature_salt", "account-signature")
            .put("yy_salt", "acctyy")
            .put("version_code", "33333");
        var deviceProfile = mapper.createObjectNode()
            .put("timezone_offset", 28_800)
            .put("os_name", "Windows")
            .put("browser_name", "Chrome")
            .put("device_memory", 32)
            .put("cpu_core_num", 32)
            .put("browser_language", "zh-CN")
            .put("browser_platform", "Win32")
            .put("screen_width", 1707)
            .put("screen_height", 960);
        var properties = new MinmaxProperties();
        var credential = new MinmaxCredential(
            "test-token", "test-user", "12345678", "test-uuid", "", "",
            requestProfile, deviceProfile, "captured-user-agent");

        var signed = new MinmaxSigner(properties, mapper).signControl(
            properties.getBaseUrl(), "/archon/api/v1/agent?limit=20", "GET", "", credential);

        assertThat(signed.url())
            .contains("version_code=33333")
            .contains("timezone_offset=28800")
            .contains("device_memory=32")
            .contains("cpu_core_num=32")
            .contains("browser_language=zh-CN")
            .contains("screen_width=1707")
            .contains("screen_height=960");
        assertThat(signed.headers().get("user-agent")).isEqualTo("captured-user-agent");
        assertThat(signed.headers().get("x-signature")).isEqualTo(md5(
            signed.headers().get("x-timestamp") + "account-signature"));
    }

    @Test
    void mapsResponsesAndIgnoresUserEchoInOfficialEventStream() {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode().put("model", "minmax/MiniMax-M3");
        var input = mapper.createObjectNode().put("role", "user").put("content", "hello");
        var request = new CanonicalRequest("r4", CanonicalRequest.Protocol.RESPONSES,
            "minmax", "MiniMax-M3", true, List.of(input), Map.of(), Map.of("effort", "high"),
            List.of(), Map.of(), raw);

        var prepared = new MinmaxRequestMapper(mapper).prepare(request);
        var decoder = new MinmaxEventDecoder("r4");
        var events = new java.util.ArrayList<CanonicalEvent>();
        events.addAll(decoder.decode("{\"type\":2,\"agent_message\":{\"role\":\"user\",\"msg_content\":\"hello\"}}"));
        events.addAll(decoder.decode("{\"type\":6,\"agent_message_chunk\":{\"thinking_content\":\"why\"}}"));
        events.addAll(decoder.decode("{\"type\":2,\"agent_message\":{\"role\":\"assistant\",\"msg_content\":\"answer\"}}"));
        events.addAll(decoder.finish());

        assertThat(prepared.model().path("variant").asText()).isEqualTo("thinking");
        assertThat(prepared.sessionModel()).isEqualTo("minimax/MiniMax-M3");
        assertThat(prepared.enableTeam()).isTrue();
        assertThat(events.stream().filter(CanonicalEvent.OutputTextDelta.class::isInstance)
            .map(CanonicalEvent.OutputTextDelta.class::cast)
            .map(CanonicalEvent.OutputTextDelta::delta)).containsExactly("answer");
        assertThat(events).anyMatch(CanonicalEvent.ReasoningDelta.class::isInstance)
            .anyMatch(CanonicalEvent.Completed.class::isInstance);
    }

    @Test
    void preservesNativeImageInputForTheProviderUploadStage() {
        var mapper = new ObjectMapper();
        var content = mapper.createArrayNode()
            .add(mapper.createObjectNode().put("type", "input_text").put("text", "read it"))
            .add(mapper.createObjectNode().put("type", "input_image")
                .put("image_url", "data:image/png;base64,aGVsbG8="));
        var raw = mapper.createObjectNode().put("model", "minmax/MiniMax-M3");
        var input = mapper.createObjectNode().put("role", "user").set("content", content);
        var request = new CanonicalRequest("r-mm", CanonicalRequest.Protocol.RESPONSES,
            "minmax", "MiniMax-M3", false, List.of(input), Map.of(), Map.of(),
            List.of(), Map.of(), raw);

        var prepared = new MinmaxRequestMapper(mapper).prepare(request);

        assertThat(prepared.content()).contains("read it");
        assertThat(prepared.media()).hasSize(1);
        assertThat(prepared.media().getFirst().dataUrl())
            .startsWith("data:image/png;base64,");
    }

    @Test
    void rejectsAnEmptyOfficialEventStream() {
        var events = new MinmaxEventDecoder("empty").finish();

        assertThat(events).anyMatch(event -> event instanceof CanonicalEvent.Failed failed
            && failed.errorType().equals("empty_model_response"));
        assertThat(events).noneMatch(CanonicalEvent.Completed.class::isInstance);
    }

    private void assertOfficialSignature(
        MinmaxSignedRequest request,
        String body,
        boolean absoluteResource
    ) {
        var uri = URI.create(request.url());
        var actualResource = uri.getRawPath() + "?" + uri.getRawQuery();
        var signedResource = actualResource.replace(
            "&screen_width=", "&op_ticket=undefined&screen_width=");
        if (absoluteResource) {
            signedResource = uri.getScheme() + "://" + uri.getAuthority() + signedResource;
        }
        var unix = queryValue(uri.getRawQuery(), "unix");
        var expectedYy = md5(encodeURIComponent(signedResource) + "_"
            + new ObjectMapper().writeValueAsString(body) + md5(unix) + "ooui");
        var seconds = request.headers().get("x-timestamp");
        var expectedSignature = md5(seconds + "I*7Cf%WZ#S&%1RlZJ&C2" + body);

        assertThat(request.headers().get("yy")).isEqualTo(expectedYy);
        assertThat(request.headers().get("x-signature")).isEqualTo(expectedSignature);
    }

    private String queryValue(String query, String name) {
        for (var part : query.split("&")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        throw new IllegalArgumentException("missing query parameter " + name);
    }

    private String md5(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String encodeURIComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~");
    }
}
