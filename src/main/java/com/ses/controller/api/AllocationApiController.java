package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.staffing.AllocationCardDto;
import com.ses.entity.AllocationPlan;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.staffing.AllocationPlanService;
import com.ses.service.staffing.StaffingBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 要員配置計画API（T077 A1）。
 * 要員メニューのapi_prefix（/api/engineers）配下に置き、DataScope（担当要員）を適用する。
 * 過配賦になる操作はservice層が拒否し、画面はカードを元の位置へ戻す（design §3）。
 */
@RestController
@RequestMapping("/api/engineers")
@RequiredArgsConstructor
public class AllocationApiController {

    private final AllocationPlanService allocationService;
    private final StaffingBoardService boardService;
    private final DataScopeService dataScopeService;
    private final ProjectPositionMapper positionMapper;

    /** 要員の配置タイムライン（破棄済みを除く）。 */
    @GetMapping("/{engineerId}/allocations")
    public ApiResult<List<AllocationCardDto>> timeline(@PathVariable Long engineerId) {
        dataScopeService.assertAllowedEngineer(engineerId);
        return ApiResult.success(boardService.engineerTimeline(engineerId));
    }

    /**
     * 配置の下書き保存（新規または既存下書きの更新）。過配賦は例外理由と承認が必須。
     * 入力検証はservice層で行う（engineerIdはパスから設定されるためentityの@Validは使わない）。
     * position_idの案件scopeも検証する（S12-R1-P1-06: cross-scope write防止）。
     */
    @PostMapping("/{engineerId}/allocations")
    public ApiResult<AllocationCardDto> saveDraft(@PathVariable Long engineerId,
                                                  @RequestBody AllocationPlan allocation) {
        dataScopeService.assertAllowedEngineer(engineerId);
        assertAllowedPosition(allocation.getPositionId());
        allocation.setEngineerId(engineerId);
        return ApiResult.success(boardService.card(allocationService.saveDraft(allocation).getId()));
    }

    /** 配置の確定（下書き→確定・状態CAS）。過配賦はロック付き再検証し、例外は承認済みを要求。 */
    @PostMapping("/{engineerId}/allocations/{allocationId}/confirm")
    public ApiResult<AllocationCardDto> confirm(@PathVariable Long engineerId, @PathVariable Long allocationId) {
        dataScopeService.assertAllowedEngineer(engineerId);
        return ApiResult.success(boardService.card(allocationService.confirm(allocationId).getId()));
    }

    /** 配置の破棄（下書き|確定→破棄・状態CAS）。実契約由来（actual）は破棄できない。 */
    @PostMapping("/{engineerId}/allocations/{allocationId}/discard")
    public ApiResult<Boolean> discard(@PathVariable Long engineerId, @PathVariable Long allocationId) {
        dataScopeService.assertAllowedEngineer(engineerId);
        allocationService.discard(allocationId);
        return ApiResult.success(true);
    }

    /** 配置の変更（確定→破棄＋新区間を確定。失敗時は変更前の区間へ戻る）。 */
    @PutMapping("/{engineerId}/allocations/{allocationId}")
    public ApiResult<AllocationCardDto> revise(@PathVariable Long engineerId, @PathVariable Long allocationId,
                                               @RequestBody AllocationPlan newAllocation) {
        dataScopeService.assertAllowedEngineer(engineerId);
        assertAllowedPosition(newAllocation.getPositionId());
        return ApiResult.success(boardService.card(allocationService.revise(allocationId, newAllocation).getId()));
    }

    /** position_idが指定された場合、その案件がDataScope内か検証する（cross-scope write防止）。 */
    private void assertAllowedPosition(Long positionId) {
        if (positionId == null) {
            return;
        }
        com.ses.entity.ProjectPosition position = positionMapper.selectById(positionId);
        if (position == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.staffing.positionNotFound");
        }
        dataScopeService.assertAllowedProject(position.getProjectId());
    }
}
