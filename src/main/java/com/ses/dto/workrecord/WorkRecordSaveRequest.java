package com.ses.dto.workrecord;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WorkRecordSaveRequest {
    @NotNull(message = "契約IDは必須です")
    private Long contractId;
    @NotBlank(message = "対象月は必須です")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "対象月はYYYY-MM形式で指定してください")
    private String workMonth;
    @NotNull(message = "実績時間は必須です")
    @DecimalMin(value = "0", message = "実績時間は0以上を指定してください")
    @DecimalMax(value = "999.9", message = "実績時間は999.9以下を指定してください")
    private BigDecimal actualHours;
    @Size(max = 500, message = "備考は500文字以内で入力してください")
    private String remarks;
}

