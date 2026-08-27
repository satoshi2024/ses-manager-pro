package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエスト作成リクエストDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestCreateRequest {

    @NotNull(message = "顧客IDは必須です")
    private Long customerId;

    private Long contactId;

    private Long contractId;

    private Long projectId;

    private Long engineerId;

    @NotBlank(message = "カテゴリは必須です")
    private String category;

    @NotBlank(message = "優先度は必須です")
    private String priority;

    private String channel;

    @NotBlank(message = "件名は必須です")
    private String subject;

    @NotBlank(message = "詳細内容は必須です")
    private String description;

    private Long ownerUserId;
}
