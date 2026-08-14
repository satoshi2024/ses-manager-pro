package com.ses.dto.contractdocument;

import com.ses.entity.ContractDocument;

import java.time.LocalDateTime;

/**
 * 契約書詳細のallow-list DTO。三hash・配送工程・artifact可用性を返し、storage path/renderedHtmlは返さない。
 * contractNo/recipientCompanyはcontrollerが親契約・顧客から解決して設定する（HFP-02-AC-03-04の表示用）。
 */
public record ContractDocumentDetailDto(
        Long id,
        Long contractId,
        String contractNo,
        String recipientCompany,
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
        return of(d, null, null);
    }

    public static ContractDocumentDetailDto of(ContractDocument d, String contractNo, String recipientCompany) {
        return new ContractDocumentDetailDto(
                d.getId(),
                d.getContractId(),
                contractNo,
                recipientCompany,
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
