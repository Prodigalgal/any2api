package com.any2api.media.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.any2api.auth.ApiKeyAuthorization;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class InputFileCapacityTest {
    @Test void cancellationAndFailuresReleaseTheGlobalMemoryPermit() {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var files=new InputFileService(mock(InputFileStore.class),new ApiKeyAuthorization(),executor);
            var held=new ArrayList<Disposable>();
            try {
                for(int i=0;i<8;i++)held.add(files.withinCapacity(Mono::never).subscribe());
                assertThatThrownBy(()->files.withinCapacity(()->Mono.just(true)).block()).isInstanceOf(InputFileException.class)
                    .extracting("code").isEqualTo("file_operation_busy");
                held.removeFirst().dispose();
                assertThat(files.withinCapacity(()->Mono.just(true)).block()).isTrue();
                assertThatThrownBy(()->files.withinCapacity(()-> {throw new IllegalStateException("expected");}).block())
                    .isInstanceOf(IllegalStateException.class);
                assertThat(files.withinCapacity(()->Mono.just(true)).block()).isTrue();
            }finally{held.forEach(Disposable::dispose);}
        }
    }
}
