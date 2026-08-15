package com.ses.service.staffing;

import com.ses.dto.staffing.AllocationCardDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 仮配置シナリオの比較（T079 B2）。
 *
 * <p>scenario操作は {@code t_staffing_scenario} / {@code t_staffing_scenario_allocation} のみを
 * 更新し、実データ（t_allocation_plan・契約・提案）を一切変更しない（R3.3・design §5.4）。
 * 共有scenarioでも閲覧者のscopeを超えて要員を見せない（design §5.3）。
 * 粗利はHRロールでmask（null）される。
 */
public interface StaffingScenarioCompareService {

    /**
     * 指定scenarioの月別比較（供給FTE・稼働率・粗利）。
     * 閲覧者のscopeで要員をfilterして集計する（scenario経由のscope迂回を防ぐ）。
     */
    List<ScenarioMonthDto> compare(List<Long> scenarioIds, LocalDate asOf);

    /** scenario内の仮配置一覧（閲覧者のscopeで要員をfilter）。 */
    List<AllocationCardDto> visibleAllocations(Long scenarioId);

    /** シナリオ1月分の比較行。 */
    record ScenarioMonthDto(Long scenarioId, String scenarioName, YearMonth month,
                            int engineerCount, BigDecimal supplyFte, double utilizationRate,
                            BigDecimal grossProfit) {
    }
}
