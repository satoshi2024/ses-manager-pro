package com.ses.privacy.production.dsar;

/**
 * DSAR 受付〜SCOPED の要求本文（NF07-A2a）。subject 参照は本文だけで受け取り、URL・query に入れない。
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
}
