package com.any2api.provider;

import java.net.URI;
import tools.jackson.databind.node.ObjectNode;

public record PreparedProviderRequest(
    URI baseUrl,
    String apiKey,
    String upstreamPath,
    ObjectNode body
) {
}
