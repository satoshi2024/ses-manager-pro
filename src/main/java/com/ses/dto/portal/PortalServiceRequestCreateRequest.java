package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顧客ポータル サービスリクエスト起票リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalServiceRequestCreateRequest {

    /** 関連契約ID (任意) */
    private Long contractId;

    /** 関連案件ID (任意) */
    private Long projectId;

    /** 関連要員ID (任意) */
    private Long engineerId;

    /** 関連担当者ID (任意) */
    private Long contactId;

    /** カテゴリ (CONTRACT, BILLING, ATTENDANCE, QUALITY, SYSTEM, OTHER) */
    @NotBlank(message = "カテゴリは必須です")
    private String category;

    /** 優先度 (P0, P1, P2, P3) */
    @NotBlank(message = "優先度は必須です")
    private String priority;

    /** 件名 */
    @NotBlank(message = "件名は必須です")
    @Size(max = 255, message = "件名は255文字以内で入力してください")
    private String subject;

    /** 詳細内容 */
    @NotBlank(message = "詳細内容は必須です")
    private String description;
}
