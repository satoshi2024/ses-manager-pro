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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MonthlyRevenueCalcServiceImpl.class);

    /**
     * 単価改定履歴のリゾルバ（任意依存）。本番では常に配線される。未配線は純ロジックの
     * 単体テスト（no-arg 生成）向けの緩和で、その場合はフォールバックに契約の現在単価を用いる。
     */
    @Autowired(required = false)
    private ContractPriceResolver priceResolver;

    @jakarta.annotation.PostConstruct
    void warnIfResolverMissing() {
        if (priceResolver == null) {
            log.warn("ContractPriceResolver が未配線です。集計フォールバックは契約の現在単価を用います"
                    + "（テスト専用の緩和。本番では配線されているべき）。");
        }
    }

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
                WorkRecord confirmed = confirmedByContractId == null ? null
                        : confirmedByContractId.get(c.getId());
                if (!isTargetInMonthWithActual(c, month, confirmed)) {
                    continue;
                }
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

    @Override
    public Map<Long, ContractAmount> resolveContractAmountBatch(List<Contract> contracts, Map<Long, WorkRecord> confirmedByContractId, YearMonth month) {
        Map<Long, ContractPriceResolver.ResolvedPrice> priceMap = priceResolver != null && contracts != null
                ? priceResolver.resolveBatch(contracts, month) : null;
        
        Map<Long, ContractAmount> result = new java.util.HashMap<>();
        if (contracts != null) {
            for (Contract c : contracts) {
                WorkRecord confirmed = confirmedByContractId != null ? confirmedByContractId.get(c.getId()) : null;
                ContractPriceResolver.ResolvedPrice rp = priceMap != null ? priceMap.get(c.getId()) : null;
                result.put(c.getId(), resolveWithPrice(c, confirmed, rp));
            }
        }
        return result;
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
        if ((StatusConstants.CONTRACT_ENDED.equals(contract.getStatus()) || "終了".equals(contract.getStatus())
                || StatusConstants.CONTRACT_CANCELLED.equals(contract.getStatus()) || "解約".equals(contract.getStatus()))
                && contract.getEndDate() == null) {
            return false;
        }
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        return !contract.getStartDate().isAfter(monthEnd)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(monthStart));
    }

    @Override
    public boolean isTargetInMonthWithActual(Contract contract, YearMonth month, WorkRecord confirmed) {
        boolean hasConfirmedRecord = confirmed != null && confirmed.getBillingAmount() != null;
        return hasConfirmedRecord || isTargetInMonth(contract, month);
    }
}
