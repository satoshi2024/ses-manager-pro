package com.ses.service.repair;

/**
 * プロトコル標識行の破損・未知バージョン・必須項目欠落検知時に送出される例外。
 */
public class CorruptedEvidenceException extends RuntimeException {

    public CorruptedEvidenceException(String message) {
        super(message);
    }

    public CorruptedEvidenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
