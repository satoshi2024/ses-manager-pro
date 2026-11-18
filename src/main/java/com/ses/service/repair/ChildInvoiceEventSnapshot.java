package com.ses.service.repair;

import java.util.Objects;

/**
 * 子インボイスイベント行の不変スナップショット (t_digital_invoice_event)
 */
public record ChildInvoiceEventSnapshot(
    Long id,
    Long digitalInvoiceId,
    String tenantId,
    Long legalEntityId
) {
    public ChildInvoiceEventSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(digitalInvoiceId, "digitalInvoiceId must not be null");
    }
}
