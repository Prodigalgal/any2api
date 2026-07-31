package com.any2api.credential;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountCredentialRepository extends JpaRepository<AccountCredentialEntity, UUID> {

    Optional<AccountCredentialEntity> findByAccountIdAndCredentialType(
        UUID accountId,
        String credentialType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select credential from AccountCredentialEntity credential
        where credential.accountId = :accountId and credential.credentialType = :credentialType
        """)
    Optional<AccountCredentialEntity> findForUpdate(
        @Param("accountId") UUID accountId,
        @Param("credentialType") String credentialType
    );
}
