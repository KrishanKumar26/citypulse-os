package com.citypulse.apikey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    public record Create(
            @Schema(description = "What this key is for. Required — an unnamed key cannot be "
                    + "revoked with any confidence about what will break.")
            @NotBlank @Size(max = 120) String name,

            @Size(max = 500) String description,

            @Schema(description = "Permissions to grant. Must be a subset of your own: a key "
                    + "cannot carry authority its creator does not hold.")
            @NotEmpty Set<String> scopes,

            @Schema(description = "Days until it expires. Omit for a key that never expires — "
                    + "permitted, but the weaker choice.")
            Integer expiresInDays
    ) {
    }

    public record Revoke(
            @Size(max = 200) String reason
    ) {
    }

    /**
     * A key as it can safely be listed.
     *
     * <p>No secret, ever. The prefix identifies it without revealing it.
     */
    public record Summary(
            String id,
            String name,
            String description,

            @Schema(description = "Identifies the key without revealing it. Not a credential.")
            String keyPrefix,

            Set<String> scopes,
            String owner,

            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            String revokedReason,

            @Schema(description = "Null when the key has never been used — different from used "
                    + "long ago, and the pair is how a key nobody needs is found")
            Instant lastUsedAt,

            @Schema(description = "Usable right now: not revoked, not expired")
            boolean active,
            @Schema(description = "Why it is not usable, when it is not")
            String inactiveReason
    ) {
    }

    /**
     * The one response that carries the secret.
     *
     * <p>Returned only from creation, and never retrievable again — nothing
     * stores it. A key that could be read back would make the database as
     * sensitive as the keys themselves.
     */
    public record Created(
            Summary key,

            @Schema(description = "The only time this is ever returned. It is not stored and "
                    + "cannot be recovered; if it is lost, revoke the key and issue another.")
            String secret
    ) {
    }

    public record ScopeCatalogue(
            @Schema(description = "Permissions the caller holds and may therefore grant")
            List<String> grantable
    ) {
    }
}
