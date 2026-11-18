package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * ポリシーまたはエンベロープ検証失敗理由を型付けして運ぶ例外。
 * メッセージ文字列照合に依存した判定を排除する。
 */
final class PolicySecurityException extends SecurityException {
    private final PolicyFailureReason reason;

    PolicySecurityException(PolicyFailureReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    PolicySecurityException(PolicyFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    PolicyFailureReason reason() {
        return reason;
    }
}
