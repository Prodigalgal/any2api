package com.any2api.account;

public class DuplicateAccountException extends RuntimeException {

    public DuplicateAccountException(String providerId, String externalId) {
        super("account already exists for provider " + providerId
            + " and external identity " + externalId);
    }
}
