package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * 内部判定 port（{@link PolicyEnforcementPort}）の結果。可否と固定の理由コードだけを持ち、policy の metadata・説明文・
 * revision・digest を持たない（説明は privacy 管理者向けの {@link PolicyReadonlyQueryPort} だけが返す）。
 *
 * <p>許可の判定は送信を許す根拠になるため、作れるのは foundation の中だけにする（{@link #permit()} は package-private で、
 * public な constructor を持たない。呼出し元は {@code PolicyEnforcementCallSiteTest} が {@link ProductionPolicyEnforcementService}
 * に固定する。RB-NF08-LINK review P3-1）。拒否（{@link #deny}）はどこからでも作れるが、{@link PolicyEnforcementReason#PERMITTED}
 * を渡すと拒否する（拒否の入口から許可を作れない）。
 */
public final class PolicyEnforcementDecision {

    private static final PolicyEnforcementDecision PERMIT =
            new PolicyEnforcementDecision(true, PolicyEnforcementReason.PERMITTED, PolicyFailureReason.NONE);

    private final boolean permitted;
    private final PolicyEnforcementReason reason;
    private final PolicyFailureReason detail;

    private PolicyEnforcementDecision(boolean permitted, PolicyEnforcementReason reason, PolicyFailureReason detail) {
        this.permitted = permitted;
        this.reason = Objects.requireNonNull(reason, "reasonが必要です");
        this.detail = Objects.requireNonNull(detail, "detailが必要です");
    }

    /** 全ての前提を満たした判定。foundation の中（本番の内部判定）だけが作る。 */
    static PolicyEnforcementDecision permit() {
        return PERMIT;
    }

    /** 拒否の判定。{@code reason} に {@link PolicyEnforcementReason#PERMITTED} は渡せない。 */
    public static PolicyEnforcementDecision deny(PolicyEnforcementReason reason, PolicyFailureReason detail) {
        Objects.requireNonNull(reason, "reasonが必要です");
        if (reason == PolicyEnforcementReason.PERMITTED) {
            throw new IllegalArgumentException("拒否の判定に許可の結果コードは付けられません");
        }
        return new PolicyEnforcementDecision(false, reason, detail == null ? PolicyFailureReason.NONE : detail);
    }

    /** 判定の全ての前提を満たしたか。 */
    public boolean permitted() {
        return permitted;
    }

    /** 結果コード（許可なら {@link PolicyEnforcementReason#PERMITTED}）。 */
    public PolicyEnforcementReason reason() {
        return reason;
    }

    /** policy 検証の細かな原因（policy の検証に進まなかった拒否・許可では {@link PolicyFailureReason#NONE}）。 */
    public PolicyFailureReason detail() {
        return detail;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PolicyEnforcementDecision that
                && permitted == that.permitted && reason == that.reason && detail == that.detail;
    }

    @Override
    public int hashCode() {
        return Objects.hash(permitted, reason, detail);
    }

    @Override
    public String toString() {
        return "PolicyEnforcementDecision[permitted=" + permitted + ", reason=" + reason + ", detail=" + detail + "]";
    }
}
