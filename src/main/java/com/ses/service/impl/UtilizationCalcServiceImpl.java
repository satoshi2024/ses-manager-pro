package com.ses.service.impl;

import com.ses.common.constant.RenewalState;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.service.UtilizationCalcService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 稼働率の共通口径サービス実装(純粋ロジック、DBアクセス無し)。
 *
 * @see UtilizationCalcService 口径の定義
 */
@Service
public class UtilizationCalcServiceImpl implements UtilizationCalcService {

    @Override
    public boolean isActiveInMonth(List<Contract> engineerContracts, YearMonth month, boolean assumeRenew) {
        if (engineerContracts == null || engineerContracts.isEmpty()) {
            return false;
        }
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        for (Contract c : engineerContracts) {
            String status = c.getStatus();
            if (!StatusConstants.CONTRACT_ACTIVE.equals(status)
                    && !StatusConstants.CONTRACT_PREPARING.equals(status)
                    && !StatusConstants.CONTRACT_ENDED.equals(status)) {
                continue;
            }
            if (c.getStartDate() == null || c.getStartDate().isAfter(monthEnd)) {
                continue;
            }
            if (c.getEndDate() == null) {
                return true;
            }
            if (!c.getEndDate().isBefore(monthStart)) {
                return true;
            }
            // 終了日が対象月より前でも、自動更新契約は更新継続とみなす(将来月の見込み)。
            if ((StatusConstants.CONTRACT_ACTIVE.equals(status) || StatusConstants.CONTRACT_PREPARING.equals(status))
                    && assumeRenew
                    && Integer.valueOf(1).equals(c.getAutoRenew())
                    && !RenewalState.DECISION_END.equals(c.getRenewalDecision())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public UtilizationSnapshot calc(YearMonth month, List<Engineer> engineers,
                                    Map<Long, List<Contract>> contractsByEngineer, boolean assumeRenew) {
        if (engineers == null || engineers.isEmpty()) {
            return new UtilizationSnapshot(0, 0, 0, 0.0);
        }
        Map<Long, List<Contract>> byEngineer = contractsByEngineer != null ? contractsByEngineer : Collections.emptyMap();

        int totalCount = engineers.size();
        int workingCount = 0;
        for (Engineer e : engineers) {
            List<Contract> engContracts = byEngineer.getOrDefault(e.getId(), Collections.emptyList());
            if (isActiveInMonth(engContracts, month, assumeRenew)) {
                workingCount++;
            }
        }

        int benchCount = Math.max(0, totalCount - workingCount);
        double rate = (double) workingCount / totalCount * 100.0;
        return new UtilizationSnapshot(workingCount, benchCount, totalCount, Math.round(rate * 10.0) / 10.0);
    }
}
