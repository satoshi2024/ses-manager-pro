package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 月次売上・粗利の共通集計サービス(全社共通口径)。
 *
 * <p>口径: 契約単位フォールバック + 準備中除外。
 * <ul>
 *   <li>対象契約: {@code status != '準備中'} かつ 契約期間が対象月と重なるもの。</li>
 *   <li>金額: 対象月に確定済 {@code t_work_record} があれば {@code billing_amount}/{@code payment_amount}
 *       (null は 0)、なければ契約の {@code selling_price}/{@code cost_price}。</li>
 * </ul>
 * Dashboard(チャート・トレンド・KPI)/月次売上Excel帳票/営業成績が本ロジックを共用する。
 */
public interface MonthlyRevenueCalcService {

    /**
     * 対象月の売上・粗利を集計する。
     *
     * @param month 対象月
     * @param contracts 呼び出し元が一括ロードした全契約
     * @param confirmedByContractId 当月確定実績 (contract_id -> record)
     * @return 集計結果
     */
    MonthlyAmount calc(YearMonth month, List<Contract> contracts, Map<Long, WorkRecord> confirmedByContractId);

    /**
     * 1契約分の当月金額を決定する(確定実績優先、なければ契約単価)。
     * 対象月に契約が該当するかの判定は呼び出し元の責務。
     *
     * @param contract 契約
     * @param confirmed 当月の確定実績(なければ null)
     * @return 売上・原価・実績由来フラグ
     */
    ContractAmount resolveContractAmount(Contract contract, WorkRecord confirmed);

    /**
     * 対象月を考慮した1契約分の金額決定（確定実績優先、なければ対象月に有効な単価）。
     * 単価改定リゾルバが配線されていれば期間別単価を、無ければ契約の現在単価をフォールバックに用いる。
     */
    ContractAmount resolveContractAmount(Contract contract, WorkRecord confirmed, YearMonth month);

    /**
     * 複数契約の一括金額決定（N+1回避）。
     */
    Map<Long, ContractAmount> resolveContractAmountBatch(List<Contract> contracts, Map<Long, WorkRecord> confirmedByContractId, YearMonth month);

    /**
     * 契約が対象月の集計対象か(準備中除外・期間重なり)。
     */
    boolean isTargetInMonth(Contract contract, YearMonth month);

    /** 月次集計結果。 */
    class MonthlyAmount {
        private final long sales;
        private final long profit;
        private final boolean hasActual;

        public MonthlyAmount(long sales, long profit, boolean hasActual) {
            this.sales = sales;
            this.profit = profit;
            this.hasActual = hasActual;
        }

        public long getSales() { return sales; }
        public long getProfit() { return profit; }
        /** 当月に確定実績由来の金額が1件以上あれば true。 */
        public boolean isHasActual() { return hasActual; }
    }

    /** 1契約分の金額決定結果。 */
    class ContractAmount {
        private final BigDecimal sales;
        private final BigDecimal cost;
        private final boolean actual;

        public ContractAmount(BigDecimal sales, BigDecimal cost, boolean actual) {
            this.sales = sales;
            this.cost = cost;
            this.actual = actual;
        }

        public BigDecimal getSales() { return sales; }
        public BigDecimal getCost() { return cost; }
        public BigDecimal getProfit() { return sales.subtract(cost); }
        /** 確定実績由来か(true=実績、false=契約単価の見込み)。 */
        public boolean isActual() { return actual; }
    }
}
