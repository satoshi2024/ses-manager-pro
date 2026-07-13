package com.ses.dto.analytics;

import lombok.Data;

import java.time.LocalDate;

/**
 * 稼動率推移の集計専用の軽量射影（要員IDと契約期間のみ）。
 * Contractエンティティ全体（単価・備考等）を丸ごと読み込む代わりに
 * 必要な列だけを取得し、大量データ時のメモリ使用量を抑える。
 * status IN ('稼動中','終了') の絞り込みはSQL側で行う。
 */
@Data
public class ContractDateRangeDto {
    private Long engineerId;
    private LocalDate startDate;
    private LocalDate endDate;
}
