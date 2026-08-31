package dev.bob.openmarket.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "Current password is required")
    String currentPassword,

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    String newPassword
) {
}
