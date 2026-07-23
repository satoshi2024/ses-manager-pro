package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.SalesActivity;
import com.ses.dto.salesactivity.SalesActivityCreateRequest;
import com.ses.dto.salesactivity.SalesActivityUpdateRequest;
import com.ses.service.SalesActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class SalesActivityApiController {

    private final SalesActivityService salesActivityService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping("/{id}/activities")
    public ApiResult<Page<SalesActivity>> getActivities(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String type) {
        dataScopeService.assertAllowedCustomer(id);
        salesActivityService.assertCustomerExists(id);
        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ
        Page<SalesActivity> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<SalesActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SalesActivity::getCustomerId, id);
        
        if (StringUtils.hasText(type)) {
            wrapper.eq(SalesActivity::getActivityType, type);
        }
        
        wrapper.orderByDesc(SalesActivity::getActivityDate);
        return ApiResult.success(salesActivityService.page(page, wrapper));
    }

    @PostMapping("/{id}/activities")
    public ApiResult<Boolean> createActivity(@PathVariable Long id, @Valid @RequestBody SalesActivityCreateRequest request) {
        dataScopeService.assertAllowedCustomer(id);
        // created_by は MyBatis-Plus の MetaObjectHandler がログイン中ユーザーを自動設定する
        salesActivityService.create(id, request);
        return ApiResult.success(true);
    }

    @PutMapping("/{id}/activities/{activityId}")
    public ApiResult<Boolean> updateActivity(
            @PathVariable Long id,
            @PathVariable Long activityId,
            @Valid @RequestBody SalesActivityUpdateRequest request) {
        dataScopeService.assertAllowedCustomer(id);
        salesActivityService.update(id, activityId, request);
        return ApiResult.success(true);
    }

    @PutMapping("/{id}/activities/{activityId}/complete")
    public ApiResult<Boolean> completeActivity(
            @PathVariable Long id,
            @PathVariable Long activityId) {
        dataScopeService.assertAllowedCustomer(id);
        salesActivityService.complete(id, activityId);
        return ApiResult.success(true);
    }

    @DeleteMapping("/{id}/activities/{activityId}")
    public ApiResult<Boolean> deleteActivity(
            @PathVariable Long id,
            @PathVariable Long activityId) {
        dataScopeService.assertAllowedCustomer(id);
        salesActivityService.delete(id, activityId);
        return ApiResult.success(true);
    }

    @GetMapping("/follow-ups")
    public ApiResult<List<SalesActivity>> getFollowUps() {
        LambdaQueryWrapper<SalesActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(SalesActivity::getNextActionDate, LocalDate.now())
               .eq(SalesActivity::getCompletedFlag, 0)
               .orderByAsc(SalesActivity::getNextActionDate);
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
            wrapper.in(SalesActivity::getCustomerId, allowed);
        }
        return ApiResult.success(salesActivityService.list(wrapper));
    }
}
