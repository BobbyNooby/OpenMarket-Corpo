package dev.bob.openmarket.auth.common;

import org.springframework.http.HttpStatus;

/** 403 for identity-level refusals (banned account, missing role). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message) {
        super(code, message, null);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }
}
