package com.ses.service;

import com.ses.entity.ComplianceMappingApprovalEvent;

/**
 * G2 mapping approval（Phase A step 3）。
 * 実actor＝現行openのCOMPLIANCE_RESPONSIBLE assignment（証跡2）による承認eventを記録する。
 * mapping_hashはcanonicalizerから再計算し、client supplied hashは信頼しない。
 * R23-P1-01 P0-5: exact CLEAN evidence（document id＋exact version id）をserver-side解決してsnapshotする。
 */
public interface ComplianceApprovalService {

    /**
     * PROVISIONAL_REVIEWEDのmappingを承認する（証跡2・実actor承認event）。
     *
     * @param mappingId  対象mapping version
     * @param workplaceId 対象事業所（assignment照合用）
     * @param reason      承認理由
     * @param evidenceDocumentId 根拠資料のdocument archive id
     * @param evidenceDocumentVersionId exact evidence version id（§4-5/6・必須）
     */
    ComplianceMappingApprovalEvent approve(Long mappingId, Long workplaceId, String reason,
                                           Long evidenceDocumentId, Long evidenceDocumentVersionId);
}
