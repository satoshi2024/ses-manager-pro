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
     * portal提出（未確認）と却下は内部候補に出さない（R3.2: review前のavailabilityが内部の候補に出ないこと）。
     * status指定時はそのstatusだけを返す（内部確認用）。
     */
    @GetMapping
    public ApiResult<Page<BpAvailability>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        Page<BpAvailability> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<BpAvailability> wrapper = new LambdaQueryWrapper<BpAvailability>()
                .eq(status != null && !status.isBlank(), BpAvailability::getStatus, status)
                .notIn((status == null || status.isBlank()),
                        BpAvailability::getStatus,
                        com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_PENDING,
                        com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_REJECTED)
                .orderByDesc(BpAvailability::getCreatedAt);
        return ApiResult.success(bpAvailabilityService.page(page, wrapper));
    }

    /**
     * 内部営業のreview（R3.1: portal提出→review→有効化）。未確認→提案可能（approved）/ 未確認→却下。
     * 同一メニュー（bp-availability）のapi_prefix配下のため、既存のメニュー権限で保護される。
     */
    @PostMapping("/{id}/review")
    public ApiResult<BpAvailability> review(@PathVariable Long id, @RequestBody ReviewRequest request) {
        BpAvailability availability = bpAvailabilityService.getById(id);
        if (availability == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        if (!com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_PENDING.equals(availability.getStatus())) {
            throw com.ses.common.exception.BusinessException.of(409, "error.portal.bp.availabilityReviewed");
        }
        String next = Boolean.TRUE.equals(request.isApproved())
                ? com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_ACTIVE
                : com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_REJECTED;
        availability.setStatus(next);
        availability.setRemarks(request.getComment() == null || request.getComment().isBlank()
                ? availability.getRemarks() : request.getComment().trim());
        bpAvailabilityService.updateById(availability);
        return ApiResult.success(availability);
    }

    /** reviewリクエスト。 */
    public static class ReviewRequest {
        private boolean approved;
        private String comment;

        public boolean isApproved() {
            return approved;
        }

        public void setApproved(boolean approved) {
            this.approved = approved;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
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
        if (bpAvailability.getBpCompany() != null && !bpAvailability.getBpCompany().isBlank()
                && bpAvailability.getBpCompanyId() == null) {
            throw com.ses.common.exception.BusinessException.of(400, "error.bpAvailability.bpCompanyRequired");
        }
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
