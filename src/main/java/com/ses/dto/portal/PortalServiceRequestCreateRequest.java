package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顧客ポータル専用のリクエスト起票リクエストDTO (WIP-8)。
 * 社内内部情報（ownerUserId等）は受け付けない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceRequestCreateRequest {

    @NotBlank(message = "件名は必須です")
    private String subject;

    @NotBlank(message = "内容は必須です")
    private String description;

    @NotBlank(message = "カテゴリは必須です")
    private String category;

    @NotBlank(message = "優先度は必須です")
    private String priority;

    private Long contactId;
    private Long contractId;
    private Long projectId;
    private Long engineerId;
}
