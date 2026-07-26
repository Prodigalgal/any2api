package com.any2api.coordination;

import java.util.UUID;

public class AccountCapacityException extends RuntimeException {

    public AccountCapacityException(String providerId, UUID accountId) {
        super("account capacity exhausted for " + providerId + "/" + accountId);
    }
}

