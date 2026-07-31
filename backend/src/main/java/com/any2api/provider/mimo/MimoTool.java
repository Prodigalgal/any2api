package com.any2api.provider.mimo;

import tools.jackson.databind.JsonNode;

record MimoTool(String name, String description, JsonNode parameters) {
}
