package com.ses.dto.common;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** ステータス変更リクエスト。 */
@Data
public class StatusChangeRequest {
    @NotBlank(message = "ステータスは必須です")
    private String status;
}
