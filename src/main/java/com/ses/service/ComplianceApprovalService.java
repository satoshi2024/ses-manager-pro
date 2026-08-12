package com.ses.service;

import com.ses.entity.ComplianceMappingApprovalEvent;

/**
 * G2 mapping approval（Phase A step 3）。
 * 実actor（現行openのCOMPLIANCE_RESPONSIBLE assignment）による承認eventを記録する。
 * mapping_hashはcanonicalizerから再計算し、client supplied hashは信頼しない。
 */
public interface ComplianceApprovalService {

    /**
     * PROVISIONAL_REVIEWEDのmappingを承認する（証跡2・実actor承認event）。
     *
     * @param mappingId  対象mapping version
     * @param workplaceId 対象事業所（assignment照合用）
     * @param reason      承認理由
     * @param evidenceDocumentId 根拠資料（document archive、任意）
     */
    ComplianceMappingApprovalEvent approve(Long mappingId, Long workplaceId, String reason, Long evidenceDocumentId);
}
