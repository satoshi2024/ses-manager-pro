package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.staffing.PositionBoardDto;
import com.ses.entity.ProjectPosition;
import com.ses.service.ProjectService;
import com.ses.service.security.DataScopeService;
import com.ses.service.staffing.PositionService;
import com.ses.service.staffing.StaffingBoardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 案件ポジション（募集枠）API（T077 A1）。
 * 案件メニューのapi_prefix（/api/projects）配下に置き、DataScope（担当案件）を適用する。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectPositionApiController {

    private final PositionService positionService;
    private final StaffingBoardService boardService;
    private final DataScopeService dataScopeService;
    private final ProjectService projectService;

    /** 案件詳細のポジションボード。 */
    @GetMapping("/{projectId}/board")
    public ApiResult<PositionBoardDto> board(@PathVariable Long projectId) {
        dataScopeService.assertAllowedProject(projectId);
        return ApiResult.success(boardService.projectBoard(projectId));
    }

    /** 案件配下のポジション一覧。 */
    @GetMapping("/{projectId}/positions")
    public ApiResult<List<ProjectPosition>> list(@PathVariable Long projectId) {
        dataScopeService.assertAllowedProject(projectId);
        return ApiResult.success(positionService.listByProject(projectId));
    }

    /** ポジション登録。 */
    @PostMapping("/{projectId}/positions")
    public ApiResult<ProjectPosition> create(@PathVariable Long projectId,
                                             @Valid @RequestBody ProjectPosition position) {
        dataScopeService.assertAllowedProject(projectId);
        position.setProjectId(projectId);
        return ApiResult.success(positionService.create(position));
    }

    /** ポジション更新。 */
    @PutMapping("/{projectId}/positions/{positionId}")
    public ApiResult<ProjectPosition> update(@PathVariable Long projectId, @PathVariable Long positionId,
                                             @Valid @RequestBody ProjectPosition position) {
        dataScopeService.assertAllowedProject(projectId);
        position.setId(positionId);
        return ApiResult.success(positionService.update(position));
    }

    /** ポジション状態遷移（募集中→候補選定→充足→募集中など。design §5.4）。 */
    @PostMapping("/{projectId}/positions/{positionId}/status")
    public ApiResult<ProjectPosition> changeStatus(@PathVariable Long projectId, @PathVariable Long positionId,
                                                   @RequestBody Map<String, String> body) {
        dataScopeService.assertAllowedProject(projectId);
        String status = body == null ? null : body.get("status");
        return ApiResult.success(positionService.changeStatus(positionId, status));
    }

    /** ポジション削除（論理削除。充足済みは拒否）。 */
    @DeleteMapping("/{projectId}/positions/{positionId}")
    public ApiResult<Boolean> delete(@PathVariable Long projectId, @PathVariable Long positionId) {
        dataScopeService.assertAllowedProject(projectId);
        positionService.delete(positionId);
        return ApiResult.success(true);
    }
}
