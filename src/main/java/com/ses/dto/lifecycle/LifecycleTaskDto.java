package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ライフサイクルタスク詳細・一覧DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleTaskDto {

    private Long id;
    private Long caseId;
    private String taskCode;
    private String taskName;
    private String description;
    private LocalDate dueDate;
    private Long assigneeUserId;
    private String assigneeRole;
    private String assigneeNameSnapshot;
    private Integer isMandatory;
    private Integer isBlocking;
    private String evidenceType;
    private Integer isEngineerVisible;
    private String status;
    private LocalDateTime completedAt;
    private Long completedBy;
    private String completedByName;
    private String completionComment;
    private String evidenceDataJson;
    private Long approvalRequestId;
    private Integer version;

    /**
     * 先行タスクコード一覧
     */
    private List<String> predecessorTaskCodes;

    /**
     * 着手可能フラグ (先行タスクが全件完了しているか)
     */
    private boolean readyToStart;

    /**
     * 期限超過フラグ
     */
    private boolean overdue;

    /**
     * 添付された証跡文書ID
     */
    private Long documentId;
}
