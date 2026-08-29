package dev.bob.openmarket.auth.common;

/**
 * Standard error envelope every error response uses.
 * Shape: {"code": "email_taken", "message": "...", "field": "email"}
 * `field` is only set for validation-style errors and may be omitted.
 */
public record ApiError(String code, String message, String field) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError ofField(String code, String message, String field) {
        return new ApiError(code, message, field);
    }
}
