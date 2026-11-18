package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * hold 解除 proof を拒否したことを示す（NF07-REL）。理由は固定 code（{@link HoldReleaseRejection}）だけで、message に
 * 設定値・識別子を入れない。{@link SecurityException} を継承し、Journal adapter は ACK 不明として扱わずにそのまま返す。
 */
public final class HoldReleaseRejectedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    private final HoldReleaseRejection reason;

    public HoldReleaseRejectedException(HoldReleaseRejection reason) {
        super("hold解除proofを拒否しました: " + Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public HoldReleaseRejection reason() {
        return reason;
    }
}
