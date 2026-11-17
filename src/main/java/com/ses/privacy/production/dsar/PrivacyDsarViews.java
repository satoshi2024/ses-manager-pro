package com.ses.privacy.production.dsar;

import java.util.List;

/**
 * DSAR の応答（受付〜SCOPED は NF07-A2a、検索〜交付記録は NF07-A2b）。privacy-admin だけが受け取る。
 *
 * <p>候補の表示は subject 参照・種別・link の状態と、F2 provider が返した行の件数（provider・data element ごと）だけで、
 * 行の値（氏名・連絡先・本文等）は一切含めない。第三者情報が混在し得る element（{@code MIXED_THIRD_PARTY}）も件数だけを出す
 * （交付の可否は A2b の redaction が決める。F2 review の DsarDeliveryClass）。日時は JST の表示文字列で返す。
 */
public final class PrivacyDsarViews {

    private PrivacyDsarViews() {
    }

    /**
     * 本人確認・範囲確定の前提。
     *
     * @param identityReady     D-01・D-06 の決定が有効で、本人確認以降へ進める
     * @param missing           欠けている決定（{@code d01} / {@code d06}）
     * @param dueRuleConfigured D-06 の期限規則（{@code privacy.dsar.due-days}）が設定され、受付で期限日を計算する
     * @param dueDays           期限規則の日数（未設定は null）
     */
    public record Readiness(boolean identityReady, List<String> missing, boolean dueRuleConfigured, Integer dueDays) {
        public Readiness {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /** 一覧。 */
    public record Overview(Readiness readiness, List<Summary> requests, long total, long page, long size) {
        public Overview {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }

    /** 一覧の1件（subject 参照を含めない）。 */
    public record Summary(long id, String requestNo, String requestType, String status, String channel,
                         String receivedAt, String dueDate, boolean overdue, boolean dueSoon, long rowVersion) {
    }

    /** 詳細。 */
    public record Detail(long id, String requestNo, String requestType, String status, String channel,
                         String receivedAt, String dueDate, boolean overdue, String intakeTicketRef,
                         String identityState, String identityEvidenceRef, String identityRuleRef,
                         String identityVerifiedAt, Long reopenOfRequestId, long rowVersion,
                         List<Candidate> candidates, int selectedCount, ScopeRecord scope, ClosureRecord closure,
                         List<String> nextActions, Readiness readiness) {
        public Detail {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        }
    }

    /**
     * 名寄せの候補。
     *
     * @param footprintAvailable provider の照会に成功したか（失敗した場合は件数を出さない）
     * @param footprintOmitted   別人と判定済み、または裁定を終えた case のため provider を照会しなかったか
     * @param footprint          provider・data element ごとの件数（行の値は含めない）
     */
    public record Candidate(long linkId, String subjectRef, String subjectType, String linkState,
                            String resolutionCode, String resolvedAt, boolean footprintAvailable,
                            boolean footprintOmitted, List<FootprintEntry> footprint) {
        public Candidate {
            footprint = footprint == null ? List.of() : List.copyOf(footprint);
        }
    }

    /** 候補の記録の所在（件数だけ）。 */
    public record FootprintEntry(String providerId, String dataElementId, int rowCount, String deliveryClass) {
    }

    /** 範囲確定の記録（snapshot の as-of と data element の数。hash は画面にも出さない）。 */
    public record ScopeRecord(String asOf, Integer elementCount) {
    }

    /** 終了の記録。 */
    public record ClosureRecord(String state, String reasonCode, String evidenceRef, String closedAt) {
    }

    /** 状態遷移の結果（画面は詳細を再読み込みする）。 */
    public record Transition(long requestId, String requestNo, String status, long rowVersion) {
    }

    // ---------------------------------------------------------------- NF07-A2b（検索〜交付記録）

    /**
     * 開示資料（草案・承認・交付）の区画（{@code GET /api/privacy/dsar/{id}/delivery}）。記録の値は含めず、件数・理由コード・
     * package の状態と内容 hash だけを返す。保存名（stored name）は返さない。
     *
     * @param status                case の状態
     * @param rowVersion            case の版（操作の要求に使う）
     * @param exportSupported       草案 package を作る請求か（開示請求 ACCESS だけ）
     * @param previewReady          草案を作る前提（D-01・D-06、package の最長保存期間、D-06 の不変）が揃っているか
     * @param packageRuleConfigured D-06 の package 最長保存期間（{@code privacy.dsar.package-max-age}）が設定されているか
     * @param decisionChanged       本人確認で適用した D-06 が現在の D-06 と違うか（delta review D-4）
     * @param hold                  本人の保全 hold の状態（読取りの表示だけ。統括の決定 A2a P3-9）
     * @param currentPackage        最新の package（無ければ null）
     * @param preparedByMe          草案の作成者が操作者自身か（自分の草案は承認できない）
     * @param approvedByMe          承認者が操作者自身か
     * @param approvedAt            承認日時
     * @param redactionReason       草案の第三者情報の扱い（{@code ThirdPartyRedactor} の理由コード）
     * @param autoReleasedCount     自動で package に入れた項目の数（規則未設定なら0）
     * @param providers             provider ごとの記録の件数
     * @param manualReview          要手動確認の一覧（未登録 provider・規則未設定・第三者混在の項目）
     * @param delivery              交付の記録（開示請求の CONFIRMED のときだけ）
     * @param approvedPackageDownloaded 承認した package を専用ダウンロードで取得済みか（取得後は期限切れでも作り直さず、交付を記録する。
     *                              review P3-4）
     * @param manualResolution      開示以外の請求の手動対応の完了の記録（review P2-2。無ければ null）
     * @param actions               この区画で行える操作（PREVIEW / REVIEW_DRAFT / APPROVE / DOWNLOAD / RECORD_DELIVERY /
     *                              RECORD_MANUAL_RESOLUTION。RECORD_DELIVERY は承認した package を取得した後だけ）
     * @param holdConflictResolutions 本人が HELD のとき、記録すると警告になる手動対応の結果コード（この請求の種別に当てはまる
     *                              DELETED / RESTRICTED。HELD でなければ空。記録は拒否しない。HYG-FINAL P3-F）
     */
    public record DeliverySection(String status, long rowVersion, boolean exportSupported, boolean previewReady,
                                  boolean packageRuleConfigured, boolean decisionChanged, HoldStatus hold,
                                  PackageSummary currentPackage, boolean preparedByMe, boolean approvedByMe,
                                  String approvedAt, String redactionReason, Integer autoReleasedCount,
                                  List<ProviderCount> providers, List<ManualReviewItem> manualReview,
                                  DeliveryRecord delivery, boolean approvedPackageDownloaded,
                                  ManualResolutionRecord manualResolution, List<String> actions,
                                  List<String> holdConflictResolutions) {
        public DeliverySection {
            providers = providers == null ? List.of() : List.copyOf(providers);
            manualReview = manualReview == null ? List.of() : List.copyOf(manualReview);
            actions = actions == null ? List.of() : List.copyOf(actions);
            holdConflictResolutions = holdConflictResolutions == null ? List.of()
                    : List.copyOf(holdConflictResolutions);
        }
    }

    /**
     * package の要約（保存名は含めない）。
     *
     * @param expired {@code purge_after} を過ぎたか（取得・承認・交付記録はできない。作り直す）
     */
    public record PackageSummary(long id, String status, String contentSha256, long sizeBytes, String createdAt,
                                 String purgeAfter, boolean expired, boolean createdByMe) {
    }

    /** provider ごとの記録の件数。 */
    public record ProviderCount(String providerId, int recordCount) {
    }

    /**
     * 開示以外の請求の手動対応の完了の記録（結果のコード・証跡の参照・記録日時）。
     *
     * @param holdConflict 記録した結果が削除・利用停止で、本人が現在 HELD か（警告の表示だけ。HYG-FINAL P3-F）
     */
    public record ManualResolutionRecord(String resolutionCode, String evidenceRef, String resolvedAt,
                                         boolean holdConflict) {
    }

    /**
     * 手動対応の完了の記録（{@code POST /api/privacy/dsar/{id}/resolution}）の応答。{@link Transition} の4項目に、記録した直後の
     * 本人の hold の状態と警告の印を足す（記録は止めない。HYG-FINAL P3-F の残り、RB-NF07-HOLD）。
     *
     * @param holdState    本人の保全 hold の状態（{@code NONE} / {@code HELD} / {@code NOT_HELD} / {@code UNKNOWN} / {@code NOT_SCOPED}。
     *                     区画の {@code hold.state} と同じ値）
     * @param holdConflict 記録した結果が削除・利用停止で、本人が HELD か（区画の {@code manualResolution.holdConflict} と同じ判定）
     */
    public record ResolutionRecorded(long requestId, String requestNo, String status, long rowVersion, String holdState,
                                     boolean holdConflict) {
    }

    /**
     * 要手動確認の1項目（記録の値は含めない）。
     *
     * @param providerId    provider（未登録なら catalog の値または null）
     * @param dataElementId data element
     * @param reason        理由コード（{@code PROVIDER_UNREGISTERED}・{@code REDACTION_RULES_UNSET}・
     *                      {@code REDACTION_MIXED_MANUAL_ONLY} 等）
     * @param recordCount   該当する記録の件数（未登録 provider は数えられないため null）
     */
    public record ManualReviewItem(String providerId, String dataElementId, String reason, Integer recordCount) {
    }

    /**
     * 本人の保全 hold の状態（読取りの表示だけ）。
     *
     * @param state NONE（hold 機構を使っていない）/ HELD / NOT_HELD / UNKNOWN（判定できない）/ NOT_SCOPED（本人が未確定）
     */
    public record HoldStatus(String state) {
    }

    /** 交付の記録。 */
    public record DeliveryRecord(String deliveredAt, String deliveryMethod, String deliveryEvidenceRef,
                                 boolean deliveredByMe) {
    }

    /** 専用ダウンロードの内容（controller が添付として返す）。 */
    public record PackageDownload(String filename, byte[] content) {
    }
}
