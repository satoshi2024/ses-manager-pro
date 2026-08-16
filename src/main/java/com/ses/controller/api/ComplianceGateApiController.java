package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ComplianceAdoptionEventDto;
import com.ses.dto.compliance.ComplianceApprovalEventDto;
import com.ses.dto.compliance.ComplianceApprovalRequest;
import com.ses.dto.compliance.ComplianceAssignmentDto;
import com.ses.dto.compliance.ComplianceAssignmentRequest;
import com.ses.dto.compliance.ComplianceCapabilityDto;
import com.ses.dto.compliance.ComplianceEvidencePickerDto;
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

    // ===== review policy group/type（R23-P1-01 P0-1: Policy tab操作） =====

    @GetMapping("/mappings/{id}/requirement-groups")
    public ApiResult<List<com.ses.entity.ComplianceMappingReviewRequirementGroup>> listRequirementGroups(@PathVariable Long id) {
        return ApiResult.success(complianceGateAdminService.listRequirementGroups(id));
    }

    @PostMapping("/mappings/{id}/requirement-groups")
    public ApiResult<com.ses.entity.ComplianceMappingReviewRequirementGroup> createRequirementGroup(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceRequirementGroupRequest request) {
        return ApiResult.success(complianceGateAdminService.createRequirementGroup(
                id, request.getGroupCode(), request.getDisplayName(),
                request.getMinimumDistinctReviewers() == null ? 1 : request.getMinimumDistinctReviewers()));
    }

    @PostMapping("/requirement-groups/{groupId}/requirement-types")
    public ApiResult<com.ses.entity.ComplianceMappingReviewRequirementType> addRequirementType(
            @PathVariable Long groupId,
            @RequestBody com.ses.dto.compliance.ComplianceRequirementTypeRequest request) {
        return ApiResult.success(complianceGateAdminService.addRequirementType(groupId, request.getReviewerTypeId()));
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
    public ApiResult<ComplianceAssignmentDto> createAssignment(
            @RequestBody ComplianceAssignmentRequest request) {
        return ApiResult.success(ComplianceAssignmentDto.fromEntity(
                complianceGateAdminService.createAssignment(
                        request.getWorkplaceId(), request.getUserId(), request.getEffectiveFrom())));
    }

    @PutMapping("/assignments/{id}/end")
    public ApiResult<ComplianceAssignmentDto> endAssignment(@PathVariable Long id,
                                                            @RequestParam String reason) {
        return ApiResult.success(ComplianceAssignmentDto.fromEntity(
                complianceGateAdminService.endAssignment(id, reason)));
    }

    @PostMapping("/approvals")
    public ApiResult<ComplianceApprovalEventDto> approve(@RequestBody ComplianceApprovalRequest request) {
        return ApiResult.success(ComplianceApprovalEventDto.fromEntity(
                complianceApprovalService.approve(
                        request.getMappingId(), request.getWorkplaceId(), request.getReason(),
                        request.getEvidenceDocumentId(), request.getEvidenceDocumentVersionId())));
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

    // R23-P1-01 P0-4: subject create path（person-stable正本・fingerprint計算）
    @PostMapping("/subjects")
    public ApiResult<ComplianceReviewerSubjectDto> createSubject(
            @RequestBody com.ses.dto.compliance.ComplianceSubjectRequest request) {
        return ApiResult.success(ComplianceReviewerSubjectDto.fromEntity(
                complianceGateAdminService.createSubject(
                        request.getSubjectCode(), request.getDisplayName(), request.getOrganizationName())));
    }

    // R23-P1-01 P0-4: subject×資格association
    @PostMapping("/subjects/{id}/qualifications")
    public ApiResult<com.ses.entity.ComplianceReviewerQualification> addQualification(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceQualificationRequest request) {
        return ApiResult.success(complianceGateAdminService.addQualification(
                id, request.getReviewerTypeId(),
                request.getRegistrationIdentifierMaskedSnapshot(), request.getRegistrationIdentifierLabel()));
    }

    @GetMapping("/subjects/{id}/qualifications")
    public ApiResult<List<com.ses.entity.ComplianceReviewerQualification>> listQualifications(@PathVariable Long id) {
        return ApiResult.success(complianceGateAdminService.listQualifications(id));
    }

    // ===== dynamic policy master（R23-P1-01 §3.8・P0-3） =====

    @GetMapping("/verification-sources")
    public ApiResult<List<com.ses.entity.ComplianceVerificationSource>> listVerificationSources() {
        return ApiResult.success(complianceGateAdminService.listVerificationSources());
    }

    @PostMapping("/verification-sources")
    public ApiResult<com.ses.entity.ComplianceVerificationSource> createVerificationSource(
            @RequestBody com.ses.dto.compliance.ComplianceVerificationSourceRequest request) {
        return ApiResult.success(complianceGateAdminService.createVerificationSource(
                request.getSourceCode(), request.getSourceName(), request.getOfficialUrl(),
                Boolean.TRUE.equals(request.getEnabled()), request.getEffectiveFrom(), request.getEffectiveTo()));
    }

    @PutMapping("/verification-sources/{id}")
    public ApiResult<com.ses.entity.ComplianceVerificationSource> updateVerificationSource(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceVerificationSourceRequest request) {
        return ApiResult.success(complianceGateAdminService.updateVerificationSource(
                id, request.getSourceName(), request.getOfficialUrl(),
                Boolean.TRUE.equals(request.getEnabled()), request.getEffectiveFrom(), request.getEffectiveTo()));
    }

    @GetMapping("/verification-methods")
    public ApiResult<List<com.ses.entity.ComplianceVerificationMethod>> listVerificationMethods() {
        return ApiResult.success(complianceGateAdminService.listVerificationMethods());
    }

    @PostMapping("/verification-methods")
    public ApiResult<com.ses.entity.ComplianceVerificationMethod> createVerificationMethod(
            @RequestBody com.ses.dto.compliance.ComplianceVerificationMethodRequest request) {
        return ApiResult.success(complianceGateAdminService.createVerificationMethod(
                request.getMethodCode(), request.getMethodName(), request.getDescription(),
                Boolean.TRUE.equals(request.getEnabled()), request.getEffectiveFrom(), request.getEffectiveTo()));
    }

    @PutMapping("/verification-methods/{id}")
    public ApiResult<com.ses.entity.ComplianceVerificationMethod> updateVerificationMethod(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceVerificationMethodRequest request) {
        return ApiResult.success(complianceGateAdminService.updateVerificationMethod(
                id, request.getMethodName(), request.getDescription(),
                Boolean.TRUE.equals(request.getEnabled()), request.getEffectiveFrom(), request.getEffectiveTo()));
    }

    // R23-P1-01 P0-3: reviewer type dynamic設定（flags・source/method・max_age・effective period・§8）
    @PutMapping("/reviewer-types/{id}/dynamic")
    public ApiResult<ComplianceReviewerTypeDto> updateReviewerTypeDynamic(
            @PathVariable Long id,
            @RequestBody com.ses.dto.compliance.ComplianceReviewerTypeDynamicRequest request) {
        return ApiResult.success(ComplianceReviewerTypeDto.fromEntity(
                complianceGateAdminService.updateReviewerTypeDynamic(
                        id, request.getQualificationVerificationRequired(),
                        request.getActiveStatusVerificationRequired(),
                        request.getVerificationSourceId(), request.getVerificationMethodId(),
                        request.getMaxAgeDays(), request.getEffectiveFrom(), request.getEffectiveTo())));
    }

    // ===== exact CLEAN evidence picker（R23-P1-01 P0-5・allow-list） =====

    @GetMapping("/evidence-picker")
    public ApiResult<List<ComplianceEvidencePickerDto>> evidencePicker(
            @RequestParam(required = false) String query) {
        return ApiResult.success(complianceGateAdminService.searchEvidence(query));
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

    // ===== Phase B manifest（R23-P1-01 P1-7・allow-list） =====

    /**
     * Phase B manifest用の完全hash/ID一覧（allow-list）。
     * mapping・sources・policy groups/types・approval・external reviews・verification・adoption・evidenceの
     * 完全ID/hashを返す（§4-11 gate snapshot・Phase B manifest）。
     */
    @GetMapping("/mappings/{id}/manifest")
    public ApiResult<com.ses.dto.compliance.ComplianceManifestDto> manifest(@PathVariable Long id) {
        return ApiResult.success(complianceGateAdminService.buildManifest(id));
    }
}
