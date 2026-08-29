package dev.bob.openmarket.auth.common;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String code, String message, String field) {
        super(code, message, field);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
