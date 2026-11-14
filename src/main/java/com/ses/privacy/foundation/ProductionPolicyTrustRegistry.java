package com.ses.privacy.foundation;

import java.util.Map;

/**
 * production の trust anchor registry（W-3）。key は {@code authorityId + ":" + keyId}。
 *
 * <p>第1期は空（D-03 の署名前は anchor を登録しない）。空なら全ての policy が {@code UNKNOWN_TRUST_ANCHOR}
 * （{@code POLICY_DRIFT}）に閉じる。実 provider（KMS / CRL / OCSP）は NF07-TRUST で追加する。
 */
public interface ProductionPolicyTrustRegistry {
    Map<String, TrustedAuthority> anchors();
}
