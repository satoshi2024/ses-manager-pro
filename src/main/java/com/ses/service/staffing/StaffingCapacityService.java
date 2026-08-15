package com.ses.service.staffing;

import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.service.UtilizationCalcService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 需給集計（capacity aggregation）。
 *
 * <p>月別FTE換算はdesign §5.2の確定済み表どおり:
 * 月内の<b>稼働可能日数</b>（m_work_calendar基準・承認済休暇控除後）に対する在籍日数比 × 配賦率。
 * 休暇は稼働可能日数を減らすが、契約FTE（配賦率）自体は自動変更しない（capで維持）。
 *
 * <ul>
 *   <li>plan/actualの二重計上は<b>SQLのWHERE句</b>で排他する（source_contract_id IS NULL/NOT NULL。
 *       design §5.4。memory filterにしない）。</li>
 *   <li>稼働率の口径は {@link UtilizationCalcService} に委譲し、dashboard KPIと同一値にする（design §5.1）。</li>
 *   <li>更新済契約（autoRenew=1∧assumeRenew∧renewalDecision≠'END'）は終了日以降もactualとして延長。
 *       {@code UtilizationCalcService.isActiveInMonth} と同一規則。</li>
 *   <li>退場予定（engineer.status='退場予定'）は最終契約終了日以降の供給を0にする。</li>
 *   <li>呼出側がscope適用済みのengineer集合を渡す（SQLはengineer_id INで境界化）。</li>
 * </ul>
 */
public interface StaffingCapacityService {

    /** 要員×月の供給サマリ。 */
    record EngineerMonthSupply(Long engineerId, YearMonth month,
                               int workingDays, int leaveDays, int availableDays,
                               BigDecimal actualFte, BigDecimal planFte) {

        public EngineerMonthSupply {
            actualFte = actualFte == null ? BigDecimal.ZERO : actualFte;
            planFte = planFte == null ? BigDecimal.ZERO : planFte;
        }

        /** 供給FTE合計（actual + plan、二重計上なし）。 */
        public BigDecimal totalFte() {
            return actualFte.add(planFte);
        }
    }

    /** 要員×月の供給。actual（契約由来・source_contract_id NOT NULL）とplan（IS NULL）を分けて返す。 */
    EngineerMonthSupply supply(Engineer engineer, YearMonth month, LocalDate asOf);

    /** 複数要員×期間の供給。月順・要員順で返す。 */
    List<EngineerMonthSupply> supplyBatch(List<Engineer> engineers, YearMonth from, YearMonth to, LocalDate asOf);

    /**
     * 稼働率（%）。{@link UtilizationCalcService#calc} に委譲し、dashboard KPIと同一口径を保証する。
     * 対象契約/有効判定/自動更新の扱いはUtilizationCalcServiceが唯一の正。
     */
    UtilizationCalcService.UtilizationSnapshot utilization(YearMonth month, List<Engineer> engineers,
                                                           Map<Long, List<Contract>> contractsByEngineer,
                                                           boolean assumeRenew);
}
