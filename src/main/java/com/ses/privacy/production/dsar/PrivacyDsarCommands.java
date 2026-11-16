package com.ses.privacy.production.dsar;

/**
 * DSAR の要求本文（受付〜SCOPED は NF07-A2a、草案・承認・交付記録は NF07-A2b）。subject 参照は本文だけで受け取り、URL・query に入れない。
 *
 * <p>状態を変える要求は全て、画面が表示した時点の {@code rowVersion} を持つ（CAS。古ければ 409）。
 * 依頼者の連絡先・本人確認書類の本体を受け取る項目は持たない（外部 ticket・証跡の参照だけ）。
 */
public final class PrivacyDsarCommands {

    private PrivacyDsarCommands() {
    }

    /**
     * 受付。
     *
     * @param requestType       ACCESS / CORRECTION / DELETION / RESTRICTION / OBJECTION
     * @param channel           受付経路（{@link PrivacyDsarVocabulary.Channel}）
     * @param receivedAt        受付日時（JST の {@code yyyy-MM-ddTHH:mm}。省略時は現在時刻）
     * @param intakeTicketRef   受付 ticket の参照（必須。依頼者の連絡先は外部 ticket にだけ置く）
     * @param reopenOfRequestId 再申立・再開の元 case（終了済みの case だけ。元 case は書き換えない）
     */
    public record Intake(String requestType, String channel, String receivedAt, String intakeTicketRef,
                         Long reopenOfRequestId) {
    }

    /**
     * 本人確認の記録。適用した D-06 の決定ID は設定から取り、要求では受け取らない。
     *
     * @param rowVersion          画面表示時の版
     * @param identityEvidenceRef 本人確認証跡の参照（書類の本体は保存しない）
     */
    public record IdentityVerification(Long rowVersion, String identityEvidenceRef) {
    }

    /**
     * 名寄せの候補の登録。
     *
     * @param rowVersion 画面表示時の版
     * @param subjectRef 種別付き subject 参照（例: {@code ENGINEER:123}）
     */
    public record CandidateAddition(Long rowVersion, String subjectRef) {
    }

    /**
     * 候補の人手裁定。
     *
     * @param rowVersion     画面表示時の版
     * @param linkState      SELECTED / REJECTED
     * @param resolutionCode 判定コード（任意。英大文字・数字・下線）
     */
    public record CandidateResolution(Long rowVersion, String linkState, String resolutionCode) {
    }

    /**
     * 範囲の確定（SELECTED がちょうど1件のときだけ SCOPED へ進む）。
     *
     * @param rowVersion 画面表示時の版
     */
    public record ScopeConfirmation(Long rowVersion) {
    }

    /**
     * 終了（BLOCKED / CANCELLED）。
     *
     * @param rowVersion  画面表示時の版
     * @param reasonCode  終了理由（{@link PrivacyDsarVocabulary.CloseReason}）
     * @param evidenceRef 証跡の参照（任意）
     */
    public record Closure(Long rowVersion, String reasonCode, String evidenceRef) {
    }

    /**
     * 検索と草案 package の作成（NF07-A2b。SCOPED → PREVIEWED、期限切れの package の作り直しも同じ）。
     *
     * @param rowVersion 画面表示時の版
     */
    public record Preview(Long rowVersion) {
    }

    /**
     * 草案 package の承認（作成者以外の privacy-admin。PREVIEWED → APPROVED）。承認する package を id と内容 hash で特定する。
     *
     * @param rowVersion    画面表示時の版
     * @param packageId     承認する package の ID
     * @param contentSha256 画面で確認した package の内容 hash
     */
    public record Approval(Long rowVersion, Long packageId, String contentSha256) {
    }

    /**
     * system の外で交付した後の交付記録（APPROVED → CONFIRMED。package は同時に PURGED）。承認した package の id と hash を照合する。
     *
     * @param rowVersion          画面表示時の版
     * @param packageId           交付した package の ID（承認した package と一致すること）
     * @param contentSha256       交付した package の内容 hash（承認した hash と一致すること）
     * @param deliveryMethod      交付の方法（{@link PrivacyDsarVocabulary.DeliveryMethod}）
     * @param deliveryEvidenceRef 交付の証跡の参照（必須。送付記録・受領記録等の ID）
     */
    public record Delivery(Long rowVersion, Long packageId, String contentSha256, String deliveryMethod,
                           String deliveryEvidenceRef) {
    }
}
