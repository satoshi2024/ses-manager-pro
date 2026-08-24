package com.ses.common.security;

/**
 * 外向きHTTPリクエストの宛先URLがセキュリティ検証に失敗したことを表す例外。
 * <p>
 * SSRF（Server-Side Request Forgery）対策の一環として、
 * {@link OutboundUrlGuard} が安全でない宛先を検出した際に送出する。
 * メッセージには宛先ホスト等の識別に必要な最小限の情報のみを含め、
 * 解決済みIPアドレスの全列挙や認証情報などの機微情報は含めない。
 */
public class OutboundUrlException extends RuntimeException {

    public OutboundUrlException(String message) {
        super(message);
    }

    public OutboundUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
