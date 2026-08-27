package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ライフサイクルイベントDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleEventDto {

    private Long id;
    private Long caseId;
    private Long taskId;
    private String taskName;
    private String eventType;
    private Long actorUserId;
    private String actorName;
    private String actorRoleSnapshot;
    private String beforeState;
    private String afterState;
    private String detailsJson;
    private LocalDateTime occurredAt;
}
