package com.any2api.account;

public record AccountSearchQuery(
    String providerId,
    AccountStatus status,
    Boolean enabled,
    String keyword,
    Expiry expiry,
    int page,
    int size
) {
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public AccountSearchQuery {
        providerId = normalized(providerId);
        keyword = normalized(keyword);
        expiry = expiry == null ? Expiry.ANY : expiry;
        if (keyword != null && keyword.length() > 120) {
            throw new IllegalArgumentException("account search keyword must not exceed 120 characters");
        }
        if (page < 0) {
            throw new IllegalArgumentException("account page must be zero or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("account page size must be between 1 and " + MAX_SIZE);
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public enum Expiry {
        ANY,
        VALID,
        EXPIRING_SOON,
        EXPIRED,
        NEVER
    }
}
