package com.ses.privacy.foundation;

/**
 * hold 解除 proof の検証（NF07-REL）。実装は {@link ProductionPrivacyComponents#holdReleaseVerifier} だけが生成する。
 */
public interface HoldReleaseVerifier {

    /**
     * 提示された束縛と2つの authority claim を検証し、{@link VerifiedHoldRelease} を発行する。
     *
     * @param presented  要求が提示した束縛（発行者・承認者が署名したもの）
     * @param expected   信頼できる現在値から組み立てた束縛（呼出し元の tenant・hold の partition・hold revision・
     *                   理由本文の hash・現在の journal head・呼出し元）
     * @param targetType 解除する hold の対象種別（projection の値）
     * @param issuer     発行者の claim
     * @param approver   承認者の claim
     * @throws HoldReleaseRejectedException 拒否（理由は固定 code）
     */
    VerifiedHoldRelease verify(HoldReleaseBinding presented, HoldReleaseBinding expected, String targetType,
                               HoldReleaseAuthorityClaim issuer, HoldReleaseAuthorityClaim approver);
}
