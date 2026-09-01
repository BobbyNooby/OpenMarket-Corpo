package dev.bob.openmarket.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 254, message = "Email must be at most 254 characters")
    String email
) {
}
