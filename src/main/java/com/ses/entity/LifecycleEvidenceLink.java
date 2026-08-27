package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ライフサイクルタスク証跡文書リンクエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_evidence_link")
public class LifecycleEvidenceLink implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 対象タスクID (t_lifecycle_task.id)
     */
    private Long taskId;

    /**
     * 文書ID (t_document.id)
     */
    private Long documentId;

    /**
     * 文書版ID (t_document_version.id)
     */
    private Long documentVersionId;

    /**
     * 検証日時
     */
    private LocalDateTime verifiedAt;

    /**
     * 検証実行者ID
     */
    private Long verifiedBy;

    /**
     * 備考
     */
    private String remarks;
}
