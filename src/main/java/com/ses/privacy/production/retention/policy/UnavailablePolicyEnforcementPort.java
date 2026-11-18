package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.foundation.PolicyFailureReason;

/**
 * prod 以外の profile の内部判定 port（NF07-NF08-LINK）。何も読まず、常に拒否（{@code ENFORCEMENT_UNAVAILABLE}）を返す。
 *
 * <p>dev・test では production の trust registry・失効 provider を構成しないため、NF07 policy を根拠にした実行
 * （NF08 の承認済み外部送信等）は常に止まる（計画 §8: 1つでも欠ければ外部送信は必ず拒否される）。
 */
public final class UnavailablePolicyEnforcementPort implements PolicyEnforcementPort {

    @Override
    public PolicyEnforcementDecision check(PolicyEnforcementRequest request) {
        return PolicyEnforcementDecision.deny(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE,
                PolicyFailureReason.NONE);
    }
}
