package com.any2api.media;

import com.any2api.account.LeasedProviderAccount;
import com.any2api.provider.ProviderAccountProfile;
import com.any2api.provider.ProviderFailure;
import reactor.core.publisher.Mono;

public interface ProviderMediaHandler {
    String providerId();

    boolean supports(MediaRequest request);

    default void validate(MediaRequest request) {}

    default boolean supportsAccount(MediaRequest request, ProviderAccountProfile account) {
        return true;
    }

    Mono<MediaResult> generate(MediaRequest request, LeasedProviderAccount account);

    ProviderFailure classify(Throwable error);
}
