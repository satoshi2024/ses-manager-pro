package com.ses.dto.quotation;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
@Data
public class QuotationRemarkAppendRequest {
    @NotBlank
    private String additionalRemark;
}
