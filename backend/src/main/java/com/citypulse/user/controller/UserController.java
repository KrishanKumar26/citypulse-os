package com.citypulse.user.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import com.citypulse.user.dto.UserRequests;
import com.citypulse.user.dto.UserSummaryResponse;
import com.citypulse.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Validated
@Tag(name = "Users", description = "User and role administration")
public class UserController {

    /** Bounded so a caller cannot request an unbounded result set (PRD §44). */
    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users", description = "Requires the user:read permission.")
    public ResponseEntity<ApiResponse<PageResponse<UserSummaryResponse>>> list(
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(userService.list(search, pageable)));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user", description = "Requires the user:read permission.")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(userService.get(userId)));
    }

    @PutMapping("/{userId}/roles")
    @Operation(summary = "Replace a user's roles",
            description = "Requires user:manage_roles. Revokes the user's sessions so the new "
                          + "permissions take effect on their next sign-in.")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> assignRoles(
            @PathVariable UUID userId,
            @Valid @RequestBody UserRequests.AssignRoles request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.assignRoles(userId, request), "Roles updated"));
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Activate or suspend a user",
            description = "Requires user:write. Suspending revokes the user's sessions immediately.")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> setStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UserRequests.SetStatus request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setStatus(userId, request), "Status updated"));
    }
}
