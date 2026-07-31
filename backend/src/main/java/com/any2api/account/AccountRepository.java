package com.any2api.account;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    long countByStatus(AccountStatus status);

    Optional<AccountEntity> findByProviderIdAndExternalId(String providerId, String externalId);

    List<AccountEntity> findAllByProviderIdOrderByCreatedAtDesc(String providerId);

    boolean existsByProviderIdAndEnabledTrue(String providerId);

    @Query("""
        SELECT account FROM AccountEntity account
        WHERE account.providerId = :providerId
          AND account.enabled = true
          AND account.status IN :statuses
          AND (account.cooldownUntil IS NULL OR account.cooldownUntil <= :now)
          AND (account.expiresAt IS NULL OR account.expiresAt > :now)
        ORDER BY account.priority DESC, account.lastUsedAt ASC NULLS FIRST, account.id ASC
        """)
    List<AccountEntity> findEligible(
        @Param("providerId") String providerId,
        @Param("statuses") List<AccountStatus> statuses,
        @Param("now") Instant now
    );

    @Modifying
    @Transactional
    @Query("""
        UPDATE AccountEntity account
        SET account.requestCount = account.requestCount + 1,
            account.lastUsedAt = :now
        WHERE account.id = :accountId
        """)
    int markUsed(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE AccountEntity account
        SET account.successCount = account.successCount + 1,
            account.failureCount = 0,
            account.lastSuccessAt = :now,
            account.lastError = NULL,
            account.cooldownUntil = NULL,
            account.status = com.any2api.account.AccountStatus.ACTIVE
        WHERE account.id = :accountId
        """)
    int markSuccess(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE AccountEntity account
        SET account.failureCount = account.failureCount + 1,
            account.lastFailureAt = :now,
            account.lastError = :error,
            account.cooldownUntil = :cooldownUntil,
            account.status = com.any2api.account.AccountStatus.DEGRADED
        WHERE account.id = :accountId
        """)
    int markFailure(
        @Param("accountId") UUID accountId,
        @Param("now") Instant now,
        @Param("error") String error,
        @Param("cooldownUntil") Instant cooldownUntil
    );

    @Modifying
    @Transactional
    @Query("""
        UPDATE AccountEntity account
        SET account.failureCount = account.failureCount + 1,
            account.lastFailureAt = :now,
            account.lastError = :error,
            account.cooldownUntil = :cooldownUntil,
            account.status = com.any2api.account.AccountStatus.PENDING,
            account.enabled = false
        WHERE account.id = :accountId
        """)
    int markReadinessFailure(
        @Param("accountId") UUID accountId,
        @Param("now") Instant now,
        @Param("error") String error,
        @Param("cooldownUntil") Instant cooldownUntil
    );
}
