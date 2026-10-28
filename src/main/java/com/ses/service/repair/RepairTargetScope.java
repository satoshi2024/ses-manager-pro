package com.ses.service.repair;

import java.util.Objects;

/**
 * 修復対象およびターゲットスコープの不変スナップショット
 */
public record RepairTargetScope(
    Long invoiceId,
    String targetTenantId,
    Long targetLegalEntityId,
    Long expectedInvoiceVersion
) {
    public RepairTargetScope {
        Objects.requireNonNull(invoiceId, "invoiceId must not be null");
        Objects.requireNonNull(targetTenantId, "targetTenantId must not be null");
        Objects.requireNonNull(targetLegalEntityId, "targetLegalEntityId must not be null");
        Objects.requireNonNull(expectedInvoiceVersion, "expectedInvoiceVersion must not be null");
    }
}
