package com.ses.featureenablement.service;

import com.ses.entity.FeatureSignOff;

import java.util.Map;

/**
 * 組の種類ごとの整合の検査の拡張点（feature-enablement-console design §2.6.1。組の確認の時に呼ぶ）。
 *
 * <p>NF07 の {@code NF07_DECISION_BINDING}（D-09 の {@code approvalDigest} と組の D-01・(D-06)・D-09 から計算した値の一致）は
 * 段階 C（FE-C01）で実装を足す。合わなければ {@code BusinessException}（400 {@code error.featureEnablement.bundleInconsistent}）を
 * 投げ、組の確認の transaction は何も変えずに終わる。</p>
 */
public interface FeatureSignOffBundleConsistencyCheck {

    /** 対象の組の種類。 */
    String bundleKind();

    /**
     * 組の全ての member（種別 → 署名の行。引き継ぐ確認済みの署名を含む）の整合を確かめる。
     *
     * @param tenantId 設定の tenant
     * @param members  種別 → 署名の行
     */
    void verify(String tenantId, Map<String, FeatureSignOff> members);
}
