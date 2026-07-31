package com.ses.dto.document;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 文書台帳検索クエリ。
 */
@Data
public class DocumentSearchQuery {
    private String documentType;
    private String counterpartyName;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String direction;
    private String status;
    private Integer legalHoldFlag;

    private Integer page = 1;
    private Integer pageSize = 20;
}
