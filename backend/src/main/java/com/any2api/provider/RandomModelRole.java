package com.any2api.provider;

public enum RandomModelRole {
    TOP_TEXT("top_text"),
    TOP_MULTIMODAL("top_multimodal");

    private final String catalogValue;

    RandomModelRole(String catalogValue) {
        this.catalogValue = catalogValue;
    }

    public String catalogValue() {
        return catalogValue;
    }
}
