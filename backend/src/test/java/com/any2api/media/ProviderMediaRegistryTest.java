package com.any2api.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderMediaRegistryTest {
    @Test
    void providerValidationRunsBeforeTheHandlerCanBeUsed() {
        var handler = mock(ProviderMediaHandler.class);
        var request = new MediaRequest(
            "request", "acme", "image", MediaOperation.IMAGE_GENERATION,
            "prompt", 1, MediaRequest.ResponseFormat.URL, Map.of(),
            new ObjectMapper().createObjectNode());
        when(handler.providerId()).thenReturn("acme");
        when(handler.supports(request)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unsupported option"))
            .when(handler).validate(request);

        assertThatThrownBy(() -> new ProviderMediaRegistry(List.of(handler)).require(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unsupported option");

        verify(handler).validate(request);
        verify(handler, never()).generate(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
