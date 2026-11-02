package com.any2api.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.media.input.InputFileException;
import com.any2api.media.input.InputFileService;
import com.any2api.media.input.InputFileStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class InputFilesControllerTest {
    @Test void partialUploadTimesOutAndReleasesItsMemoryPermit() {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var auth=new ApiKeyAuthorization();
            var files=new InputFileService(mock(InputFileStore.class),auth,executor);
            var controller=new InputFilesController(files,auth);
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/v1/files").build());
            exchange.getAttributes().put(ApiKeyAuthorization.GRANT_ATTRIBUTE,ApiKeyGrant.unrestricted());
            StepVerifier.withVirtualTime(()->controller.upload(Flux.never(),exchange))
                .thenAwait(Duration.ofSeconds(361))
                .expectErrorSatisfies(error->{assertThat(error).isInstanceOf(InputFileException.class);
                    assertThat(((InputFileException)error).status()).isEqualTo(408);}).verify();
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }
}
