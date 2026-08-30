package com.ses.dto.asset;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 紛失資産インシデント対応情報の部分更新リクエスト。 */
@Data
public class AssetLostIncidentUpdateRequest {
    private String incidentDetails;
    private String remoteWipeStatus;
    private LocalDateTime remoteWipeRequestedAt;
    private LocalDateTime remoteWipeExecutedAt;
    private LocalDateTime remoteWipeConfirmedAt;
    private String policeReportNumber;
    private String insuranceClaimStatus;
    private LocalDateTime insuranceClaimedAt;
    private List<Long> documentIds;
}
