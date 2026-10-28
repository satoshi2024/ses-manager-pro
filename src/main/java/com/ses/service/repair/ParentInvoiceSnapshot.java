package com.ses.service.repair;

import java.util.Objects;

/**
 * 親インボイス行の不変スナップショット (t_digital_invoice)
 */
public record ParentInvoiceSnapshot(
    Long id,
    String tenantId,
    Long legalEntityId,
    Long version
) {
    public ParentInvoiceSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
    }
}
