package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.service.BpAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 外部要員在庫 API コントローラー。
 */
@RestController
@RequestMapping("/api/bp-availabilities")
@RequiredArgsConstructor
public class BpAvailabilityApiController {

    private final BpAvailabilityService bpAvailabilityService;

    /**
     * 在庫一覧を取得する。
     */
    @GetMapping
    public ApiResult<Page<BpAvailability>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        Page<BpAvailability> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<BpAvailability> wrapper = new LambdaQueryWrapper<BpAvailability>()
                .eq(status != null && !status.isBlank(), BpAvailability::getStatus, status)
                .orderByDesc(BpAvailability::getCreatedAt);
        return ApiResult.success(bpAvailabilityService.page(page, wrapper));
    }

    /**
     * 在庫詳細を取得する。
     */
    @GetMapping("/{id}")
    public ApiResult<BpAvailability> getById(@PathVariable Long id) {
        return ApiResult.success(bpAvailabilityService.getById(id));
    }

    /**
     * 在庫を更新する。
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @RequestBody BpAvailability bpAvailability) {
        bpAvailability.setId(id);
        com.ses.common.util.EntityProtectUtil.protectForUpdate(bpAvailability);
        return ApiResult.success(bpAvailabilityService.updateById(bpAvailability));
    }

    /**
     * 在庫を削除する。
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(bpAvailabilityService.removeById(id));
    }

    /**
     * 外部要員を自社の要員（BP）として登録・昇格する。
     */
    @PostMapping("/{id}/promote")
    public ApiResult<Engineer> promote(@PathVariable Long id) {
        Engineer engineer = bpAvailabilityService.promoteToEngineer(id);
        return ApiResult.success(engineer);
    }
}
