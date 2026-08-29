package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退社3大blockerの例外免除台帳。承認申請との対応を追記し、メモリ状態には依存しない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset_offboarding_waiver")
public class AssetOffboardingWaiver extends BaseEntity {

    private Long engineerId;
    /** 退社案件ID。要員単位の過去免除を別案件へ流用しないため必須の業務scope。 */
    private Long lifecycleCaseId;
    /** RESIGN_ASSET_RETURNタスクID。案件内の別タスクへの流用を防止する。 */
    private Long lifecycleTaskId;
    private Long approvalRequestId;
    private String reason;
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
