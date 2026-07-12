package com.ses.dto.analytics;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Bench要員一覧の1行分DTO
 */
@Data
public class BenchEngineerDto {
    private Long engineerId;
    private String fullName;
    /** Bench経過日数(最終契約終了日、無ければ登録日からの経過日数) */
    private int benchDays;
    private BigDecimal expectedUnitPrice;
    private LocalDate availableDate;
    /** スキル名一覧(P1未導入・データ無しの場合は空リスト) */
    private List<String> skillNames;
}
