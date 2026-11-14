package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.PolicyDecisionStatus;
import com.ses.privacy.foundation.PolicyEvaluationQuery;
import com.ses.privacy.foundation.PolicyExplanationResult;
import com.ses.privacy.foundation.PolicyFailureReason;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;

import java.util.Objects;
import java.util.Optional;

/**
 * prod 以外の profile の policy 照会 port。何も読まず、常に {@code POLICY_UNKNOWN} を返す。
 *
 * <p>dev・test では production の trust registry・失効 provider を構成しないため、説明照会は権限と公開スイッチの
 * 確認だけに使える（production-bridge-phased-plan.md §3.1.7 の6）。合成層の照会 service は使わない（I-6）。
 */
public final class UnavailablePolicyReadonlyQueryPort implements PolicyReadonlyQueryPort {

    static final String EXPLANATION = "この環境では production policy source を構成していません";

    @Override
    public PolicyExplanationResult evaluate(PolicyEvaluationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return new PolicyExplanationResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE,
                null, null, query.dataElementId(), query.targetAction(), query.targetProvider(), null,
                Optional.empty(), Optional.empty(), EXPLANATION);
    }
}
