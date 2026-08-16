package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.staffing.AllocationCardDto;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.service.staffing.StaffingScenarioCompareService;
import com.ses.service.staffing.StaffingScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 仮配置シナリオAPI（T079 B2）。
 * analyticsメニューのapi_prefix（/api/analytics）配下に置き、既存のmenu権限をそのまま使う。
 * scenario操作は実データ（t_allocation_plan/契約/提案）を一切変更しない（R3.3）。
 */
@RestController
@RequestMapping("/api/analytics/staffing-scenarios")
@RequiredArgsConstructor
public class StaffingScenarioApiController {

    private final StaffingScenarioService scenarioService;
    private final StaffingScenarioCompareService compareService;

    /** 可視なscenario一覧（ownerまたは共有）。 */
    @GetMapping
    public ApiResult<List<StaffingScenario>> list() {
        return ApiResult.success(scenarioService.listVisible());
    }

    /** scenario作成（owner=現在ユーザー）。 */
    @PostMapping
    public ApiResult<StaffingScenario> create(@RequestBody StaffingScenario scenario) {
        return ApiResult.success(scenarioService.create(scenario));
    }

    /** scenario更新（ownerのみ）。 */
    @PutMapping("/{id}")
    public ApiResult<StaffingScenario> update(@PathVariable Long id, @RequestBody StaffingScenario scenario) {
        scenario.setId(id);
        return ApiResult.success(scenarioService.update(scenario));
    }

    /** scenario削除（ownerのみ）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        scenarioService.delete(id);
        return ApiResult.success(true);
    }

    /** scenarioの仮配置一覧（閲覧者のscopeで要員をfilter）。 */
    @GetMapping("/{id}/allocations")
    public ApiResult<List<AllocationCardDto>> allocations(@PathVariable Long id) {
        return ApiResult.success(compareService.visibleAllocations(id));
    }

    /** 仮配置の保存（新規または上書き）。datesはISO日付のJSON配列。 */
    @PostMapping("/{id}/allocations")
    public ApiResult<StaffingScenarioAllocation> upsertAllocation(@PathVariable Long id,
                                                                  @RequestBody StaffingScenarioAllocation allocation) {
        allocation.setScenarioId(id);
        return ApiResult.success(scenarioService.upsertAllocation(allocation));
    }

    /** 仮配置の削除。 */
    @DeleteMapping("/{id}/allocations/{allocationId}")
    public ApiResult<Boolean> deleteAllocation(@PathVariable Long id, @PathVariable Long allocationId) {
        scenarioService.deleteAllocation(allocationId);
        return ApiResult.success(true);
    }

    /** 2案のscenario比較（稼働率・供給FTE・粗利）。粗利はHRでmask。 */
    @GetMapping("/compare")
    public ApiResult<List<StaffingScenarioCompareService.ScenarioMonthDto>> compare(
            @RequestParam List<Long> scenarioIds) {
        return ApiResult.success(compareService.compare(scenarioIds, LocalDate.now()));
    }
}
