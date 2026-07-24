package com.ses.dto.reconciliation;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReconciliationApplyRequest {
    @NotNull(message = "{error.reconciliation.invoiceRequired}")
    private Long invoiceId;
}
