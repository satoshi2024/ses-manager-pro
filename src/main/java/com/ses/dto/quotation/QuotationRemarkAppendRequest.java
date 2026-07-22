package com.ses.dto.quotation;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
@Data
public class QuotationRemarkAppendRequest {
    @NotBlank(message = "追記内容は必須です")
    @Size(max = 500, message = "追記は500文字以内で入力してください")
    @JsonAlias({"additional", "additionalRemark"})
    private String additionalRemark;
}
