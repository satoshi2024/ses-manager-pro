package com.ses.dto.order;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 注文一覧DTO（顧客名・要員名をJOINして表示用に整形する）。 */
@Data
public class SalesOrderListDto {
    private Long id;
    private String orderNo;
    private String customerPoNo;
    private Long customerId;
    private String customerName;
    private LocalDate orderDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private BigDecimal totalAmountSnapshot;
    private Long engineerCount;
    private Long quotationId;
}
