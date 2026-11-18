package com.ses.service.repair;

/**
 * 部材レコードの関連付け矛盾検知時に送出される例外。
 */
public class LinkageConflictException extends RuntimeException {

    public LinkageConflictException(String message) {
        super(message);
    }

    public LinkageConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
