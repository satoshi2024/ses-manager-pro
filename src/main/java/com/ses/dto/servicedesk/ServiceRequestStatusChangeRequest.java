package com.ses.dto.servicedesk;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * サービスリクエストステータス変更リクエストDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestStatusChangeRequest {

    @NotBlank(message = "変更後ステータスは必須です")
    private String toStatus;

    private String reason;

    private Integer version;
}
