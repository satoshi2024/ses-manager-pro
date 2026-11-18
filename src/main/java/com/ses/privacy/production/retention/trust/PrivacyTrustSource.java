package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.RevocationStatusProvider;

/**
 * NF07 の trust（W-3）の接続点。D-03 で決める方式の adapter は本 interface を実装し、{@link PrivacyTrustConfiguration} の Bean が
 * それを返す。policy envelope の登録・照会（NF07-REG・W-4）と hold 解除（NF07-REL）が同じ Bean を使う（anchor と失効状態の
 * 正本を1つにする）。
 *
 * <p>D-03 の方式は AWS KMS 非対称署名・鍵 version 単位の失効（2026-11-18 決定）で、adapter は {@link KmsPrivacyTrustSource}
 * （trust 文書の file を判定のたびに読む。{@code privacy.trust.kms.anchor-file} を設定したときだけ使う）。未設定（既定）なら
 * 第1期と同じ {@link UnconfiguredPrivacyTrustSource}（anchor 0件、失効状態は常に UNKNOWN）で、登録・有効化と解除の入口は404の
 * まま、説明照会は {@code APPROVED_ACTIVE} にならない。
 *
 * <p>呼出しの時機: 説明照会・登録・解除の Spring 登録は、本 Bean を {@link #policyAnchorsOf}・{@link #holdReleaseAnchorsOf}・
 * {@link #revocationsOf} で包んで verifier に渡す。そのため {@link #policyAnchors()}・{@link #holdReleaseAnchors()}・
 * {@link #revocations()} は起動時に1回ではなく、<b>検証と開放の判定のたびに</b>呼ばれる（RB-NF07-REG review P2-1）。adapter は
 * 呼出しごとに現在の anchor から registry を作って返してよく、anchor を消せば次の検証から {@code UNKNOWN_TRUST_ANCHOR}、
 * 開放の gate は次の判定から閉じる（再起動は要らない）。
 *
 * <p>adapter の契約（D-03 の方式によらず守る。違えば fail-open になる）:
 * <ul>
 *   <li>方式は Ed25519 の署名鍵を扱えること（verifier は Ed25519 だけを検証し、他の算法の鍵は anchor として扱わない）。</li>
 *   <li>返す map の key は {@code authorityId + ":" + keyId}、鍵は Ed25519 だけ（{@code ConfiguredPolicyTrustRegistry}・
 *       {@code ConfiguredHoldReleaseTrustRegistry}・{@code HoldReleaseTrustAnchor} の constructor が検査する。これらを使うか、
 *       同じ検査をする。開放の gate と verifier も key の食い違い・Ed25519 以外の鍵の anchor を数えない）。解除 authority の主体は
 *       {@code sys_user:<id>} の正規形。読めない・例外なら空として扱われ閉じる。呼出しのたびに読まれるため、外部照会の方式では
 *       D-03 で決める鮮度の上限で cache する。</li>
 *   <li>鍵の rotation は新しい {@code keyId} の anchor を足して有効期間を重ねる（旧鍵の {@code validUntil} までは両方が有効）。
 *       anchor を消すと、その鍵の署名は次の検証から {@code UNKNOWN_TRUST_ANCHOR} になる。退役した鍵の公開鍵は registry から外しても
 *       保管しておく（監査 {@code RELEASE_CLAIM} の claim や保存した envelope を事後に検証し直すため）。</li>
 *   <li>{@link #revocations()} の {@code statusOf} は、鍵（{@code authorityId:keyId}）と policy envelope の
 *       {@code revocationReference} の両方に答える。{@code ACTIVE} は失効していないことを確認できたときだけ返し、照会できない・
 *       応答が古すぎる・障害なら {@code UNKNOWN}（決して {@code ACTIVE} を既定にしない）。失効は次の照会から {@code REVOKED} に
 *       なる（照会は {@code POLICY_REVOKED}、登録・有効化・解除は拒否）。開放の gate も要求ごとに anchor を照会する
 *       （REG は1件、REL は発行者と承認者の1組まで）ため、外部照会の方式では D-03 で決める鮮度の上限で cache する。</li>
 * </ul>
 */
public interface PrivacyTrustSource {

    /** policy envelope の署名 anchor（NF07-REG の登録・有効化と、説明照会の検証）。 */
    ProductionPolicyTrustRegistry policyAnchors();

    /** hold 解除の発行者・承認者の anchor（NF07-REL）。 */
    HoldReleaseTrustRegistry holdReleaseAnchors();

    /** 鍵と policy envelope の失効状態（REG・REL・説明照会が共用）。 */
    RevocationStatusProvider revocations();

    /**
     * {@code source} の {@link #policyAnchors()} を参照のたびに呼ぶ registry（verifier に渡す形）。source が null を返す・例外を
     * 投げれば、呼出し側（verifier・gate）が空として扱い閉じる。
     */
    static ProductionPolicyTrustRegistry policyAnchorsOf(PrivacyTrustSource source) {
        return () -> source.policyAnchors().anchors();
    }

    /** {@code source} の {@link #holdReleaseAnchors()} を参照のたびに呼ぶ registry（verifier・解除の service に渡す形）。 */
    static HoldReleaseTrustRegistry holdReleaseAnchorsOf(PrivacyTrustSource source) {
        return () -> source.holdReleaseAnchors().anchors();
    }

    /** {@code source} の {@link #revocations()} を照会のたびに呼ぶ provider（例外は呼出し側が UNKNOWN として扱う）。 */
    static RevocationStatusProvider revocationsOf(PrivacyTrustSource source) {
        return reference -> source.revocations().statusOf(reference);
    }
}
