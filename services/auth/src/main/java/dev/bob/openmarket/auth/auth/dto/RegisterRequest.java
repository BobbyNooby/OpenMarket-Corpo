package dev.bob.openmarket.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Register with email + password. `username` is optional — derived from the
 * name when omitted. Discord signup joins the same account later (Phase C).
 */
public record RegisterRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    String password,

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    String name,

    @Size(min = 3, max = 32, message = "Username must be 3-32 characters")
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "Username may only contain a-z, 0-9, _ and -")
    String username
) {
}
