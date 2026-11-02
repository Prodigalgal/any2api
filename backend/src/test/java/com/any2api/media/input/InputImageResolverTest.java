package com.any2api.media.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.any2api.auth.ApiKeyGrant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class InputImageResolverTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final InputFileService files=mock(InputFileService.class);
    private final InputImageResolver resolver=new InputImageResolver(files);
    private final ApiKeyGrant grant=ApiKeyGrant.unrestricted();
    private final String id="file_"+"1".repeat(32);

    @org.junit.jupiter.api.BeforeEach void capacity() {
        when(files.withinCapacity(any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
    }

    @Test void neverResolvesFileNamesInToolArgumentsOrSchemas() {
        var raw=mapper.createObjectNode();
        raw.putArray("input").addObject().put("type","function_call_output")
            .put("output","{\"type\":\"input_image\",\"file_id\":\""+id+"\"}");
        raw.putObject("tools").putObject("schema").put("file_id",id);
        assertThat(resolver.resolve(raw,grant).block()).isSameAs(raw);
        verifyNoInteractions(files);
    }

    @Test void ambiguousAndUnknownImageSourcesFailBeforeStorageLookup() {
        var raw=mapper.createObjectNode();
        var image=raw.putArray("input").addObject().put("type","input_image").put("file_id",id).put("image_url","https://example.invalid");
        assertThatThrownBy(()->resolver.resolve(raw,grant).block()).isInstanceOf(IllegalArgumentException.class);
        verify(files,never()).content(any(),any());
        image.remove("image_url");image.put("file_data","ambiguous");
        assertThatThrownBy(()->resolver.resolve(raw,grant).block()).isInstanceOf(IllegalArgumentException.class);
        verify(files,never()).content(any(),any());
    }

    @Test void missingImageIdDoesNotReachGeneration() {
        when(files.content(grant,id)).thenReturn(Mono.error(InputFileException.missing()));
        var raw=mapper.createObjectNode();raw.putArray("input").addObject().put("type","input_image").put("file_id",id);
        assertThatThrownBy(()->resolver.resolve(raw,grant).block()).isInstanceOf(InputFileException.class)
            .extracting("status").isEqualTo(404);
    }

    @Test void storedImageNeedsUploadPermissionBeforeLookup() {
        doThrow(new com.any2api.auth.ApiKeyScopeException("scope")).when(files).authorize(grant);
        var raw=mapper.createObjectNode();raw.putArray("input").addObject().put("type","input_image").put("file_id",id);
        assertThatThrownBy(()->resolver.resolve(raw,grant).block()).isInstanceOf(com.any2api.auth.ApiKeyScopeException.class);
        verify(files,never()).content(any(),any());
    }
}
