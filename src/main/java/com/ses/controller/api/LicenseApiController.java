package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.common.exception.BusinessException;
import com.ses.entity.LicenseAssignment;
import com.ses.entity.LicensePlan;
import com.ses.service.LicenseService;
import com.ses.service.AssetScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class LicenseApiController {

    private final LicenseService licenseService;
    private final AssetScopeService assetScopeService;

    @GetMapping("/plans")
    public ApiResult<IPage<LicensePlan>> listPlans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        IPage<LicensePlan> plans = licenseService.searchPlansScoped(page, size, keyword, status,
                assetScopeService.getAccessibleLicensePlanIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        return ApiResult.success(plans);
    }

    @PostMapping("/plans")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<LicensePlan> savePlan(@RequestBody LicensePlan plan) {
        Long currentUserId = SecurityUtils.currentUserId();
        LicensePlan saved = licenseService.savePlan(plan, currentUserId);
        return ApiResult.success(saved);
    }

    @GetMapping("/plans/{planId}/assignments")
    public ApiResult<List<LicenseAssignment>> getAssignments(@PathVariable Long planId) {
        assertAccessiblePlan(planId);
        List<LicenseAssignment> list = licenseService.getAssignmentsByPlanId(planId);
        return ApiResult.success(list);
    }

    @PostMapping("/plans/{planId}/assign")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<LicenseAssignment> assign(@PathVariable Long planId, @RequestBody LicenseAssignRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        LocalDate assignedDate = req.getAssignedDate() != null ? req.getAssignedDate() : LocalDate.now();
        LicenseAssignment assignment = licenseService.assignLicense(
                planId,
                req.getAssigneeType(),
                req.getAssigneeId(),
                req.getAccountReferenceId(),
                assignedDate,
                currentUserId
        );
        return ApiResult.success(assignment);
    }

    @PostMapping("/assignments/{id}/release")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<LicenseAssignment> release(@PathVariable Long id, @RequestBody(required = false) LicenseReleaseRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        LocalDate releasedDate = req != null && req.getReleasedDate() != null ? req.getReleasedDate() : LocalDate.now();
        LicenseAssignment released = licenseService.releaseLicense(id, releasedDate, currentUserId);
        return ApiResult.success(released);
    }

    @Data
    public static class LicenseAssignRequest {
        private String assigneeType;
        private Long assigneeId;
        private Long accountReferenceId;
        private LocalDate assignedDate;
    }

    @Data
    public static class LicenseReleaseRequest {
        private LocalDate releasedDate;
    }

    private void assertAccessiblePlan(Long planId) {
        List<Long> allowed = assetScopeService.getAccessibleLicensePlanIds(
                SecurityUtils.currentRole(), SecurityUtils.currentUserId());
        if (allowed != null && !allowed.contains(planId)) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }
}
