package com.ses.dto.invoice;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvoicePaymentResponse {
    private Long id;
    private Long invoiceId;
    private BigDecimal amount;
    private BigDecimal fee;
    private LocalDate paidDate;
    private String remarks;
}