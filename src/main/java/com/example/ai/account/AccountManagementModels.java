package com.example.ai.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AccountManagementModels {
    private AccountManagementModels() { }

    public record CreateAccountRequest(
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
            @NotBlank @Size(min = 4, max = 72) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @NotBlank @Pattern(regexp = "buyer|service|warehouse") String role) {
        @Override
        public String toString() {
            return "CreateAccountRequest[username=" + username + ", role=" + role + "]";
        }
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 4, max = 72) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[redacted]";
        }
    }

    public record AccountStatusRequest(@NotNull Boolean enabled) { }

    public record AccountSummary(String id, String username, String role, boolean administrator, boolean enabled,
                                 boolean passwordChangeRequired, long createdAt, long updatedAt) { }

    public record AccountPage(List<AccountSummary> items, int page, int size, boolean hasMore) { }
}
