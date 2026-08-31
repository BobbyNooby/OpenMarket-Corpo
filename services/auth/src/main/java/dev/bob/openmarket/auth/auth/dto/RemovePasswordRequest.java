package dev.bob.openmarket.auth.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RemovePasswordRequest(
    @NotBlank(message = "Current password is required")
    String currentPassword
) {
}
