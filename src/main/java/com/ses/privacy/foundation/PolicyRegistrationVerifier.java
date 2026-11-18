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
     * 保存した envelope の発行日時（canonical bytes を厳格に復号した値）。有効化の順序の比較（current より後に署名された
     * revision だけを current にする）にだけ使い、trust の判定には使わない（current の鍵が失効・rotation 済みでも、置き換えの
     * 判定のために読めるようにする）。
     *
     * @throws PolicyRegistrationRejectedException canonical bytes を復号できない
     * @throws SecurityException                   prod profile ではない
     */
    java.time.Instant signedIssuedAt(byte[] canonical, byte[] signature);

    /**
     * 登録・有効化を受け付けられる trust の状態か（開放の gate）。trust anchor が1件以上登録され、そのうち1件以上が、verifier と
     * 同じ clock の時刻に有効期間内で、{@code tenantId} を範囲に含み、失効状態が {@link RevocationStatus#ACTIVE} と確認できること
     * （RB-NF07-REG delta review P3-1。期限切れ・tenant 外の anchor しか無ければ開かない）。失効 provider が UNKNOWN を返す・例外・
     * 未設定なら false（第1期は registry が空・失効 provider が常に UNKNOWN のため常に false）。判定できなければ false。
     *
     * @param tenantId 授権 tenant（権限評価の設定 tenant。登録者・有効化者の tenant はこれと一致しなければ403になる）
     */
    boolean trustReady(String tenantId);
}
