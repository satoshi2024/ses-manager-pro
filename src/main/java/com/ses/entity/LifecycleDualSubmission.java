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
 * 二者確認の提出（{@code t_lifecycle_dual_submission}、V195）。
 *
 * <p>追記専用。同じtask・世代の再提出は revision を進めた新しい行で表し、既存行は更新しない
 * （{@link com.ses.mapper.LifecycleDualSubmissionMapper} とDB triggerが更新・削除を拒否する）。
 * epoch列は提出時のsnapshotであり、anchor行のepochとは別物である（確認時にsnapshotと現在値を比べる。計画 §5.5）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_submission")
public class LifecycleDualSubmission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** テナントID（案件・提出者と同じtenant。複合FKで固定される） */
    private String tenantId;

    /** 案件ID (t_lifecycle_case.id) */
    private Long caseId;

    /** タスクID (t_lifecycle_task.id) */
    private Long taskId;

    /** caseの要員ID（attempt内で一致を確認する） */
    private Long engineerId;

    /** 提出時のtask世代 */
    private Long taskGeneration;

    /** 同一task・世代内の提出版（1から） */
    private Integer revision;

    /** 提出者ユーザーID */
    private Long submitterUserId;

    /** 提出時の提出者ロール */
    private String submitterRoleSnapshot;

    /** 提出時の全体認可epoch */
    private Long globalEpoch;

    /** 提出時の提出者actor epoch */
    private Long submitterActorEpoch;

    /** 提出時の要員scope epoch */
    private Long engineerScopeEpoch;

    /** 提出時のtask行のversion */
    private Integer taskVersionSnapshot;

    /** 証跡の正規化JSON */
    private String evidenceCanonicalJson;

    /** 証跡の正規化JSONのSHA-256 */
    private String evidenceSha256;

    /** 要求の正規化hash */
    private String requestSha256;

    /** 提出時の業務コメント */
    private String businessComment;

    /** 作成日時（DBの既定値） */
    private LocalDateTime createdAt;
}
