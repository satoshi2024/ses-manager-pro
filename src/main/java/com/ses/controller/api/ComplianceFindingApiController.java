package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ComplianceFindingActionRequest;
import com.ses.service.ComplianceFindingActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * compliance findingの対応操作API（T065 B2、R3.4）。
 * /api/contracts 配下のため契約メニュー権限で保護。管理者/HR/マネージャーのみ（営業403）。
 * 遷移: ack / in-progress / resolve / exception（EXCEPTION_APPROVEDは有効期限付き）。
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ComplianceFindingApiController {

    private final ComplianceFindingActionService complianceFindingActionService;

    @PostMapping("/{id}/compliance-findings/{findingId}/ack")
    public ApiResult<Boolean> ack(@PathVariable Long id, @PathVariable Long findingId) {
        complianceFindingActionService.ack(id, findingId);
        return ApiResult.success(true);
    }

    @PostMapping("/{id}/compliance-findings/{findingId}/in-progress")
    public ApiResult<Boolean> inProgress(@PathVariable Long id, @PathVariable Long findingId) {
        complianceFindingActionService.inProgress(id, findingId);
        return ApiResult.success(true);
    }

    @PostMapping("/{id}/compliance-findings/{findingId}/resolve")
    public ApiResult<Boolean> resolve(@PathVariable Long id, @PathVariable Long findingId,
                                      @RequestBody ComplianceFindingActionRequest request) {
        complianceFindingActionService.resolve(id, findingId,
                request == null ? null : request.getNote(),
                request == null ? null : request.getEvidenceDocumentId());
        return ApiResult.success(true);
    }

    @PostMapping("/{id}/compliance-findings/{findingId}/exception")
    public ApiResult<Boolean> exception(@PathVariable Long id, @PathVariable Long findingId,
                                        @RequestBody ComplianceFindingActionRequest request) {
        complianceFindingActionService.exception(id, findingId,
                request == null ? null : request.getNote(),
                request == null ? null : request.getExpiresAt());
        return ApiResult.success(true);
    }
}
