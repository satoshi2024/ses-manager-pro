package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二者確認の追記監査（{@code t_lifecycle_dual_event}、V195）。
 *
 * <p>FKを持たない監査ログ。拒否（DENIED）では他tenantのIDが要求されてもinsertが失敗しないよう、要求値は
 * requested_* 列に置き、case_id・task_generation 等は主体のtenant内で解決できた場合だけ入れる（計画 §5.8）。
 * 文書title・storage key・業務comment・clientが送ったhash・氏名は記録しない。追記専用
 * （{@link com.ses.mapper.LifecycleDualEventMapper} とDB triggerが更新・削除を拒否する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_event")
public class LifecycleDualEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主体のDB上のtenant */
    private String tenantId;

    /** 主体のtenant内で解決できた案件ID */
    private Long caseId;

    /** 主体のtenant内で解決できたタスクID */
    private Long taskId;

    /** 主体のtenant内で解決できたtask世代 */
    private Long taskGeneration;

    /** 主体のtenant内で解決できた提出ID */
    private Long submissionId;

    /** 要求されたタスクID（他tenantの値でもよい） */
    private Long requestedTaskId;

    /** 要求された提出ID（他tenantの値でもよい） */
    private Long requestedSubmissionId;

    /** 操作者ユーザーID */
    private Long actorUserId;

    /** 操作: SUBMIT / CONFIRM / REJECT */
    private String operation;

    /** 種別: SUBMITTED / SUPERSEDED / CONFIRMED / REJECTED / DENIED */
    private String eventType;

    /** 拒否理由コード（計画 §5.8） */
    private String reasonCode;

    /** 拒否時の応答status */
    private Integer httpStatus;

    /** 試行回数 */
    private Integer attemptCount;

    /** 発生日時（DBの既定値） */
    private LocalDateTime occurredAt;
}
