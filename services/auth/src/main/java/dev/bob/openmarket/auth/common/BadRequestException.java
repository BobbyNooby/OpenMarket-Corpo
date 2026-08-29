package dev.bob.openmarket.auth.common;

import org.springframework.http.HttpStatus;

/** 400 for well-formed requests that are semantically rejected. */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message, String field) {
        super(code, message, field);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
