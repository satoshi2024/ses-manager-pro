package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二者確認の提出に含めた文書版（{@code t_lifecycle_dual_submission_document}、V195）。
 *
 * <p>主キーは (submission_id, document_id) の複合keyのため {@code @TableId} を置かない（ID指定の汎用メソッドは生成されない）。
 * 追記専用（{@link com.ses.mapper.LifecycleDualSubmissionDocumentMapper} とDB triggerが更新・削除を拒否する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_submission_document")
public class LifecycleDualSubmissionDocument {

    /** テナントID（提出・文書版と同じtenant。複合FKで固定される） */
    private String tenantId;

    /** 提出ID (t_lifecycle_dual_submission.id) */
    private Long submissionId;

    /** 文書ID (t_document.id) */
    private Long documentId;

    /** 文書版ID (t_document_version.id) */
    private Long documentVersionId;

    /** 提出時の版番号 */
    private Integer versionNo;

    /** 提出時の版のSHA-256 */
    private String sha256;

    /** 提出時の文書の証跡利用可否epoch */
    private Long evidenceEligibilityEpoch;
}
