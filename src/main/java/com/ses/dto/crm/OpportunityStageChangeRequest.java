package com.ses.dto.crm;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 商機ステージ変更リクエスト。versionは競合検知用に任意指定できる。 */
@Data
public class OpportunityStageChangeRequest {
    @NotBlank(message = "ステージは必須です")
    private String stage;

    private String lostReason;

    private Integer version;
}
