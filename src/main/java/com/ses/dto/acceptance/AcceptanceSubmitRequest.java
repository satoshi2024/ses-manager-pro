package com.ses.dto.acceptance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 検収提出リクエスト（契約×月の検収を未提出から作成/提出する）。 */
@Data
public class AcceptanceSubmitRequest {
    @NotNull(message = "契約は必須です")
    private Long contractId;
    @NotBlank(message = "対象月は必須です")
    @Pattern(regexp = "^\\d{4}-(?:0[1-9]|1[0-2])$", message = "対象月はYYYY-MM形式で指定してください")
    private String workMonth;
}
