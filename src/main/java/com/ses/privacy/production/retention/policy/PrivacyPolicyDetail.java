package com.ses.privacy.production.retention.policy;

/** 説明APIの詳細（登録情報と、その key に対する説明照会の結果）。 */
public record PrivacyPolicyDetail(PrivacyPolicySummary policy, PrivacyPolicyExplanation evaluation) {
}
