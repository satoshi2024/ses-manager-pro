package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエスト属性更新リクエストDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestUpdateRequest {

    private String category;

    private String priority;

    private String channel;

    private String subject;

    private String description;

    private Long ownerUserId;

    private Long contactId;

    private Long contractId;

    private Long projectId;

    private Long engineerId;

    private Integer version;
}
