package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.RevocationStatusProvider;

/**
 * NF07 の trust（W-3）の接続点。D-03 で決める方式（KMS / HSM の anchor registry、CRL / OCSP の失効 provider 等）の adapter は
 * 本 interface を実装し、{@link PrivacyTrustConfiguration} の Bean を差し替える。policy envelope の登録・照会（NF07-REG・W-4）と
 * hold 解除（NF07-REL）が同じ Bean を使う（anchor と失効状態の正本を1つにする）。
 *
 * <p>第1期は {@link UnconfiguredPrivacyTrustSource}（anchor 0件、失効状態は常に UNKNOWN）で、登録・有効化と解除の入口は404のまま、
 * 説明照会は {@code APPROVED_ACTIVE} にならない。
 *
 * <p>adapter の契約（D-03 の方式によらず守る。違えば fail-open になる）:
 * <ul>
 *   <li>{@link #policyAnchors()}・{@link #holdReleaseAnchors()} は検証・開放の判定のたびに呼ばれる。返す map の key は
 *       {@code authorityId + ":" + keyId}、鍵は Ed25519 だけ（{@code ConfiguredPolicyTrustRegistry}・
 *       {@code ConfiguredHoldReleaseTrustRegistry}・{@code HoldReleaseTrustAnchor} の constructor が検査する。これらを使うか、
 *       同じ検査をする）。解除 authority の主体は {@code sys_user:<id>} の正規形。読めない・例外なら空として扱われ閉じる。</li>
 *   <li>鍵の rotation は新しい {@code keyId} の anchor を足して有効期間を重ねる（旧鍵の {@code validUntil} までは両方が有効）。
 *       anchor を消すと、その鍵の署名は即時に {@code UNKNOWN_TRUST_ANCHOR} になる。</li>
 *   <li>{@link #revocations()} の {@code statusOf} は、鍵（{@code authorityId:keyId}）と policy envelope の
 *       {@code revocationReference} の両方に答える。{@code ACTIVE} は失効していないことを確認できたときだけ返し、照会できない・
 *       応答が古すぎる・障害なら {@code UNKNOWN}（決して {@code ACTIVE} を既定にしない）。失効は次の照会から {@code REVOKED} に
 *       なる（照会は {@code POLICY_REVOKED}、登録・有効化・解除は拒否）。開放の gate も要求ごとに anchor を照会する
 *       （REG は1件、REL は2件まで）ため、外部照会の方式では D-03 で決める鮮度の上限で cache する。</li>
 * </ul>
 */
public interface PrivacyTrustSource {

    /** policy envelope の署名 anchor（NF07-REG の登録・有効化と、説明照会の検証）。 */
    ProductionPolicyTrustRegistry policyAnchors();

    /** hold 解除の発行者・承認者の anchor（NF07-REL）。 */
    HoldReleaseTrustRegistry holdReleaseAnchors();

    /** 鍵と policy envelope の失効状態（REG・REL・説明照会が共用）。 */
    RevocationStatusProvider revocations();
}
