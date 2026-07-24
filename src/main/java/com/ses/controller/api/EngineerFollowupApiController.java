package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.entity.EngineerFollowup;
import com.ses.service.EngineerFollowupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 要員フォロー記録API
 */
@RestController
@RequestMapping("/api/engineers/{engineerId}/followups")
@RequiredArgsConstructor
public class EngineerFollowupApiController {

    private final EngineerFollowupService engineerFollowupService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping
    public ApiResult<List<EngineerFollowup>> list(@PathVariable Long engineerId) {
        dataScopeService.assertAllowedEngineer(engineerId);
        LambdaQueryWrapper<EngineerFollowup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerFollowup::getEngineerId, engineerId);
        wrapper.orderByDesc(EngineerFollowup::getFollowupDate, EngineerFollowup::getId);
        return ApiResult.success(engineerFollowupService.list(wrapper));
    }

    @GetMapping("/{id}")
    public ApiResult<EngineerFollowup> getById(@PathVariable Long engineerId, @PathVariable Long id) {
        dataScopeService.assertAllowedEngineer(engineerId);
        return ApiResult.success(findOwnedOrThrow(engineerId, id));
    }

    @PostMapping
    public ApiResult<Boolean> save(@PathVariable Long engineerId, @RequestBody EngineerFollowup followup) {
        dataScopeService.assertAllowedEngineer(engineerId);
        validate(followup);
        com.ses.common.util.EntityProtectUtil.protectForCreate(followup);
        followup.setEngineerId(engineerId);
        return ApiResult.success(engineerFollowupService.save(followup));
    }

    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long engineerId, @PathVariable Long id, @RequestBody EngineerFollowup followup) {
        dataScopeService.assertAllowedEngineer(engineerId);
        // 更新対象が本当にこの要員に属するフォロー記録かを確認する（他要員のIDを指定した書き換えを防止）
        findOwnedOrThrow(engineerId, id);
        validate(followup);
        followup.setId(id);
        followup.setEngineerId(engineerId);
        boolean success = engineerFollowupService.updateById(followup);
        if (!success) throw BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long engineerId, @PathVariable Long id) {
        dataScopeService.assertAllowedEngineer(engineerId);
        // 削除対象が本当にこの要員に属するフォロー記録かを確認する（他要員の記録を削除できてしまう不備を防止）
        findOwnedOrThrow(engineerId, id);
        boolean success = engineerFollowupService.removeById(id);
        if (!success) throw BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * 指定IDのフォロー記録が指定要員に属することを確認して返す。属さない/存在しない場合は例外を投げる。
     * URLパスの engineerId とは無関係な他要員の記録を id 指定だけで更新・削除できてしまう不備（IDOR）を防ぐ。
     */
    private EngineerFollowup findOwnedOrThrow(Long engineerId, Long id) {
        EngineerFollowup followup = engineerFollowupService.getById(id);
        if (followup == null || !followup.getEngineerId().equals(engineerId)) {
            throw BusinessException.of(404, "error.engineerFollowup.notFound");
        }
        return followup;
    }

    private void validate(EngineerFollowup followup) {
        if (followup.getFollowupType() == null || followup.getFollowupType().isBlank()) {
            throw BusinessException.of("error.engineerFollowup.typeRequired");
        }
        if (followup.getFollowupDate() == null) {
            throw BusinessException.of("error.engineerFollowup.dateRequired");
        }
        if (followup.getSatisfaction() != null && (followup.getSatisfaction() < 1 || followup.getSatisfaction() > 5)) {
            throw BusinessException.of("error.engineerFollowup.satisfactionRange");
        }
    }
}
