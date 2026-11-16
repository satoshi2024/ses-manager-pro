package com.ses.privacy.production.retention.hold;

import java.util.List;

/**
 * hold 画面の表示内容（{@code GET /api/privacy/holds}）。privacy-admin だけが取得できる（{@code privacy.hold.view}）。
 *
 * @param gateState              tenant の gate 状態（{@code NO_HOLDS} / {@code VERIFIED} / {@code UNAVAILABLE}）
 * @param gateReason             UNAVAILABLE の固定 code
 * @param journalAvailability    Journal の可用性
 * @param startReady             hold を開始できる前提が揃っているか
 * @param missingPrerequisites   欠けている前提の固定 code
 * @param allowedTargetTypes     D-05 で許可された対象種別
 * @param holds                  hold の一覧（開始日時の新しい順）
 * @param unattributableElements SUBJECT hold で守られない element（選択が必要）
 * @param unclassifiedElements   未分類（UNKNOWN）の element
 * @param relatedElements        同じ実体の別 element（片方だけの DATA_ELEMENT hold では他方を守れない組）
 * @param holdableElements       DATA_ELEMENT hold の対象にできる element（catalog の NO_PERSONAL_DATA 以外）
 */
public record PrivacyHoldOverview(String gateState, String gateReason, String journalAvailability, boolean startReady,
                                  List<String> missingPrerequisites, List<String> allowedTargetTypes,
                                  List<HoldEntry> holds, List<ElementEntry> unattributableElements,
                                  List<String> unclassifiedElements, List<RelatedElements> relatedElements,
                                  List<String> holdableElements) {

    /**
     * hold の1件。
     *
     * @param reconciled reconciler が Journal から復元した行（理由・ticket は開始時の監査を参照）
     */
    public record HoldEntry(String holdId, String targetType, String subjectRef, String dataElementId, String status,
                            String reasonCode, String ticketRef, String requestedAt, long journalRevision,
                            boolean reconciled) {
    }

    /**
     * 帰属できない element。
     *
     * @param tenantRowCount       tenant 内の行数（照会の手段が無ければ null）
     * @param dataElementHoldActive ACTIVE な DATA_ELEMENT hold があるか
     */
    public record ElementEntry(String dataElementId, Long tenantRowCount, boolean dataElementHoldActive) {
    }

    /** 同じ実体の別 element の組と、その理由の message key。 */
    public record RelatedElements(String dataElementId, String relatedDataElementId, String noteKey) {
    }
}
