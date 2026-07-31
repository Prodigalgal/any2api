package com.any2api.provider.minmax;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Component
final class MinmaxSigner {
    private final MinmaxProperties properties;
    private final ObjectMapper mapper;

    MinmaxSigner(MinmaxProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    MinmaxSignedRequest signControl(
        String baseUrl,
        String path,
        String method,
        String body,
        MinmaxCredential credential
    ) {
        return sign(baseUrl, path, method, body, credential, false);
    }

    MinmaxSignedRequest signStream(
        String baseUrl,
        String path,
        String method,
        String body,
        MinmaxCredential credential
    ) {
        return sign(baseUrl, path, method, body, credential, true);
    }

    private MinmaxSignedRequest sign(
        String baseUrl,
        String path,
        String method,
        String body,
        MinmaxCredential credential,
        boolean absoluteYyPath
    ) {
        var unix = System.currentTimeMillis() / 1000 * 1000;
        var profile = credential.effectiveProfile(properties.requestProfile());
        var query = new LinkedHashMap<String, Object>();
        query.put("device_platform", profile.devicePlatform());
        query.put("biz_id", profile.bizId());
        query.put("app_id", profile.appId());
        query.put("version_code", profile.versionCode());
        query.put("unix", unix);
        query.put("timezone_offset", credential.timezoneOffset(ZoneId.systemDefault().getRules()
            .getOffset(java.time.Instant.now()).getTotalSeconds()));
        query.put("sys_language", profile.language());
        query.put("lang", profile.language());
        query.put("uuid", credential.uuid().isBlank() ? null : credential.uuid());
        query.put("device_id", credential.deviceId());
        query.put("os_name", profile.osName());
        query.put("browser_name", profile.browserName());
        query.put("device_memory", profile.deviceMemory());
        query.put("cpu_core_num", profile.cpuCoreCount());
        query.put("browser_language", profile.browserLanguage());
        query.put("browser_platform", profile.browserPlatform());
        query.put("user_id", credential.userId());
        query.put("op_ticket", credential.opTicket().isBlank() ? null : credential.opTicket());
        query.put("screen_width", profile.screenWidth());
        query.put("screen_height", profile.screenHeight());
        query.put("token", credential.token());
        query.put("client", profile.client());
        query.put("region", profile.region());
        var actualBuilder = UriComponentsBuilder.fromUriString(path);
        query.forEach((key, value) -> {
            if (value != null) actualBuilder.queryParam(key, value);
        });
        var actualPath = actualBuilder.build().encode(StandardCharsets.UTF_8).toUriString();

        var signedBuilder = UriComponentsBuilder.fromUriString(path);
        query.forEach((key, value) -> signedBuilder.queryParam(key,
            value != null ? value : "op_ticket".equals(key) ? "undefined" : "null"));
        var signedPath = signedBuilder.build().encode(StandardCharsets.UTF_8).toUriString();
        var url = baseUrl + actualPath;
        var yyBody = "POST".equalsIgnoreCase(method) && body != null && !body.isBlank()
            ? mapper.writeValueAsString(body) : "{}";
        var yyResource = absoluteYyPath ? baseUrl + signedPath : signedPath;
        var yy = md5(encodeURIComponent(yyResource) + "_" + yyBody
            + md5(Long.toString(unix)) + profile.yySalt());
        var seconds = unix / 1000;
        var signature = md5(seconds + profile.signatureSalt()
            + (body == null ? "" : body));
        return new MinmaxSignedRequest(url, Map.of(
            "token", credential.token(),
            "yy", yy,
            "x-timestamp", Long.toString(seconds),
            "x-signature", signature,
            "origin", properties.getBaseUrl(),
            "referer", properties.getBaseUrl() + "/",
            "user-agent", credential.effectiveUserAgent(properties.getUserAgent())));
    }

    private String md5(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("MD5 unavailable", error);
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
