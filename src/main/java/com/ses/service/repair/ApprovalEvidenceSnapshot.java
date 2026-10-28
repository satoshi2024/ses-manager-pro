package com.ses.service.repair;

import java.util.Objects;

/**
 * 決裁承認証跡の不変入力スナップショット。
 * <p>
 * 注意: 本スナップショットの存在・検証通過は、実環境における正式な決裁承認の完了を表明するものではない。
 * </p>
 */
public record ApprovalEvidenceSnapshot(
    String requestNo,
    Integer roundNo,
    Long invoiceId,
    String targetTenantId,
    Long targetLegalEntityId,
    Long targetVersion
) {
    public ApprovalEvidenceSnapshot {
        Objects.requireNonNull(requestNo, "requestNo must not be null");
        Objects.requireNonNull(roundNo, "roundNo must not be null");
        Objects.requireNonNull(invoiceId, "invoiceId must not be null");
        Objects.requireNonNull(targetTenantId, "targetTenantId must not be null");
        Objects.requireNonNull(targetLegalEntityId, "targetLegalEntityId must not be null");
        Objects.requireNonNull(targetVersion, "targetVersion must not be null");
    }
}
