package dev.bob.openmarket.auth.auth.dto;

import dev.bob.openmarket.auth.common.PasswordBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank(message = "Token is required")
    String token,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    @PasswordBytes
    String newPassword
) {
}
