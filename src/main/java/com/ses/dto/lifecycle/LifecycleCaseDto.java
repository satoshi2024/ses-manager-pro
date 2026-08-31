package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ライフサイクル案件DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleCaseDto {

    private Long id;
    private String caseNo;
    private String lifecycleType;
    private Long engineerId;
    private String engineerName;
    private String employmentType;
    private Long templateId;
    private String templateName;
    private Integer templateVersion;
    private LocalDate anchorDate;
    private String status;
    private String title;
    private String remarks;
    private Long applicantUserId;
    private String applicantName;
    private String engineerSnapshotJson;
    private LocalDateTime completedAt;
    private Long completedBy;
    private String completedByName;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 進捗メトリクス
     */
    private int totalTasks;
    private int completedTasks;
    private int pendingTasks;
    private int blockingUncompletedCount;
    private int progressPercent;

    /**
     * 配下のタスク一覧
     */
    private List<LifecycleTaskDto> tasks;

    /**
     * 不変イベント台帳。主体/チャネルは常に解決済みenum値で返す。
     */
    private List<LifecycleEventDto> events;
}
