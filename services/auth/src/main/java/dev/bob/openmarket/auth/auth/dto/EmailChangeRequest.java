package dev.bob.openmarket.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailChangeRequest(
    @NotBlank(message = "New email is required")
    @Email(message = "Email must be a valid address")
    String newEmail
) {
}
