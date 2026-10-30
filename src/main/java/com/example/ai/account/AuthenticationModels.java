package com.example.ai.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthenticationModels {
    private AuthenticationModels() { }

    public record LoginRequest(@NotBlank @Size(min = 3, max = 64) String username,
                               @NotBlank @Size(min = 4, max = 72)
                               @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 4, max = 72) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String currentPassword,
            @NotBlank @Size(min = 4, max = 72) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }

    public record CurrentAccount(String id, String username, String role, boolean administrator,
                                 boolean passwordChangeRequired) { }

    /** 仅在认证服务与 HTTP 会话边界之间传递，不直接作为响应。 */
    public record LoginResult(AccountSession session, CurrentAccount account) { }

    public record CsrfResponse(String headerName, String token) { }
}
