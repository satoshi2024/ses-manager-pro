package com.ses.dto.acceptance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 検収提出リクエスト（契約×月の検収を未提出から作成/提出する）。 */
@Data
public class AcceptanceSubmitRequest {
    @NotNull(message = "契約は必須です")
    private Long contractId;
    @NotBlank(message = "対象月は必須です")
    private String workMonth;
}
