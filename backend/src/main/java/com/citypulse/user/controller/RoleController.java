package com.citypulse.user.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.user.dto.RoleResponse;
import com.citypulse.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Roles live at their own path rather than under {@code /users}, so the literal
 * segment can never be ambiguous with {@code /users/{userId}}.
 */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Role definitions and their permissions")
public class RoleController {

    private final UserService userService;

    public RoleController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List roles and their permissions",
            description = "Requires the role:read permission.")
    public ResponseEntity<ApiResponse<List<RoleResponse>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.ok(userService.listRoles()));
    }
}
