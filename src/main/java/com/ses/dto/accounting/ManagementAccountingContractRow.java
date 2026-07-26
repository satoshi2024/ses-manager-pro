package com.ses.dto.accounting;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 組織scopeをSQLで適用して取得する管理会計用契約行。 */
@Data
public class ManagementAccountingContractRow {
    private Long id;
    private Long engineerId;
    private Long organizationId;
    private Long salesUserId;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal sellingPrice;
    private BigDecimal costPrice;
    private String status;
}
