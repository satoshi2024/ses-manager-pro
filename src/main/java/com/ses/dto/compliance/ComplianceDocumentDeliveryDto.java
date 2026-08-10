package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 法定帳票の交付記録（T064 B1）。
 * confirmedAtがNULLの行は「受領未確認」（未交付ではない、design §5.1）。
 */
@Data
public class ComplianceDocumentDeliveryDto {

    private Long id;
    private Long documentId;
    private String documentType;
    private Integer templateVersion;
    private String snapshotHash;
    private String deliveryMethod;
    private String deliveryStatus;
    private LocalDateTime deliveredAt;
    private LocalDateTime confirmedAt;
    private String confirmationNote;
    private String recipientNameSnapshot;
}
