package com.ses.service.compliance;

/**
 * R23-P1-01 §9（G2-VERIFY-10/13）: reviewer subject fingerprint key provider。
 * tenant別専用key namespace（`compliance.gate.fingerprint.{tenantId}`）のkeyをkey versionごとに解決する。
 * 未解決・不正key versionはfail-closed（nullを返さず例外）。
 */
public interface ComplianceReviewerFingerprintKeyProvider {

    /**
     * 指定tenantの現行active key version。
     * @throws IllegalStateException tenant別key設定が解決できない場合（prodは起動時fail-fast）
     */
    String getCurrentKeyVersion(String tenantId);

    /**
     * 指定tenant・key versionのHMAC key bytesを解決する。
     * @throws IllegalArgumentException tenant/key versionが未知・不正な場合（fail-closed）
     */
    byte[] getKey(String tenantId, String keyVersion);
}
