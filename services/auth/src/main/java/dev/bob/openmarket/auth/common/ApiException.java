package dev.bob.openmarket.auth.common;

import org.springframework.http.HttpStatus;

/**
 * Base for service-level errors that map to the {@link ApiError} envelope.
 * Subclasses pick the HTTP status; the handler catches all of them in one place.
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final String field;

    protected ApiException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public abstract HttpStatus status();

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    public ApiError toApiError() {
        return new ApiError(code, getMessage(), field);
    }
}
