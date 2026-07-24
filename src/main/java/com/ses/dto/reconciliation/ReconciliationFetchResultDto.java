package com.ses.dto.reconciliation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * fetch実行結果のサマリー。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationFetchResultDto {
    /** freeeから取得した件数（既存分含む）。 */
    private int fetchedCount;
    /** 新規保存件数（freee_deposit_id重複を除く）。 */
    private int newCount;
    /** 自動消込された件数。 */
    private int autoMatchedCount;
    /** 未消込のまま残った件数（候補提示・保留の合計）。 */
    private int pendingCount;
}
