package com.ses.dto.accounting;

import lombok.Data;

import java.math.BigDecimal;

/** 月次snapshot用の要員単位Bench待機原価。 */
@Data
public class AccountingWaitCostSnapshotRow {
    private Long engineerId;
    private Long organizationId;
    private Long costCenterId;
    private BigDecimal waitCost;
}
