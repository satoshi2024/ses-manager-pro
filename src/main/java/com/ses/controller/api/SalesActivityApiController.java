package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.SalesActivity;
import com.ses.service.SalesActivityService;
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

    @GetMapping("/{id}/activities")
    public ApiResult<Page<SalesActivity>> getActivities(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String type) {
        
        Page<SalesActivity> page = new Page<>(current, size);
        LambdaQueryWrapper<SalesActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SalesActivity::getCustomerId, id);
        
        if (StringUtils.hasText(type)) {
            wrapper.eq(SalesActivity::getActivityType, type);
        }
        
        wrapper.orderByDesc(SalesActivity::getActivityDate);
        return ApiResult.success(salesActivityService.page(page, wrapper));
    }

    @PostMapping("/{id}/activities")
    public ApiResult<Boolean> createActivity(@PathVariable Long id, @RequestBody SalesActivity activity) {
        activity.setCustomerId(id);
        // Ensure created_by is set to the current user
        Long currentUserId = SecurityUtils.currentUserId();
        if (currentUserId != null) {
            activity.setCreatedBy(currentUserId);
        }
        return ApiResult.success(salesActivityService.save(activity));
    }

    @PutMapping("/{id}/activities/{activityId}")
    public ApiResult<Boolean> updateActivity(
            @PathVariable Long id,
            @PathVariable Long activityId,
            @RequestBody SalesActivity activity) {
        activity.setId(activityId);
        activity.setCustomerId(id);
        return ApiResult.success(salesActivityService.updateById(activity));
    }

    @PutMapping("/{id}/activities/{activityId}/complete")
    public ApiResult<Boolean> completeActivity(
            @PathVariable Long id,
            @PathVariable Long activityId) {
        SalesActivity activity = new SalesActivity();
        activity.setId(activityId);
        activity.setCompletedFlag(1);
        return ApiResult.success(salesActivityService.updateById(activity));
    }

    @DeleteMapping("/{id}/activities/{activityId}")
    public ApiResult<Boolean> deleteActivity(
            @PathVariable Long id,
            @PathVariable Long activityId) {
        return ApiResult.success(salesActivityService.removeById(activityId));
    }

    @GetMapping("/follow-ups")
    public ApiResult<List<SalesActivity>> getFollowUps() {
        LambdaQueryWrapper<SalesActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(SalesActivity::getNextActionDate, LocalDate.now())
               .eq(SalesActivity::getCompletedFlag, 0)
               .orderByAsc(SalesActivity::getNextActionDate);
        return ApiResult.success(salesActivityService.list(wrapper));
    }
}
