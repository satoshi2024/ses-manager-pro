package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.TableField;
import com.ses.common.base.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/** 管理レポートschedule（m_report_schedule）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_report_schedule")
public class ReportSchedule extends BaseEntity {

    private String tenantId;
    private Long templateVersionId;
    private String cronExpression;
    private String timezoneId;
    private Integer enabled;
    private String lockKey;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String scopeOwnerType;
    private Long scopeOwnerId;
    private String organizationScopeJson;
    private String scopePolicyVersion;
    private String scopeHash;
    private LocalDateTime retryScheduledAt;
    /** claim中の論理実行時刻。成功時にnext_run_atを進める。 */
    private LocalDateTime processingLogicalRunAt;
    /** processing lease取得日時。stale時は同一logical monthを再claimする。 */
    private LocalDateTime processingClaimedAt;
    /** scheduler内部のclaim所有権token。API/diagnosticから隠す。 */
    @JsonIgnore
    @ToString.Exclude
    @TableField("processing_claim_token")
    private String processingClaimToken;
    private Integer failureCount;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
