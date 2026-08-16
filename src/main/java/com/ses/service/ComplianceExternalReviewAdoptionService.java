package com.ses.service;

import com.ses.entity.ComplianceExternalReviewAdoptionEvent;

import java.util.List;

/**
 * R23-P1-01 §3.2/§4: adoption eventの記録・revoke（append-only・EXTERNAL_REVIEW_ADOPT / EXTERNAL_REVIEW_REVOKE operation）。
 * gateはAPPROVED adoption eventのみ採用する（§G2-VERIFY-09）。REJECTED / REVOKEDはgate不採用。
 */
public interface ComplianceExternalReviewAdoptionService {

    /**
     * APPROVED adoptionを記録する（§3.2 step 3）。
     * 前提: 同一tenantのSUBMITTED review event・identity/authorship verification必須・
     * frozen policy（qualification/active_status flag=true時）のverification必須・
     * exact evidence CLEAN・mapping/policy/evidence snapshot一致。
     * 同一submitted review chainの初回adoptionのみ許可（APPROVEDまたはREJECTED）。
     */
    ComplianceExternalReviewAdoptionEvent approve(
            Long submittedReviewEventId,
            Long identityVerificationEventId,
            Long qualificationVerificationEventId,
            Long activeStatusVerificationEventId,
            Long authorshipVerificationEventId,
            Long evidenceDocumentId,
            Long evidenceDocumentVersionId,
            String idempotencyKey);

    /**
     * REJECTED adoptionを記録する（§3.2 step 3）。verificationは不要。
     * 同一submitted review chainの初回adoptionのみ許可。
     */
    ComplianceExternalReviewAdoptionEvent reject(
            Long submittedReviewEventId,
            String reason,
            String idempotencyKey);

    /**
     * APPROVED adoptionをREVOKEDにする（§3.2 step 4・EXTERNAL_REVIEW_REVOKE）。
     * REVOKEDはAPPROVED adoptionだけをtargetにできる（REJECTEDをtarget不可・triggerでも拒否）。
     */
    ComplianceExternalReviewAdoptionEvent revoke(
            Long targetAdoptionEventId,
            String reason,
            String idempotencyKey);

    /** 指定submitted review chainのadoption event一覧（adopted_at, id順）。 */
    List<ComplianceExternalReviewAdoptionEvent> listBySubmittedReview(Long submittedReviewEventId);
}
