package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * policy envelope の登録・有効化の検証で拒否した（NF07-REG）。理由は固定の {@link PolicyFailureReason} だけを持ち、
 * 例外の本文・envelope の値・鍵の情報を含めない（監査と応答には理由 code だけを出す）。
 */
public final class PolicyRegistrationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final PolicyFailureReason reason;

    PolicyRegistrationRejectedException(PolicyFailureReason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, false);
        this.reason = reason;
    }

    /** 拒否の理由（固定 code）。 */
    public PolicyFailureReason reason() {
        return reason;
    }
}
