package com.ses.dto.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 月次売上レポートExcel出力用DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyRevenueDto {

    /** 対象月ラベル(例: "2026年4月") */
    private String label;

    /** 売上 */
    private long sales;

    /** 粗利 */
    private long profit;

    /** 実績確定済みか(true: 実績, false: 見込み) */
    private boolean isActual;
}
