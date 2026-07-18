package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 対象月に有効な契約単価を解決する。履歴が無い契約は契約の現在単価を返す。
 */
public interface ContractPriceResolver {

    ResolvedPrice resolve(Contract contract, YearMonth month);

    /** 集計の月ループで N+1 を避ける一括版。 */
    Map<Long, ResolvedPrice> resolveBatch(List<Contract> contracts, YearMonth month);

    /**
     * 履歴リストを呼び出し元が渡す純ロジック版（テスト容易化）。
     * apply_from_month <= month の最大行を採用、無ければ契約の現在単価。
     */
    static ResolvedPrice resolveFrom(Contract contract, YearMonth month, List<ContractPriceHistory> histories) {
        ContractPriceHistory best = null;
        if (histories != null) {
            for (ContractPriceHistory h : histories) {
                if (h.getApplyFromMonth() == null) {
                    continue;
                }
                YearMonth applyFrom = YearMonth.parse(h.getApplyFromMonth());
                if (!applyFrom.isAfter(month)) {
                    if (best == null || YearMonth.parse(best.getApplyFromMonth()).isBefore(applyFrom)) {
                        best = h;
                    }
                }
            }
        }
        if (best != null) {
            return new ResolvedPrice(best.getSellingPrice(), best.getCostPrice(), true);
        }
        BigDecimal selling = contract.getSellingPrice() != null ? contract.getSellingPrice() : BigDecimal.ZERO;
        BigDecimal cost = contract.getCostPrice() != null ? contract.getCostPrice() : BigDecimal.ZERO;
        return new ResolvedPrice(selling, cost, false);
    }

    /** 解決された単価。 */
    class ResolvedPrice {
        private final BigDecimal sellingPrice;
        private final BigDecimal costPrice;
        private final boolean fromHistory;

        public ResolvedPrice(BigDecimal sellingPrice, BigDecimal costPrice, boolean fromHistory) {
            this.sellingPrice = sellingPrice;
            this.costPrice = costPrice;
            this.fromHistory = fromHistory;
        }

        public BigDecimal getSellingPrice() { return sellingPrice; }
        public BigDecimal getCostPrice() { return costPrice; }
        public boolean isFromHistory() { return fromHistory; }
    }
}
