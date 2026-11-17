package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.TableField;
import com.ses.common.base.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 管理レポートschedule（m_report_schedule）。
 *
 * <p>次回実行・最終論理実行・retry・processing lease・claim token・失敗回数は、schedulerのclaim/completion
 * CAS（{@code ReportScheduleMapper} の専用SQL）だけが更新する。entity経由の {@code updateById} /
 * {@code update(entity, wrapper)} が読取時点の古い値でleaseを書き戻さないよう、これらの列は
 * {@link FieldStrategy#NEVER} でentity由来のUPDATEから除外する。lease/retry/token/失敗回数はINSERTからも除外し、
 * DB既定値（NULL/0）で作成する。{@code nextRunAt} だけは作成時にentityから設定する。
 *
 * <p>{@code enabled}・{@code lastErrorCode}・{@code lastErrorMessage} も、retry上限の自動停止でschedulerが書く状態である
 * （停止は {@code enabled = 0} と停止理由codeの組）。読取後にschedulerが自動停止した行をentity経由で書き戻すと、
 * 停止を古い値で取り消してしまうため、同じくentity由来のUPDATEから除外する（review P3-9）。管理者のenable/disableは
 * {@code ReportScheduleMapper.updateEnabled} の指名列UPDATEだけで行う。{@code enabled} は作成時に無効（0）を
 * 保存するためINSERTは許可し、error列はINSERTからも除外する（作成時はNULL）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_report_schedule")
public class ReportSchedule extends BaseEntity {

    private String tenantId;
    private Long templateVersionId;
    private String cronExpression;
    private String timezoneId;
    /** 有効フラグ。作成時のみentityから設定し、以後は {@code updateEnabled} と自動停止のSQLだけが更新する。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer enabled;
    private String lockKey;
    /** 次回実行予定。作成時のみentityから設定し、以後はsuccess completionだけが進める。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime nextRunAt;
    /** 最終論理実行時刻。claim/completion SQLだけが更新する。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime lastRunAt;
    private String scopeOwnerType;
    private Long scopeOwnerId;
    private String organizationScopeJson;
    private String scopePolicyVersion;
    private String scopeHash;
    /** 失敗した論理実行のretry予定。failure completionだけが設定する。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime retryScheduledAt;
    /** claim中の論理実行時刻。成功時にnext_run_atを進める。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime processingLogicalRunAt;
    /** processing lease取得日時。stale時は同一logical monthを再claimする。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime processingClaimedAt;
    /** scheduler内部のclaim所有権token。API/diagnostic/entity更新から隠す。 */
    @JsonIgnore
    @ToString.Exclude
    @TableField(value = "processing_claim_token",
            insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String processingClaimToken;
    /** 連続失敗回数。failure completionがowner条件下のDB式で加算する。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer failureCount;
    /** 直近の失敗理由code（自動停止の理由を含む）。completion・自動停止のSQLだけが更新する。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String lastErrorCode;
    /** 直近の失敗理由の安全なmessage。completion・自動停止のSQLだけが更新する。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String lastErrorMessage;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
