package com.ses.privacy.foundation;

/** durable ACKを確認できない場合のfail-closed結果。 */
public final class JournalUnavailableException extends RuntimeException {
    public JournalUnavailableException(String message, Throwable cause) { super(message, cause); }
}
