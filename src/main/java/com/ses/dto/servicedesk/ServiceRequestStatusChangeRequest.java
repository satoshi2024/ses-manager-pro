package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /** 読み取り時点のサービスリクエストversion。HTTP状態変更では必須。 */
    @NotNull(message = "サービスリクエストversionは必須です")
    private Integer version;

    private Long organizationId;

    private Long legalEntityId;
}
