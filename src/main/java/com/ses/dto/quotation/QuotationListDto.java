package com.ses.dto.quotation;

import com.ses.entity.Quotation;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QuotationListDto extends Quotation {
    private String customerName;
}
