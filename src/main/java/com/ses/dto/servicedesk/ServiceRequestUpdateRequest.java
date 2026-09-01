package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエスト更新リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestUpdateRequest {

    private Long contactId;

    private Long contractId;

    private Long projectId;

    private Long engineerId;

    @NotBlank(message = "カテゴリは必須です")
    private String category;

    @NotBlank(message = "優先度は必須です")
    private String priority;

    @NotBlank(message = "件名は必須です")
    @Size(max = 255, message = "件名は255文字以内で入力してください")
    private String subject;

    @NotBlank(message = "詳細内容は必須です")
    private String description;

    private Long ownerUserId;

    /** 読み取り時点のversion。 */
    private Integer version;
}
