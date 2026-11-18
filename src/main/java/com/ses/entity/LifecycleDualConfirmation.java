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
 * 二者確認の確認・差戻し（{@code t_lifecycle_dual_confirmation}、V195）。
 *
 * <p>提出ごとに1行（UNIQUE(tenant_id, submission_id)）。提出への参照は (tenant, task, 世代, revision, 提出ID, 証跡hash) の
 * 複合FKで束縛され、別の提出・版を指す確認行は作れない。追記専用
 * （{@link com.ses.mapper.LifecycleDualConfirmationMapper} とDB triggerが更新・削除を拒否する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_confirmation")
public class LifecycleDualConfirmation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** テナントID（提出・確認者と同じtenant） */
    private String tenantId;

    /** 案件ID (t_lifecycle_case.id) */
    private Long caseId;

    /** タスクID (t_lifecycle_task.id) */
    private Long taskId;

    /** 確認対象の提出のtask世代 */
    private Long taskGeneration;

    /** 確認対象の提出ID */
    private Long submissionId;

    /** 確認対象の提出版 */
    private Integer submissionRevision;

    /** 確認対象の証跡hash */
    private String evidenceSha256;

    /** 確認者ユーザーID */
    private Long confirmerUserId;

    /** 確認時の確認者ロール */
    private String confirmerRoleSnapshot;

    /** 確認時の全体認可epoch */
    private Long globalEpoch;

    /** 確認時の確認者actor epoch（snapshotとは比べず、S lock下の現在値を記録する） */
    private Long confirmerActorEpoch;

    /** 決定: CONFIRMED / REJECTED */
    private String decision;

    /** 確認・差戻しのコメント */
    private String decisionComment;

    /** 決定日時（DBの既定値） */
    private LocalDateTime decidedAt;
}
