package com.ses.dto.contractdocument;

import com.ses.entity.ContractDocument;

import java.time.LocalDateTime;

/**
 * 契約書一覧のallow-list DTO（HFP-02-AC-08-03）。
 * entity/path/renderedHtml/raw errorを公開しない。
 */
public record ContractDocumentListDto(
        Long id,
        Long contractId,
        Long templateId,
        Integer templateVersion,
        String status,
        String dispatchState,
        Integer cloudsignStatus,
        String recipientName,
        String recipientEmail,
        String sourcePdfSha256,
        LocalDateTime sentAt,
        LocalDateTime lastSyncedAt,
        String operationId,
        boolean signedPdfAvailable,
        boolean certificateAvailable) {

    public static ContractDocumentListDto of(ContractDocument d) {
        return new ContractDocumentListDto(
                d.getId(),
                d.getContractId(),
                d.getTemplateId(),
                d.getTemplateVersion(),
                d.getStatus(),
                d.getDispatchState(),
                d.getCloudsignStatus(),
                d.getRecipientName(),
                d.getRecipientEmail(),
                d.getPdfSha256(),
                d.getSentAt(),
                d.getLastSyncedAt(),
                d.getOperationId(),
                d.getSignedArchiveDocumentId() != null || (d.getSignedPdfPath() != null && !d.getSignedPdfPath().isBlank()),
                d.getCertificateArchiveDocumentId() != null || (d.getCertificatePath() != null && !d.getCertificatePath().isBlank()));
    }
}
