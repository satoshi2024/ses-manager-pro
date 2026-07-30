package com.ses.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文書詳細情報DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDetailDTO {
    private Long id;
    private String tenantId;
    private String documentType;
    private String documentTypeName;
    private String documentNo;
    private String title;
    private String counterpartyType;
    private Long counterpartyId;
    private String counterpartyNameSnapshot;
    private LocalDate transactionDate;
    private BigDecimal amount;
    private String currency;
    private String direction;
    private String status;
    private LocalDate retentionUntil;
    private Integer legalHoldFlag;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private List<DocumentVersionDTO> versions;
    private List<DocumentLinkDTO> links;
}
