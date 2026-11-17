package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * 内部判定 port（{@link PolicyEnforcementPort}）の結果。可否と固定の理由コードだけを持ち、policy の metadata・説明文・
 * revision・digest を持たない（説明は privacy 管理者向けの {@link PolicyReadonlyQueryPort} だけが返す）。
 *
 * @param permitted 判定の全ての前提を満たしたか
 * @param reason    結果コード（許可なら {@link PolicyEnforcementReason#PERMITTED}）
 * @param detail    policy 検証の細かな原因（policy の検証に進まなかった拒否・許可では {@link PolicyFailureReason#NONE}）
 */
public record PolicyEnforcementDecision(boolean permitted, PolicyEnforcementReason reason, PolicyFailureReason detail) {

    private static final PolicyEnforcementDecision PERMIT =
            new PolicyEnforcementDecision(true, PolicyEnforcementReason.PERMITTED, PolicyFailureReason.NONE);

    public PolicyEnforcementDecision {
        Objects.requireNonNull(reason, "reasonが必要です");
        Objects.requireNonNull(detail, "detailが必要です");
        if (permitted != (reason == PolicyEnforcementReason.PERMITTED)) {
            throw new IllegalArgumentException("判定の可否と結果コードが一致しません");
        }
        if (permitted && detail != PolicyFailureReason.NONE) {
            throw new IllegalArgumentException("許可の判定に失敗原因は付けられません");
        }
    }

    /** 全ての前提を満たした判定。 */
    public static PolicyEnforcementDecision permit() {
        return PERMIT;
    }

    /** 拒否の判定。 */
    public static PolicyEnforcementDecision deny(PolicyEnforcementReason reason, PolicyFailureReason detail) {
        return new PolicyEnforcementDecision(false, reason, detail == null ? PolicyFailureReason.NONE : detail);
    }
}
