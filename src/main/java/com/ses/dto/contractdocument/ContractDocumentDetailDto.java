package com.ses.dto.contractdocument;

import com.ses.entity.ContractDocument;

import java.time.LocalDateTime;

/**
 * 契約書詳細のallow-list DTO。三hash・配送工程・artifact可用性を返し、storage path/renderedHtmlは返さない。
 */
public record ContractDocumentDetailDto(
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
        String signedPdfSha256,
        String certificateSha256,
        boolean signedPdfAvailable,
        boolean certificateAvailable,
        String operationId,
        String lastProviderErrorCode,
        LocalDateTime sentAt,
        LocalDateTime completedAt,
        LocalDateTime lastSyncedAt) {

    public static ContractDocumentDetailDto of(ContractDocument d) {
        return new ContractDocumentDetailDto(
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
                d.getSignedPdfSha256(),
                d.getCertificateSha256(),
                d.getSignedArchiveDocumentId() != null || (d.getSignedPdfPath() != null && !d.getSignedPdfPath().isBlank()),
                d.getCertificateArchiveDocumentId() != null || (d.getCertificatePath() != null && !d.getCertificatePath().isBlank()),
                d.getOperationId(),
                d.getLastProviderErrorCode(),
                d.getSentAt(),
                d.getCompletedAt(),
                d.getLastSyncedAt());
    }
}
