package com.citypulse.apikey.controller;

import com.citypulse.apikey.dto.ApiKeyDtos;
import com.citypulse.apikey.service.ApiKeyService;
import com.citypulse.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API keys for programmatic access (PRD §22).
 *
 * <p>No permission is required beyond being authenticated, and that is
 * deliberate: a key can only ever carry permissions its creator already holds,
 * so gating key creation behind an extra permission would restrict nothing an
 * attacker could not already do directly. The scope check in the service is the
 * control that matters.
 *
 * <p>Every endpoint here operates on the caller's own keys. There is no
 * administrator view of other people's keys, because nothing in the product
 * needs one and the ability to list another user's credentials is worth more to
 * an attacker than to an operator.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API Keys", description = "Credentials for programmatic access")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Your API keys",
            description = "Never includes the secret — it is not stored and cannot be shown "
                    + "again. The prefix identifies a key without revealing it.")
    public ResponseEntity<ApiResponse<List<ApiKeyDtos.Summary>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listMine()));
    }

    @GetMapping("/scopes")
    @Operation(summary = "Permissions you may grant to a key",
            description = "Exactly the permissions you hold. A key cannot carry authority its "
                    + "creator does not have.")
    public ResponseEntity<ApiResponse<ApiKeyDtos.ScopeCatalogue>> scopes() {
        return ResponseEntity.ok(ApiResponse.ok(service.grantableScopes()));
    }

    @PostMapping
    @Operation(summary = "Issue a key",
            description = "The response carries the secret. It is the only time: nothing stores "
                    + "it and no endpoint can return it again. If it is lost, revoke the key and "
                    + "issue another.")
    public ResponseEntity<ApiResponse<ApiKeyDtos.Created>> create(
            @Valid @RequestBody ApiKeyDtos.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(request)));
    }

    @DeleteMapping("/{keyId}")
    @Operation(summary = "Revoke a key",
            description = "Immediate and final. The row is kept rather than deleted, so a key "
                    + "that acted stays explicable afterwards.")
    public ResponseEntity<ApiResponse<ApiKeyDtos.Summary>> revoke(
            @PathVariable UUID keyId,
            @RequestBody(required = false) ApiKeyDtos.Revoke request) {
        return ResponseEntity.ok(ApiResponse.ok(service.revoke(keyId, request)));
    }
}
