package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * サービスリクエスト内部用詳細DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestDto {

    private Long id;

    private String requestNo;

    private Long customerId;

    private String customerName;

    private Long contactId;

    private String contactName;

    private Long contractId;

    private String contractCode;

    private Long projectId;

    private String projectName;

    private Long engineerId;

    private String engineerName;

    private String category;

    private String priority;

    private String channel;

    private String subject;

    private String description;

    private Long ownerUserId;

    private String ownerUserName;

    private String status;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    private LocalDateTime reopenedAt;

    private Integer reopenCount;

    private Long portalUserId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private ServiceSlaClockDto currentSlaClock;

    private List<ServiceCommentDto> comments;

    private Integer csatScore;

    private String csatComment;
}
