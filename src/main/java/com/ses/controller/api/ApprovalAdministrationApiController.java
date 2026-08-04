package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.approval.ApprovalDelegationRequest;
import com.ses.dto.approval.ApprovalDelegationView;
import com.ses.dto.approval.ApprovalRoutePreviewRequest;
import com.ses.dto.approval.ApprovalRoutePreviewView;
import com.ses.dto.approval.ApprovalRouteSaveRequest;
import com.ses.dto.approval.ApprovalRouteView;
import com.ses.dto.approval.ApprovalResponsibilitySaveRequest;
import com.ses.dto.approval.ApprovalResponsibilityView;
import com.ses.service.approval.ApprovalAdministrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 管理者向けroute改版・approver preview・代理設定API。 */
@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
public class ApprovalAdministrationApiController {
    private final ApprovalAdministrationService administrationService;

    @GetMapping("/routes")
    public ApiResult<List<ApprovalRouteView>> routes(@RequestParam(required = false) LocalDate asOf) {
        return ApiResult.success(administrationService.listRoutes(asOf));
    }

    @PostMapping("/routes")
    public ApiResult<ApprovalRouteView> createRoute(@Valid @RequestBody ApprovalRouteSaveRequest request) {
        return ApiResult.success(administrationService.createRouteVersion(request, SecurityUtils.currentUserId()));
    }

    @PutMapping("/routes/{id}")
    public ApiResult<ApprovalRouteView> editRoute(@PathVariable Long id,
                                                   @Valid @RequestBody ApprovalRouteSaveRequest request) {
        ApprovalRouteSaveRequest version = new ApprovalRouteSaveRequest(id, request.requestType(), request.organizationId(),
                request.minAmount(), request.maxAmount(), request.validFrom(), request.validTo(),
                request.applicantRoleCondition(), request.steps());
        return ApiResult.success(administrationService.createRouteVersion(version, SecurityUtils.currentUserId()));
    }

    @PostMapping("/routes/preview")
    public ApiResult<ApprovalRoutePreviewView> preview(@Valid @RequestBody ApprovalRoutePreviewRequest request) {
        return ApiResult.success(administrationService.preview(request));
    }

    @GetMapping("/responsibilities")
    public ApiResult<List<ApprovalResponsibilityView>> responsibilities(
            @RequestParam(required = false) LocalDate asOf) {
        return ApiResult.success(administrationService.listResponsibilities(asOf));
    }

    @PostMapping("/responsibilities")
    public ApiResult<ApprovalResponsibilityView> createResponsibility(
            @Valid @RequestBody ApprovalResponsibilitySaveRequest request) {
        return ApiResult.success(administrationService.createResponsibility(
                request, SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/responsibilities/{id}")
    public ApiResult<Void> deleteResponsibility(@PathVariable Long id) {
        administrationService.deleteResponsibility(id);
        return ApiResult.success(null);
    }

    @GetMapping("/delegations")
    public ApiResult<List<ApprovalDelegationView>> delegations() {
        return ApiResult.success(administrationService.listDelegations());
    }

    @PostMapping("/delegations")
    public ApiResult<ApprovalDelegationView> createDelegation(@Valid @RequestBody ApprovalDelegationRequest request) {
        return ApiResult.success(administrationService.createDelegation(request, SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/delegations/{id}")
    public ApiResult<Void> deleteDelegation(@PathVariable Long id) {
        administrationService.deleteDelegation(id);
        return ApiResult.success(null);
    }
}
