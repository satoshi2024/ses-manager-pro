package com.ses.dto.bp;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class BpPaymentTreeDto {
    private Long id;
    private Long workRecordId;
    private Integer layerOrder;
    private String payeeCompanyName;
    private Long parentPaymentId;
    private BigDecimal amount;
    private String status;
    private LocalDate paidDate;
    private String remarks;
    private BigDecimal margin;
    private List<BpPaymentTreeDto> children;
}
