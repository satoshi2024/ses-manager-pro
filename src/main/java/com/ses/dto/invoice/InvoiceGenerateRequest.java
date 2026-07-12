package com.ses.dto.invoice;

import lombok.Data;

@Data
public class InvoiceGenerateRequest {
    private Long customerId;
    private String billingMonth;
}
