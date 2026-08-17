package com.any2api.lifecycle;

import com.any2api.account.LeasedProviderAccount;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AccountRecoveryPolicy {
    Optional<RecoveryTarget> resolve(LeasedProviderAccount failedAccount);

    record RecoveryTarget(
        UUID accountId,
        String providerId,
        Map<String, Object> metadataPatch
    ) {
        public RecoveryTarget {
            metadataPatch = metadataPatch == null ? Map.of() : Map.copyOf(metadataPatch);
        }
    }
}
