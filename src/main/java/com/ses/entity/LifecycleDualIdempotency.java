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
 * 二者確認の冪等性（{@code t_lifecycle_dual_idempotency}、V195）。
 *
 * <p>一意key (tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key)。idempotency_key は
 * {@code ascii_bin} で大文字小文字を区別する（計画 §5.7）。追記専用
 * （{@link com.ses.mapper.LifecycleDualIdempotencyMapper} とDB triggerが更新・削除を拒否する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_idempotency")
public class LifecycleDualIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** テナントID */
    private String tenantId;

    /** 案件ID (t_lifecycle_case.id) */
    private Long caseId;

    /** タスクID (t_lifecycle_task.id) */
    private Long taskId;

    /** 検索時のlock済みtask行の世代 */
    private Long taskGeneration;

    /** 操作: SUBMIT / CONFIRM（差戻しはCONFIRM） */
    private String operation;

    /** 要求者ユーザーID */
    private Long actorUserId;

    /** Idempotency-Key */
    private String idempotencyKey;

    /** 要求の正規化hash */
    private String requestSha256;

    /** 結果の提出ID */
    private Long submissionId;

    /** 結果の確認ID（CONFIRMのみ） */
    private Long confirmationId;

    /** 作成日時（DBの既定値） */
    private LocalDateTime createdAt;
}
