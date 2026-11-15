package com.ses.service.ai;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * NF08 外部送信費用のJST日次・月次累計（費用上限ledger）の共通契約。
 * <ul>
 *   <li>{@link Kind#PERSISTENT}（{@link PersistentExternalSendCostLedger}）: DBの永続ledger。再起動・複数instanceを跨いで
 *       上限を守る。本番の上限はこの方式だけで守る（利用者決定Q-11、計画 §1.3）。予約はclaim transactionの中で、
 *       lock順 run → DAY → MONTH → entry に従って行う。</li>
 *   <li>{@link Kind#MEMORY}（{@link AiExternalSendBudgetLedger}）: プロセス内の計数。test/local専用で、再起動で0に戻る。</li>
 * </ul>
 * <p>どちらも、時計が戻った場合に累計を0へ戻さず、再承認（承認digestの変更）でも累計を引き継ぐ。
 * prodの起動時検査（{@code AiProductionSafetyValidator}）は {@code ai.external-send-cost-ledger=persistent} 以外を拒否する。</p>
 */
public interface ExternalSendCostLedger {

    String REASON_DAILY = "AI_EXTERNAL_SEND_DAILY_COST_LIMIT";
    String REASON_MONTHLY = "AI_EXTERNAL_SEND_MONTHLY_COST_LIMIT";

    /** ledgerの方式。 */
    enum Kind { MEMORY, PERSISTENT }

    Kind kind();

    /**
     * 現在のJST業務日・月の累計（予約中と精算済みの合計、円）。
     *
     * @param costScope 費用上限の適用範囲（provider単位。例: gemini）
     */
    Totals totals(String costScope);

    /** JST日・月の累計（円）。 */
    record Totals(LocalDate day, long dayYen, YearMonth month, long monthYen) {
    }
}
