package com.ses.config.integrationhub;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 公開APIの全拒否理由をstable codeへ収束させる例外。秘密値を保持しない。 */
@Getter
public class ExternalApiSecurityException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String decision;
    private final boolean retryable;

    public ExternalApiSecurityException(HttpStatus status, String code, String decision) {
        this(status, code, decision, false);
    }

    public ExternalApiSecurityException(HttpStatus status, String code, String decision, boolean retryable) {
        super(code);
        this.status = status;
        this.code = code;
        this.decision = decision;
        this.retryable = retryable;
    }

    public static ExternalApiSecurityException authentication(String decision) {
        return new ExternalApiSecurityException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", decision);
    }

    public static ExternalApiSecurityException forbidden(String decision) {
        return new ExternalApiSecurityException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", decision);
    }

    public static ExternalApiSecurityException notFound(String decision) {
        return new ExternalApiSecurityException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", decision);
    }

    public static ExternalApiSecurityException invalid(String decision) {
        return new ExternalApiSecurityException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", decision);
    }
}
