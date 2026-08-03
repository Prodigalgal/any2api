package com.any2api.account;

import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class AccountSpecifications {
    private static final Duration EXPIRING_WINDOW = Duration.ofDays(7);

    private AccountSpecifications() {
    }

    static Specification<AccountEntity> matching(AccountSearchQuery query, Instant now) {
        return (root, criteria, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (query.providerId() != null) {
                predicates.add(builder.equal(root.get("providerId"), query.providerId()));
            }
            if (query.status() != null) {
                predicates.add(builder.equal(root.get("status"), query.status()));
            }
            if (query.enabled() != null) {
                predicates.add(builder.equal(root.get("enabled"), query.enabled()));
            }
            if (query.keyword() != null) {
                var pattern = "%" + escapeLike(query.keyword().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                    builder.like(builder.lower(root.get("externalId")), pattern, '\\'),
                    builder.like(builder.lower(builder.coalesce(root.get("email"), "")),
                        pattern, '\\'),
                    builder.like(builder.lower(builder.coalesce(root.get("lastError"), "")),
                        pattern, '\\')
                ));
            }
            var expiresAt = root.<Instant>get("expiresAt");
            switch (query.expiry()) {
                case VALID -> predicates.add(builder.or(
                    builder.isNull(expiresAt), builder.greaterThan(expiresAt, now)));
                case EXPIRING_SOON -> predicates.add(builder.and(
                    builder.greaterThan(expiresAt, now),
                    builder.lessThanOrEqualTo(expiresAt, now.plus(EXPIRING_WINDOW))));
                case EXPIRED -> predicates.add(builder.lessThanOrEqualTo(expiresAt, now));
                case NEVER -> predicates.add(builder.isNull(expiresAt));
                case ANY -> {
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
