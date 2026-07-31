package com.any2api.media;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public final class MediaAssetService {
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private final MediaAssetStore store;
    private final ExecutorService databaseExecutor;

    public MediaAssetService(MediaAssetStore store, ExecutorService databaseExecutor) {
        this.store = store;
        this.databaseExecutor = databaseExecutor;
    }

    public Mono<UUID> save(
        String providerId,
        UUID accountId,
        GeneratedMedia media
    ) {
        return Mono.fromCallable(() -> store.save(
                providerId, accountId, media.contentType(), media.content(), DEFAULT_TTL))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Mono<Optional<MediaAssetStore.StoredMediaAsset>> find(UUID id) {
        return Mono.fromCallable(() -> store.find(id))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }
}
