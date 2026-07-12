package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.entity.EngineerCareer;
import com.ses.service.EngineerCareerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/engineers/{engineerId}/careers")
@RequiredArgsConstructor
public class EngineerCareerApiController {

    private final EngineerCareerService engineerCareerService;

    @GetMapping
    public ApiResult<List<EngineerCareer>> list(@PathVariable Long engineerId) {
        LambdaQueryWrapper<EngineerCareer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EngineerCareer::getEngineerId, engineerId);
        wrapper.orderByDesc(EngineerCareer::getPeriodFrom);
        return ApiResult.success(engineerCareerService.list(wrapper));
    }

    @GetMapping("/{id}")
    public ApiResult<EngineerCareer> getById(@PathVariable Long engineerId, @PathVariable Long id) {
        return ApiResult.success(findOwnedOrThrow(engineerId, id));
    }

    @PostMapping
    public ApiResult<Boolean> save(@PathVariable Long engineerId, @RequestBody EngineerCareer career) {
        validatePeriod(career);
        career.setId(null);
        career.setEngineerId(engineerId);
        return ApiResult.success(engineerCareerService.save(career));
    }

    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long engineerId, @PathVariable Long id, @RequestBody EngineerCareer career) {
        // 更新対象が本当にこの要員に属する経歴かを確認する（他要員のIDを指定した書き換えを防止）
        findOwnedOrThrow(engineerId, id);
        validatePeriod(career);
        career.setId(id);
        career.setEngineerId(engineerId);
        return ApiResult.success(engineerCareerService.updateById(career));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long engineerId, @PathVariable Long id) {
        // 削除対象が本当にこの要員に属する経歴かを確認する（他要員の経歴を削除できてしまう不備を防止）
        findOwnedOrThrow(engineerId, id);
        return ApiResult.success(engineerCareerService.removeById(id));
    }

    /**
     * 指定IDの経歴が指定要員に属することを確認して返す。属さない/存在しない場合は例外を投げる。
     * URLパスの engineerId とは無関係な他要員の経歴レコードを id 指定だけで
     * 更新・削除できてしまう不備（IDOR）を防ぐためのガード。
     */
    private EngineerCareer findOwnedOrThrow(Long engineerId, Long id) {
        EngineerCareer career = engineerCareerService.getById(id);
        if (career == null || !career.getEngineerId().equals(engineerId)) {
            throw new BusinessException(404, "指定された経歴情報が見つかりません");
        }
        return career;
    }

    private void validatePeriod(EngineerCareer career) {
        if (career.getPeriodTo() != null && career.getPeriodFrom() != null && career.getPeriodTo().isBefore(career.getPeriodFrom())) {
            throw new BusinessException("終了時期は開始時期以降を指定してください");
        }
    }
}
