package com.ses.dto.invoice;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BpPaymentListDto {
    private Long id;
    private Long workRecordId;
    private String workMonth;
    private String engineerName;
    private String projectName;
    private BigDecimal amount;
    private String status;
    private LocalDate paidDate;
    private Integer layerOrder;
    private String payeeCompanyName;
    private Long parentPaymentId;
}
