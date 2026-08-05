package com.ses.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 注文作成・更新リクエスト。 */
@Data
public class SalesOrderSaveRequest {

    private Long customerId;
    private Long contactId;
    private Long quotationId;
    private String customerPoNo;
    @NotNull(message = "注文日は必須です")
    private LocalDate orderDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String paymentTerms;

    @NotNull(message = "注文明細は必須です")
    private List<Line> lines;

    @Data
    public static class Line {
        private Long id;
        @NotNull(message = "要員は必須です")
        private Long engineerId;
        private Long projectId;
        @PositiveOrZero(message = "単価は0以上で入力してください")
        @NotNull(message = "単価は必須です")
        private BigDecimal unitPrice;
        private BigDecimal settlementMin;
        private BigDecimal settlementMax;
        private String remarks;
    }
}
