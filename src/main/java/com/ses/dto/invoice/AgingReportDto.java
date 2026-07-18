package com.ses.dto.invoice;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * エイジング(債権年齢)レポート。顧客ごとの未回収残高を経過区分別に集計する。
 */
@Data
public class AgingReportDto {
    private LocalDate asOf;
    private List<Row> rows = new ArrayList<>();
    private Row total;

    @Data
    public static class Row {
        private Long customerId;
        private String customerName;
        /** 期限内(未経過)。 */
        private BigDecimal notDue = BigDecimal.ZERO;
        private BigDecimal d1to30 = BigDecimal.ZERO;
        private BigDecimal d31to60 = BigDecimal.ZERO;
        private BigDecimal d61to90 = BigDecimal.ZERO;
        private BigDecimal d91plus = BigDecimal.ZERO;
        /** 支払期限未設定。 */
        private BigDecimal noDueDate = BigDecimal.ZERO;
        /** 未請求（未送付）。売掛の期日区分には混ぜず別掲する。 */
        private BigDecimal unsent = BigDecimal.ZERO;
        /** 残高合計。 */
        private BigDecimal balance = BigDecimal.ZERO;

        public void add(String bucket, BigDecimal amount) {
            switch (bucket) {
                case "notDue" -> notDue = notDue.add(amount);
                case "d1to30" -> d1to30 = d1to30.add(amount);
                case "d31to60" -> d31to60 = d31to60.add(amount);
                case "d61to90" -> d61to90 = d61to90.add(amount);
                case "d91plus" -> d91plus = d91plus.add(amount);
                case "noDueDate" -> noDueDate = noDueDate.add(amount);
                case "unsent" -> unsent = unsent.add(amount);
                default -> { /* no-op */ }
            }
            balance = balance.add(amount);
        }
    }
}
