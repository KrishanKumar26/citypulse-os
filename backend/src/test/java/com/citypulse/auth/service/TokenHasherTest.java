package com.citypulse.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token generation and hashing (docs/SECURITY.md §2).
 */
@DisplayName("TokenHasher")
class TokenHasherTest {

    private final TokenHasher hasher = new TokenHasher();

    @Test
    @DisplayName("generates tokens with at least 256 bits of entropy")
    void generatesLongTokens() {
        String token = hasher.generateToken();

        // 32 random bytes, URL-safe Base64 without padding.
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("never repeats a token")
    void tokensAreUnique() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(hasher.generateToken());
        }
        assertThat(generated).hasSize(10_000);
    }

    @Test
    @DisplayName("hashes to 64 lowercase hex characters, matching the storage column")
    void hashFormatMatchesColumn() {
        String hash = hasher.hash("some-token-value");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("hashing is deterministic, so a presented token can be looked up")
    void hashIsDeterministic() {
        String token = hasher.generateToken();
        assertThat(hasher.hash(token)).isEqualTo(hasher.hash(token));
    }

    @Test
    @DisplayName("the hash differs from the token, so a database dump yields no usable credential")
    void hashIsNotThePlainToken() {
        String token = hasher.generateToken();
        assertThat(hasher.hash(token)).isNotEqualTo(token);
    }

    @Test
    @DisplayName("distinct tokens hash distinctly")
    void differentTokensDifferentHashes() {
        assertThat(hasher.hash(hasher.generateToken())).isNotEqualTo(hasher.hash(hasher.generateToken()));
    }

    @Test
    @DisplayName("matches the known SHA-256 digest of a fixed input")
    void matchesKnownDigest() {
        // Guards against an accidental algorithm change; this is the published
        // SHA-256 of "abc".
        assertThat(hasher.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
