package com.ses.privacy.foundation;

import java.util.Map;

/**
 * production の trust anchor registry（W-3）。key は {@code authorityId + ":" + keyId}。
 *
 * <p>第1期は空（D-03 の署名前は anchor を登録しない）。空なら全ての policy が {@code UNKNOWN_TRUST_ANCHOR}
 * （{@code POLICY_DRIFT}）に閉じる。D-03 の方式（AWS KMS 非対称署名）の anchor は {@code PrivacyTrustSource} の adapter
 * （{@code KmsPrivacyTrustSource}）が trust 文書から作る（REG・REL・説明照会が同じ Bean を使う）。
 */
public interface ProductionPolicyTrustRegistry {
    Map<String, TrustedAuthority> anchors();
}
