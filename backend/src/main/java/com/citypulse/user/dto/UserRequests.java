package com.citypulse.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class UserRequests {

    private UserRequests() {
    }

    /**
     * @param roles the complete set of roles the user should hold afterwards.
     *              Replace rather than add/remove, so the result does not depend
     *              on the order concurrent requests arrive in.
     */
    public record AssignRoles(
            @NotEmpty(message = "At least one role is required")
            @Size(max = 7, message = "A user cannot hold more than 7 roles")
            List<@Pattern(regexp = "^[A-Z_]{3,32}$", message = "Role names must be uppercase identifiers")
                    String> roles
    ) {
    }

    public record SetStatus(
            @Pattern(regexp = "ACTIVE|SUSPENDED",
                    message = "Status must be ACTIVE or SUSPENDED")
            String status
    ) {
    }
}
