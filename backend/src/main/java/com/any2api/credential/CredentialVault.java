package com.any2api.credential;

import com.any2api.account.AccountEntity;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CredentialVault {

    private static final String CREDENTIAL_TYPE = "provider-session";
    private final AccountCredentialRepository credentials;
    private final ObjectMapper objectMapper;
    private final SecretCipher cipher;

    public CredentialVault(
        AccountCredentialRepository credentials,
        ObjectMapper objectMapper,
        SecretCipher cipher
    ) {
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
    }

    @Transactional
    public DecryptedCredential store(
        AccountEntity account,
        String expectedProviderId,
        JsonNode payload,
        Instant expiresAt
    ) {
        requireProvider(account, expectedProviderId);
        var entity = credentials.findByAccountIdAndCredentialType(account.getId(), CREDENTIAL_TYPE)
            .orElseGet(() -> AccountCredentialEntity.create(account.getId(), CREDENTIAL_TYPE));
        var nextVersion = entity.getCredentialVersion() + 1;
        var sealed = cipher.seal(objectMapper.writeValueAsBytes(payload), aad(account, nextVersion));
        entity.replace(sealed.encrypted(), sealed.nonce(), sealed.keyVersion(), expiresAt);
        credentials.save(entity);
        return new DecryptedCredential(CREDENTIAL_TYPE, nextVersion, expiresAt, payload.deepCopy());
    }

    @Transactional
    public DecryptedCredential storeIfVersion(
        AccountEntity account,
        String expectedProviderId,
        long expectedVersion,
        JsonNode payload,
        Instant expiresAt
    ) {
        requireProvider(account, expectedProviderId);
        var entity = credentials.findForUpdate(account.getId(), CREDENTIAL_TYPE)
            .orElseThrow(() -> new IllegalStateException("account has no provider credential"));
        if (entity.getCredentialVersion() != expectedVersion) {
            throw new IllegalStateException(
                "provider credential changed while the account command was running");
        }
        var nextVersion = entity.getCredentialVersion() + 1;
        var sealed = cipher.seal(objectMapper.writeValueAsBytes(payload), aad(account, nextVersion));
        entity.replace(sealed.encrypted(), sealed.nonce(), sealed.keyVersion(), expiresAt);
        credentials.save(entity);
        return new DecryptedCredential(CREDENTIAL_TYPE, nextVersion, expiresAt, payload.deepCopy());
    }

    @Transactional(readOnly = true)
    public DecryptedCredential read(AccountEntity account, String expectedProviderId) {
        requireProvider(account, expectedProviderId);
        var entity = credentials.findByAccountIdAndCredentialType(account.getId(), CREDENTIAL_TYPE)
            .orElseThrow(() -> new IllegalStateException("account has no provider credential"));
        var plaintext = cipher.open(entity.getEncryptedPayload(), entity.getNonce(),
            aad(account, entity.getCredentialVersion()));
        try {
            return new DecryptedCredential(
                CREDENTIAL_TYPE,
                entity.getCredentialVersion(),
                entity.getExpiresAt(),
                objectMapper.readTree(plaintext));
        } catch (Exception error) {
            throw new IllegalStateException("credential payload is not valid JSON", error);
        }
    }

    @Transactional(readOnly = true)
    public CredentialSummary summary(AccountEntity account, String expectedProviderId) {
        requireProvider(account, expectedProviderId);
        return credentials.findByAccountIdAndCredentialType(account.getId(), CREDENTIAL_TYPE)
            .map(entity -> new CredentialSummary(
                true,
                entity.getCredentialType(),
                entity.getCredentialVersion(),
                entity.getExpiresAt(),
                entity.getUpdatedAt()))
            .orElseGet(CredentialSummary::missing);
    }

    private String aad(AccountEntity account, long credentialVersion) {
        return (account.getProviderId() + ":" + account.getId() + ":" + CREDENTIAL_TYPE + ":"
            + credentialVersion);
    }

    private void requireProvider(AccountEntity account, String expectedProviderId) {
        if (!account.getProviderId().equals(expectedProviderId)) {
            throw new IllegalArgumentException("account does not belong to requested provider");
        }
    }

}
