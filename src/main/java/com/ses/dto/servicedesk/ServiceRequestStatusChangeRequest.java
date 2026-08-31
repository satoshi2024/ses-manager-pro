package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエストステータス変更リクエスト
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestStatusChangeRequest {

    @NotBlank(message = "変更後ステータスは必須です")
    private String toStatus;

    /** 変更理由（解決・終了・差戻し等の理由） */
    private String reason;
}
