package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ComplianceAdoptionEventDto;
import com.ses.dto.compliance.ComplianceApprovalRequest;
import com.ses.dto.compliance.ComplianceAssignmentRequest;
import com.ses.dto.compliance.ComplianceCapabilityDto;
import com.ses.dto.compliance.ComplianceExternalReviewRequest;
import com.ses.dto.compliance.ComplianceMappingCreateRequest;
import com.ses.dto.compliance.ComplianceMappingVersionDto;
import com.ses.dto.compliance.ComplianceReviewerSubjectDto;
import com.ses.dto.compliance.ComplianceReviewerTypeDto;
import com.ses.dto.compliance.ComplianceReviewerTypeRequest;
import com.ses.dto.compliance.ComplianceVerificationEventDto;
import com.ses.dto.compliance.ComplianceVerificationRecordRequest;
import com.ses.dto.compliance.ComplianceVerificationRevokeRequest;
import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceExternalReviewAdoptionService;
import com.ses.service.ComplianceExternalReviewVerificationService;
import com.ses.service.ComplianceGateAdminService;
import com.ses.service.ComplianceMappingService;
import com.ses.service.compliance.ComplianceCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * G2 mapping version / reviewer verification API（R23-P1-01 §5）。
 * typed request/response DTOとallow-listを使用し、entityやMapをAPI契約にしない（§5）。
 * 管理者のみ（SecurityConfigで制限）。mapping_hashはcanonicalizerで計算し、client supplied hashは信頼しない。
 */
@RestController
@RequestMapping("/api/compliance-gate")
@RequiredArgsConstructor
public class ComplianceGateApiController {

    private final ComplianceMappingService complianceMappingService;
    private final ComplianceGateAdminService complianceGateAdminService;
    private final ComplianceApprovalService complianceApprovalService;
    private final ComplianceExternalReviewVerificationService verificationService;
    private final ComplianceExternalReviewAdoptionService adoptionService;
    private final ComplianceCapabilityService capabilityService;

    @GetMapping("/capabilities")
    public ApiResult<ComplianceCapabilityDto> capabilities() {
        return ApiResult.success(capabilityService.current());
    }

    // ===== mapping version =====

    @PostMapping("/mappings")
    public ApiResult<ComplianceMappingVersionDto> create(@RequestBody ComplianceMappingCreateRequest request) {
        ComplianceMappingVersion version = complianceMappingService.create(
                request.getMappingCode(),
                request.getMappingVersion(),
                request.getEffectiveFrom(),
                request.getEffectiveTo(),
                request.getSources());
        return ApiResult.success(ComplianceMappingVersionDto.fromEntity(version));
    }

    @GetMapping("/mappings")
    public ApiResult<List<ComplianceMappingVersionDto>> list() {
        return ApiResult.success(complianceMappingService.list().stream()
                .map(ComplianceMappingVersionDto::fromEntity)
                .toList());
    }

    @GetMapping("/mappings/{id}")
    public ApiResult<ComplianceMappingVersionDto> getById(@PathVariable Long id) {
        return ApiResult.success(ComplianceMappingVersionDto.fromEntity(complianceMappingService.getById(id)));
    }

    @PutMapping("/mappings/{id}/transition")
    public ApiResult<ComplianceMappingVersionDto> transition(@PathVariable Long id,
                                                             @RequestParam String toStatus,
                                                             @RequestParam(required = false) Long approvalEventId) {
        return ApiResult.success(ComplianceMappingVersionDto.fromEntity(
                complianceMappingService.transition(id, toStatus, approvalEventId)));
    }

    @PutMapping("/mappings/{id}/promote")
    public ApiResult<ComplianceMappingVersionDto> promote(@PathVariable Long id) {
        return ApiResult.success(ComplianceMappingVersionDto.fromEntity(
                complianceMappingService.promoteFutureToActive(id)));
    }

    // ===== reviewer type / assignment / approval（Phase A step 3） =====

    @GetMapping("/reviewer-types")
    public ApiResult<List<ComplianceReviewerTypeDto>> reviewerTypes() {
        return ApiResult.success(complianceGateAdminService.listReviewerTypes().stream()
                .map(ComplianceReviewerTypeDto::fromEntity)
                .toList());
    }

    @PostMapping("/reviewer-types")
    public ApiResult<ComplianceReviewerTypeDto> createReviewerType(@RequestBody ComplianceReviewerTypeRequest request) {
        return ApiResult.success(ComplianceReviewerTypeDto.fromEntity(
                complianceGateAdminService.createReviewerType(
                        request.getTypeCode(),
                        request.getDisplayName(),
                        request.getDescription(),
                        request.getCredentialLabel(),
                        Boolean.TRUE.equals(request.getCredentialRequired()))));
    }

    @PutMapping("/reviewer-types/{id}")
    public ApiResult<ComplianceReviewerTypeDto> updateReviewerType(@PathVariable Long id,
                                                                   @RequestBody ComplianceReviewerTypeRequest request) {
        return ApiResult.success(ComplianceReviewerTypeDto.fromEntity(
                complianceGateAdminService.updateReviewerType(
                        id, request.getDisplayName(), request.getDescription(),
                        request.getCredentialLabel(), Boolean.TRUE.equals(request.getCredentialRequired()))));
    }

    @PutMapping("/reviewer-types/{id}/enabled")
    public ApiResult<ComplianceReviewerTypeDto> setReviewerTypeEnabled(@PathVariable Long id,
                                                                       @RequestParam boolean enabled) {
        return ApiResult.success(ComplianceReviewerTypeDto.fromEntity(
                complianceGateAdminService.setReviewerTypeEnabled(id, enabled)));
    }

    @PostMapping("/assignments")
    public ApiResult<com.ses.entity.ComplianceResponsibleAssignment> createAssignment(
            @RequestBody ComplianceAssignmentRequest request) {
        return ApiResult.success(complianceGateAdminService.createAssignment(
                request.getWorkplaceId(), request.getUserId(), request.getEffectiveFrom()));
    }

    @PutMapping("/assignments/{id}/end")
    public ApiResult<com.ses.entity.ComplianceResponsibleAssignment> endAssignment(@PathVariable Long id,
                                                                                   @RequestParam String reason) {
        return ApiResult.success(complianceGateAdminService.endAssignment(id, reason));
    }

    @PostMapping("/approvals")
    public ApiResult<com.ses.entity.ComplianceMappingApprovalEvent> approve(@RequestBody ComplianceApprovalRequest request) {
        return ApiResult.success(complianceApprovalService.approve(
                request.getMappingId(), request.getWorkplaceId(), request.getReason(), request.getEvidenceDocumentId()));
    }

    // ===== external review（SUBMITTED・K1） =====

    @PostMapping("/external-reviews")
    public ApiResult<com.ses.dto.compliance.ComplianceExternalReviewEventDto> recordExternalReview(
            @RequestBody ComplianceExternalReviewRequest request) {
        com.ses.entity.ComplianceExternalReviewEvent event = complianceGateAdminService.recordExternalReview(
                request.getMappingId(),
                request.getRequirementGroupId(),
                request.getReviewerTypeId(),
                request.getReviewerName(),
                request.getOrganization(),
                request.getCredentialRaw(),
                "SUBMITTED",
                request.getReviewedAt(),
                request.getValidUntil(),
                request.getEvidenceDocumentId(),
                request.getReason(),
                request.getTargetEventId());
        return ApiResult.success(com.ses.dto.compliance.ComplianceExternalReviewEventDto.fromEntity(event));
    }

    @GetMapping("/mappings/{id}/external-reviews")
    public ApiResult<List<com.ses.dto.compliance.ComplianceExternalReviewEventDto>> listExternalReviews(@PathVariable Long id) {
        return ApiResult.success(complianceGateAdminService.listExternalReviews(id).stream()
                .map(com.ses.dto.compliance.ComplianceExternalReviewEventDto::fromEntity)
                .toList());
    }

    // ===== reviewer subject =====

    @GetMapping("/subjects")
    public ApiResult<List<ComplianceReviewerSubjectDto>> listSubjects() {
        return ApiResult.success(complianceGateAdminService.listSubjects().stream()
                .map(ComplianceReviewerSubjectDto::fromEntity)
                .toList());
    }

    // ===== verification（R23-P1-01 §3.3） =====

    @PostMapping("/verifications")
    public ApiResult<ComplianceVerificationEventDto> recordVerification(
            @RequestBody ComplianceVerificationRecordRequest request) {
        ComplianceExternalReviewerVerificationEvent event = verificationService.record(
                request.getSubmittedReviewEventId(),
                request.getReviewerSubjectId(),
                request.getReviewerTypeId(),
                request.getVerificationKind(),
                request.getResult(),
                request.getMethodCode(),
                request.getAuthoritySourceCode(),
                request.getAuthoritySourceName(),
                request.getOfficialUrlReference(),
                request.getRegistrationIdentifier(),
                request.getCheckedAt(),
                request.getSourceDataAsOf(),
                request.getMaxAgeDays(),
                request.getValidUntil(),
                com.ses.common.util.SecurityUtils.currentUserId(),
                request.getEvidenceDocumentId(),
                request.getEvidenceDocumentVersionId(),
                request.getReviewPolicyVersion(),
                request.getReviewPolicyHash(),
                request.getMappingId(),
                request.getMappingVersion(),
                request.getMappingHash(),
                request.getExternalReviewEventId(),
                request.getExternalReviewChainId(),
                request.getIdempotencyKey());
        return ApiResult.success(ComplianceVerificationEventDto.fromEntity(event));
    }

    @PutMapping("/verifications/{id}/revoke")
    public ApiResult<ComplianceVerificationEventDto> revokeVerification(
            @PathVariable Long id,
            @RequestBody ComplianceVerificationRevokeRequest request) {
        ComplianceExternalReviewerVerificationEvent event = verificationService.revoke(
                request.getTargetVerificationEventId(),
                request.getReason(),
                com.ses.common.util.SecurityUtils.currentUserId(),
                request.getIdempotencyKey());
        return ApiResult.success(ComplianceVerificationEventDto.fromEntity(event));
    }

    @GetMapping("/mappings/{id}/verifications")
    public ApiResult<List<ComplianceVerificationEventDto>> listVerificationsByMapping(@PathVariable Long id) {
        return ApiResult.success(complianceGateAdminService.listVerificationsByMapping(id).stream()
                .map(ComplianceVerificationEventDto::fromEntity)
                .toList());
    }

    @GetMapping("/submitted-reviews/{id}/verifications")
    public ApiResult<List<ComplianceVerificationEventDto>> listVerificationsBySubmittedReview(@PathVariable Long id) {
        return ApiResult.success(verificationService.listBySubmittedReview(id).stream()
                .map(ComplianceVerificationEventDto::fromEntity)
                .toList());
    }

    // ===== adoption（R23-P1-01 §3.4） =====

    @PostMapping("/submitted-reviews/{id}/adoptions/approve")
    public ApiResult<ComplianceAdoptionEventDto> approveAdoption(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceAdoptionRequest request) {
        ComplianceExternalReviewAdoptionEvent event = adoptionService.approve(
                id,
                request.getIdentityVerificationEventId(),
                request.getQualificationVerificationEventId(),
                request.getActiveStatusVerificationEventId(),
                request.getAuthorshipVerificationEventId(),
                request.getEvidenceDocumentId(),
                request.getEvidenceDocumentVersionId(),
                request.getIdempotencyKey());
        return ApiResult.success(ComplianceAdoptionEventDto.fromEntity(event));
    }

    @PostMapping("/submitted-reviews/{id}/adoptions/reject")
    public ApiResult<ComplianceAdoptionEventDto> rejectAdoption(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceAdoptionRequest request) {
        ComplianceExternalReviewAdoptionEvent event = adoptionService.reject(
                id, request.getReason(), request.getIdempotencyKey());
        return ApiResult.success(ComplianceAdoptionEventDto.fromEntity(event));
    }

    @PutMapping("/adoptions/{id}/revoke")
    public ApiResult<ComplianceAdoptionEventDto> revokeAdoption(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceAdoptionRequest request) {
        ComplianceExternalReviewAdoptionEvent event = adoptionService.revoke(
                id, request.getReason(), request.getIdempotencyKey());
        return ApiResult.success(ComplianceAdoptionEventDto.fromEntity(event));
    }

    @GetMapping("/submitted-reviews/{id}/adoptions")
    public ApiResult<List<ComplianceAdoptionEventDto>> listAdoptionsBySubmittedReview(@PathVariable Long id) {
        return ApiResult.success(adoptionService.listBySubmittedReview(id).stream()
                .map(ComplianceAdoptionEventDto::fromEntity)
                .toList());
    }
}
