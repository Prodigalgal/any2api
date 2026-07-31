package com.any2api.account;

public class AccountUnavailableException extends RuntimeException {

    public AccountUnavailableException(String providerId) {
        super("no eligible account for provider " + providerId);
    }
}
