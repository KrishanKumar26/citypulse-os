package com.citypulse.auth.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes the opaque secrets used for refresh and password-reset
 * tokens.
 *
 * <p>SHA-256, not BCrypt. These tokens are 256 bits of output from a CSPRNG, so
 * they have no guessable structure and there is nothing for a slow hash to
 * defend against — while BCrypt's cost would be paid on every token refresh. The
 * property that matters is that the stored value is not the value the client
 * presents, and SHA-256 provides that (docs/SECURITY.md §2).
 */
@Component
public class TokenHasher {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** URL-safe, unpadded Base64 of 256 random bits. */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256, matching the {@code CHAR(64)} storage column. */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; absence means a broken runtime.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
