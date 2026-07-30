package com.ses.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文書一覧表示用DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListDTO {
    private Long id;
    private String tenantId;
    private String documentType;
    private String documentTypeName;
    private String documentNo;
    private String title;
    private String counterpartyType;
    private String counterpartyNameSnapshot;
    private LocalDate transactionDate;
    private BigDecimal amount;
    private String currency;
    private String direction;
    private String status;
    private LocalDate retentionUntil;
    private Integer legalHoldFlag;
    private Long version;
    private Integer latestVersionNo;
    private String latestOriginalName;
    private Long latestSizeBytes;
    private String latestSha256;
    private LocalDateTime createdAt;
}
