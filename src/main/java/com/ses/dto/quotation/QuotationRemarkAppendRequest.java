package com.ses.dto.quotation;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
@Data
public class QuotationRemarkAppendRequest {
    @NotBlank(message = "追記内容は必須です")
    @Size(max = 500, message = "追記は500文字以内で入力してください")
    private String additionalRemark;
}
