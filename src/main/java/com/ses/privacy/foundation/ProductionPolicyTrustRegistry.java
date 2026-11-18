package com.ses.privacy.foundation;

import java.util.Map;

/**
 * production の trust anchor registry（W-3）。key は {@code authorityId + ":" + keyId}。
 *
 * <p>第1期は空（D-03 の署名前は anchor を登録しない）。空なら全ての policy が {@code UNKNOWN_TRUST_ANCHOR}
 * （{@code POLICY_DRIFT}）に閉じる。実 provider（KMS / CRL / OCSP）は D-03 の方式の決定後に
 * {@code PrivacyTrustSource} の adapter として追加する（REG・REL・説明照会が同じ Bean を使う）。
 */
public interface ProductionPolicyTrustRegistry {
    Map<String, TrustedAuthority> anchors();
}
