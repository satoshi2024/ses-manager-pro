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
     *                   理由コード・ticket・理由本文の hash・現在の journal head・呼出し元・projection の開始者）
     * @param targetType 解除する hold の対象種別（projection の値）
     * @param rules      D-05 の解除の承認者規則と時計の許容ずれ（信頼できる設定の値）
     * @param issuer     発行者の claim
     * @param approver   承認者の claim
     * @throws HoldReleaseRejectedException 拒否（理由は固定 code）
     */
    VerifiedHoldRelease verify(HoldReleaseBinding presented, HoldReleaseBinding expected, String targetType,
                               HoldReleaseRules rules, HoldReleaseAuthorityClaim issuer,
                               HoldReleaseAuthorityClaim approver);

    /**
     * 解除を受け付けられる trust の状態か（開放の gate。NF07-REL review P3-9）。ISSUER の役割の anchor と APPROVER の役割の
     * anchor が、authority・主体・鍵を共有せずに1組以上登録され、失効 provider がその両方を {@link RevocationStatus#ACTIVE} と
     * 答えること（同じ authority の2つの鍵では開かない。RB-NF07-REG review P3-1）。組の両方が、verifier と同じ clock の時刻に
     * 有効期間内で、{@code tenantId} を範囲に含むこと（RB-NF07-REG delta review P3-1）。失効 provider が UNKNOWN を返す・例外・
     * 未設定なら false（第1期は registry が空・失効 provider が常に UNKNOWN のため常に false）。判定できなければ false。
     *
     * @param tenantId 授権 tenant（権限評価の設定 tenant。要求者の tenant はこれと一致しなければ403になる）
     */
    boolean trustReady(String tenantId);
}
