package com.any2api.credential;

import com.any2api.config.Any2ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class SecretCipher {
    private static final int KEY_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] masterKey;

    public SecretCipher(Any2ApiProperties properties) {
        masterKey = decodeKey(properties.getSecurity().getCredentialMasterKey());
    }

    public SealedSecret seal(byte[] plaintext, String aad) {
        requireKey();
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new SealedSecret(
            crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, aad), nonce, KEY_VERSION);
    }

    public byte[] open(byte[] encrypted, byte[] nonce, String aad) {
        requireKey();
        return crypt(Cipher.DECRYPT_MODE, encrypted, nonce, aad);
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce, String aad) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(masterKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("secret encryption failed", error);
        }
    }

    private void requireKey() {
        if (masterKey.length != 32) {
            throw new IllegalStateException("ANY2API_CREDENTIAL_MASTER_KEY must decode to 32 bytes");
        }
    }

    private static byte[] decodeKey(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("credential master key is not Base64", error);
            }
        }
    }

    public record SealedSecret(byte[] encrypted, byte[] nonce, int keyVersion) {
        public SealedSecret {
            encrypted = encrypted.clone();
            nonce = nonce.clone();
        }
    }
}
