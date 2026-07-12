package com.ses.dto.invoice;

import lombok.Data;
import java.time.LocalDate;

@Data
public class InvoiceStatusUpdateRequest {
    private String status;
    private LocalDate paidDate;
}
