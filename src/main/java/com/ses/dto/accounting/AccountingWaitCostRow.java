package com.ses.dto.accounting;

import lombok.Data;

import java.math.BigDecimal;

/** Bench要員の月次待機原価。帰属組織と原価部門をSQLで解決する。 */
@Data
public class AccountingWaitCostRow {
    private Long organizationId;
    private Long costCenterId;
    private BigDecimal waitCost;
}
