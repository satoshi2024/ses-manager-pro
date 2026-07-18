package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.service.billing.ContractPriceResolver;
import com.ses.service.billing.MonthlyRevenueCalcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * {@link MonthlyRevenueCalcService} の実装。純粋ロジック(DBアクセスは任意のリゾルバ経由のみ)。
 */
@Service
public class MonthlyRevenueCalcServiceImpl implements MonthlyRevenueCalcService {

    // 単価改定履歴のリゾルバ（任意）。未配線（純ロジックのテスト等）では契約の現在単価を用いる。
    @Autowired(required = false)
    private ContractPriceResolver priceResolver;

    @Override
    public MonthlyAmount calc(YearMonth month, List<Contract> contracts,
                              Map<Long, WorkRecord> confirmedByContractId) {
        long totalSales = 0;
        long totalProfit = 0;
        boolean hasActual = false;

        // 対象月に有効な単価を一括解決（フォールバック経路のみで使用。N+1回避）。
        Map<Long, ContractPriceResolver.ResolvedPrice> priceMap = priceResolver != null && contracts != null
                ? priceResolver.resolveBatch(contracts, month) : null;

        if (contracts != null) {
            for (Contract c : contracts) {
                if (!isTargetInMonth(c, month)) {
                    continue;
                }
                WorkRecord confirmed = confirmedByContractId == null ? null
                        : confirmedByContractId.get(c.getId());
                ContractAmount amount = resolveWithPrice(c, confirmed,
                        priceMap != null ? priceMap.get(c.getId()) : null);
                totalSales += amount.getSales().longValue();
                totalProfit += amount.getProfit().longValue();
                if (amount.isActual()) {
                    hasActual = true;
                }
            }
        }
        return new MonthlyAmount(totalSales, totalProfit, hasActual);
    }

    @Override
    public ContractAmount resolveContractAmount(Contract contract, WorkRecord confirmed) {
        return resolveWithPrice(contract, confirmed, null);
    }

    @Override
    public ContractAmount resolveContractAmount(Contract contract, WorkRecord confirmed, YearMonth month) {
        ContractPriceResolver.ResolvedPrice rp = priceResolver != null ? priceResolver.resolve(contract, month) : null;
        return resolveWithPrice(contract, confirmed, rp);
    }

    /** 確定実績があればそれを優先し、無ければ与えられた解決済み単価（null なら契約の現在単価）を用いる。 */
    private ContractAmount resolveWithPrice(Contract contract, WorkRecord confirmed,
                                            ContractPriceResolver.ResolvedPrice resolved) {
        if (confirmed != null && confirmed.getBillingAmount() != null) {
            BigDecimal sales = confirmed.getBillingAmount();
            BigDecimal cost = confirmed.getPaymentAmount() != null ? confirmed.getPaymentAmount() : BigDecimal.ZERO;
            return new ContractAmount(sales, cost, true);
        }
        BigDecimal sales;
        BigDecimal cost;
        if (resolved != null) {
            sales = resolved.getSellingPrice() != null ? resolved.getSellingPrice() : BigDecimal.ZERO;
            cost = resolved.getCostPrice() != null ? resolved.getCostPrice() : BigDecimal.ZERO;
        } else {
            sales = contract.getSellingPrice() != null ? contract.getSellingPrice() : BigDecimal.ZERO;
            cost = contract.getCostPrice() != null ? contract.getCostPrice() : BigDecimal.ZERO;
        }
        return new ContractAmount(sales, cost, false);
    }

    @Override
    public boolean isTargetInMonth(Contract contract, YearMonth month) {
        if (contract == null || contract.getStartDate() == null
                || StatusConstants.CONTRACT_PREPARING.equals(contract.getStatus())) {
            return false;
        }
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        return !contract.getStartDate().isAfter(monthEnd)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(monthStart));
    }
}
