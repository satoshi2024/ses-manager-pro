package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ライフサイクルタスクインスタンスエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_task")
public class LifecycleTask extends BaseEntity {

    /**
     * 所属案件ID (t_lifecycle_case.id)
     */
    private Long caseId;

    /**
     * タスクコード
     */
    private String taskCode;

    /**
     * タスク名
     */
    private String taskName;

    /**
     * 説明・手順
     */
    private String description;

    /**
     * 算出された期日 (anchorDate + relativeDueDays)
     */
    private LocalDate dueDate;

    /**
     * 解決された担当ユーザーID
     */
    private Long assigneeUserId;

    /**
     * 担当ロール (ROLE解決時)
     */
    private String assigneeRole;

    /**
     * 担当者名スナップショット
     */
    private String assigneeNameSnapshot;

    /**
     * 必須区分 (1: 必須, 0: 任意)
     */
    @Builder.Default
    private Integer isMandatory = 1;

    /**
     * 完了阻害区分 (1: 案件完了を阻害, 0: 非阻害)
     */
    @Builder.Default
    private Integer isBlocking = 1;

    /**
     * 証跡種別 (NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK)
     */
    @Builder.Default
    private String evidenceType = "NONE";

    /**
     * 本人公開区分 (1: 本人公開, 0: 内部限定)
     */
    @Builder.Default
    private Integer isEngineerVisible = 1;

    /**
     * ステータス (PENDING, IN_PROGRESS, ON_HOLD, COMPLETED, WAIVED)
     */
    @Builder.Default
    private String status = "PENDING";

    /**
     * 完了日時
     */
    private LocalDateTime completedAt;

    /**
     * 完了実行者ID
     */
    private Long completedBy;

    /**
     * 完了時コメント
     */
    private String completionComment;

    /**
     * 証跡メタデータJSON
     */
    private String evidenceDataJson;

    /**
     * 例外免除時の承認申請ID (ApprovalEngine連携)
     */
    private Long approvalRequestId;

    /**
     * 楽観ロックバージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;

    /**
     * 作成者
     */
    private Long createdBy;

    /**
     * 更新者
     */
    private Long updatedBy;
}
