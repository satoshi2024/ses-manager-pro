package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.service.billing.MonthlyRevenueCalcService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * {@link MonthlyRevenueCalcService} の実装。純粋ロジック(DBアクセスなし)。
 */
@Service
public class MonthlyRevenueCalcServiceImpl implements MonthlyRevenueCalcService {

    @Override
    public MonthlyAmount calc(YearMonth month, List<Contract> contracts,
                              Map<Long, WorkRecord> confirmedByContractId) {
        long totalSales = 0;
        long totalProfit = 0;
        boolean hasActual = false;

        if (contracts != null) {
            for (Contract c : contracts) {
                if (!isTargetInMonth(c, month)) {
                    continue;
                }
                WorkRecord confirmed = confirmedByContractId == null ? null
                        : confirmedByContractId.get(c.getId());
                ContractAmount amount = resolveContractAmount(c, confirmed);
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
        if (confirmed != null && confirmed.getBillingAmount() != null) {
            BigDecimal sales = confirmed.getBillingAmount();
            BigDecimal cost = confirmed.getPaymentAmount() != null ? confirmed.getPaymentAmount() : BigDecimal.ZERO;
            return new ContractAmount(sales, cost, true);
        }
        BigDecimal sales = contract.getSellingPrice() != null ? contract.getSellingPrice() : BigDecimal.ZERO;
        BigDecimal cost = contract.getCostPrice() != null ? contract.getCostPrice() : BigDecimal.ZERO;
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
