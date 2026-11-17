package com.ses.privacy.foundation;

/**
 * policy envelope の登録・有効化の検証（NF07-REG、W-2）。実装は {@link ProductionPrivacyComponents#policyRegistrationVerifier}
 * だけが生成する（prod profile 必須）。
 */
public interface PolicyRegistrationVerifier {

    /**
     * 保存する前（登録）または current にする前（有効化）の envelope を検証する。canonical bytes を厳格に復号し
     * （正規形でない表現・末尾の余分な bytes・別 kind を拒否）、照会と同じ規則で trust anchor・範囲・時刻・失効状態・
     * Ed25519 署名・envelope の SoD を確かめる。
     *
     * @param callerTenant 登録者・有効化者の tenant（envelope の tenant と完全一致しなければ拒否）
     * @param canonical    envelope の canonical bytes（{@link ApprovalEnvelopeCanonicalizer} の v1 形式）
     * @param signature    canonical bytes への署名
     * @throws PolicyRegistrationRejectedException 拒否（理由は固定 code）
     * @throws SecurityException                   prod profile ではない
     */
    VerifiedPolicyRegistration verify(String callerTenant, byte[] canonical, byte[] signature);

    /**
     * 登録・有効化を受け付けられる trust の状態か（開放の gate）。trust anchor が1件以上登録され、そのうち1件以上の失効状態が
     * {@link RevocationStatus#ACTIVE} と確認できること。失効 provider が UNKNOWN を返す・例外・未設定なら false（第1期は
     * registry が空・失効 provider が常に UNKNOWN のため常に false）。判定できなければ false。
     */
    boolean trustReady();
}
