package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.dto.certificationlearninggap.CertificationLearningGapAiView;
import com.ses.dto.certification.CertificationLifecycleActionView;
import com.ses.dto.certificationlearninggap.TrainingCourseMasterView;
import com.ses.entity.Certification;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.service.SkillGapService;
import com.ses.service.certification.CertificationMasterService;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.training.TrainingCourseMasterService;
import com.ses.service.certificationlearninggap.CertificationLearningGapQueryService;
import com.ses.service.certificationlearninggap.CertificationLearningGapTrainingApprovalService;
import com.ses.service.certificationlearninggap.CertificationLearningGapAiService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** HR/manager/admin向け資格・学習・skill gap API。全読み取り操作は共通queryを通る。 */
@RestController
@RequestMapping("/api/certification-learning-gap")
@RequiredArgsConstructor
public class CertificationLearningGapApiController {

    private final CertificationLearningGapQueryService queryService;
    private final CertificationLearningGapTrainingApprovalService trainingApprovalService;
    private final com.ses.service.certificationlearninggap.CertificationEvidenceAccessService evidenceAccessService;
    private final CertificationLearningGapAiService aiService;
    private final CertificationMasterService certificationMasterService;
    private final EngineerCertificationService engineerCertificationService;
    private final TrainingCourseMasterService trainingCourseMasterService;

    @GetMapping("/masters/certifications")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<List<Certification>> certificationMasters(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ApiResult.success(certificationMasterService.listMasters(includeInactive));
    }

    @GetMapping("/masters/certifications/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<Certification> certificationMaster(@PathVariable Long id) {
        return ApiResult.success(certificationMasterService.getMaster(id));
    }

    @PostMapping("/masters/certifications")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<Certification> createCertificationMaster(@RequestBody CertificationMasterRequest request) {
        return ApiResult.success(certificationMasterService.createMaster(toCertification(request),
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @PutMapping("/masters/certifications/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<Certification> updateCertificationMaster(@PathVariable Long id,
                                                               @RequestBody CertificationMasterRequest request) {
        return ApiResult.success(certificationMasterService.updateMaster(id, toCertification(request),
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/masters/certifications/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<Certification> deactivateCertificationMaster(@PathVariable Long id) {
        return ApiResult.success(certificationMasterService.deactivateMaster(id,
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @GetMapping("/masters/courses")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<List<TrainingCourseMasterView>> trainingCourses(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ApiResult.success(trainingCourseMasterService.list(includeInactive));
    }

    @GetMapping("/masters/courses/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<TrainingCourseMasterView> trainingCourse(@PathVariable Long id) {
        return ApiResult.success(trainingCourseMasterService.get(id));
    }

    @PostMapping("/masters/courses")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<com.ses.entity.TrainingCourse> createTrainingCourse(
            @RequestBody TrainingCourseMasterRequest request) {
        return ApiResult.success(trainingCourseMasterService.create(toTrainingCourseCommand(request),
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @PutMapping("/masters/courses/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<com.ses.entity.TrainingCourse> updateTrainingCourse(
            @PathVariable Long id, @RequestBody TrainingCourseMasterRequest request) {
        return ApiResult.success(trainingCourseMasterService.update(id, toTrainingCourseCommand(request),
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/masters/courses/{id}")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<com.ses.entity.TrainingCourse> deactivateTrainingCourse(@PathVariable Long id) {
        return ApiResult.success(trainingCourseMasterService.deactivate(id,
                com.ses.common.util.SecurityUtils.currentUserId()));
    }

    @PostMapping("/certifications/{recordId}/verify")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<CertificationLifecycleActionView> verifyCertification(
            @PathVariable Long recordId, @RequestBody CertificationVerificationCommand command) {
        EngineerCertification record = engineerCertificationService.verify(recordId, command.expectedVersion(),
                com.ses.common.util.SecurityUtils.currentUserId(), command.evidenceDocumentId(),
                command.evidenceDocumentVersionId(), command.evidenceHash());
        return ApiResult.success(CertificationLifecycleActionView.from(record));
    }

    @PostMapping("/certifications/{recordId}/reject")
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<CertificationLifecycleActionView> rejectCertification(
            @PathVariable Long recordId, @RequestBody CertificationStateCommand command) {
        EngineerCertification record = engineerCertificationService.reject(recordId, command.expectedVersion(),
                com.ses.common.util.SecurityUtils.currentUserId(), command.reason());
        return ApiResult.success(CertificationLifecycleActionView.from(record));
    }

    @GetMapping
    public ApiResult<Page<CertificationLearningGapRow>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long engineerId,
            @RequestParam(required = false) String engineerName,
            @RequestParam(required = false) String engineerStatus,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(required = false) String certificationState,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) SkillGapService.DemandSource demandSource,
            Authentication authentication) {
        return ApiResult.success(queryService.page(filter(engineerId, engineerName, engineerStatus, lifecycleState,
                certificationState, asOf, projectId, demandSource), current, size, authentication));
    }

    @GetMapping("/count")
    public ApiResult<Long> count(@RequestParam(required = false) Long engineerId,
                                 @RequestParam(required = false) String engineerName,
                                 @RequestParam(required = false) String engineerStatus,
                                 @RequestParam(required = false) String lifecycleState,
                                 @RequestParam(required = false) String certificationState,
                                 @RequestParam(required = false) LocalDate asOf,
                                 @RequestParam(required = false) Long projectId,
                                 @RequestParam(required = false) SkillGapService.DemandSource demandSource,
                                 Authentication authentication) {
        return ApiResult.success(queryService.count(filter(engineerId, engineerName, engineerStatus, lifecycleState,
                certificationState, asOf, projectId, demandSource), authentication));
    }

    @GetMapping("/{engineerId}")
    public ApiResult<CertificationLearningGapRow> detail(@PathVariable Long engineerId,
                                                         @RequestParam(required = false) String engineerName,
                                                         @RequestParam(required = false) String engineerStatus,
                                                         @RequestParam(required = false) String lifecycleState,
                                                         @RequestParam(required = false) String certificationState,
                                                         @RequestParam(required = false) LocalDate asOf,
                                                         @RequestParam(required = false) Long projectId,
                                                         @RequestParam(required = false) SkillGapService.DemandSource demandSource,
                                                         Authentication authentication) {
        return ApiResult.success(queryService.detail(engineerId, filter(engineerId, engineerName, engineerStatus,
                lifecycleState, certificationState, asOf, projectId, demandSource), authentication));
    }

    @PostMapping("/training-plans/{planId}/approve")
    public ApiResult<LearningPlan> approveTrainingPlan(@PathVariable Long planId,
                                                       @RequestBody(required = false) ApprovalCommand command,
                                                       Authentication authentication) {
        return ApiResult.success(trainingApprovalService.approve(planId, version(command),
                com.ses.common.util.SecurityUtils.currentUserId(), comment(command), authentication));
    }

    @PostMapping("/training-plans/{planId}/reject")
    public ApiResult<LearningPlan> rejectTrainingPlan(@PathVariable Long planId,
                                                      @RequestBody ApprovalCommand command,
                                                      Authentication authentication) {
        return ApiResult.success(trainingApprovalService.reject(planId, version(command),
                com.ses.common.util.SecurityUtils.currentUserId(), comment(command), authentication));
    }

    @GetMapping("/{engineerId}/certifications/{recordId}/evidence/{documentId}/versions/{versionNo}/download")
    public ResponseEntity<InputStreamResource> downloadEvidence(@PathVariable Long engineerId,
                                                                @PathVariable Long recordId,
                                                                @PathVariable Long documentId,
                                                                @PathVariable Integer versionNo,
                                                                Authentication authentication) {
        var evidence = evidenceAccessService.downloadForManagement(engineerId, recordId, documentId, versionNo,
                authentication);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName(evidence.fileName()) + "\"")
                .contentType(StringUtils.hasText(evidence.contentType())
                        ? MediaType.parseMediaType(evidence.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(evidence.content()));
    }

    @GetMapping("/{engineerId}/ai-candidates")
    public ApiResult<CertificationLearningGapAiView> aiCandidates(@PathVariable Long engineerId,
                                                                  @RequestParam Long projectId,
                                                                  @RequestParam(required = false) LocalDate asOf,
                                                                  @RequestParam(required = false) LocalDate periodFrom,
                                                                  @RequestParam(required = false) LocalDate periodTo,
                                                                  @RequestParam(required = false) SkillGapService.DemandSource demandSource,
                                                                  Authentication authentication) {
        return ApiResult.success(aiService.suggest(engineerId, projectId, asOf, periodFrom, periodTo,
                demandSource, com.ses.common.util.SecurityUtils.currentUserId(), authentication));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long engineerId,
                                         @RequestParam(required = false) String engineerName,
                                         @RequestParam(required = false) String engineerStatus,
                                         @RequestParam(required = false) String lifecycleState,
                                         @RequestParam(required = false) String certificationState,
                                         @RequestParam(required = false) LocalDate asOf,
                                         @RequestParam(required = false) Long projectId,
                                         @RequestParam(required = false) SkillGapService.DemandSource demandSource,
                                         Authentication authentication) {
        List<CertificationLearningGapRow> rows = queryService.export(filter(engineerId, engineerName, engineerStatus,
                lifecycleState, certificationState, asOf, projectId, demandSource), authentication);
        StringBuilder csv = new StringBuilder("engineerId,engineerName,engineerStatus,lifecycleState,certificationCount,trainingCount,gapStatus,gapCount\n");
        for (CertificationLearningGapRow row : rows) {
            csv.append(row.engineerId()).append(',')
                    .append(csv(row.engineerName())).append(',')
                    .append(csv(row.engineerStatus())).append(',')
                    .append(csv(row.lifecycleState())).append(',')
                    .append(row.certifications().size()).append(',')
                    .append(row.trainings().size()).append(',')
                    .append(csv(row.gapStatus())).append(',')
                    .append(row.skillGaps().size()).append('\n');
        }
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''certification-learning-gap.csv")
                .body(body);
    }

    private CertificationLearningGapFilter filter(Long engineerId, String engineerName, String engineerStatus,
                                                   String lifecycleState, String certificationState, LocalDate asOf,
                                                   Long projectId, SkillGapService.DemandSource demandSource) {
        return new CertificationLearningGapFilter(engineerId, engineerName, engineerStatus, lifecycleState,
                certificationState, asOf, projectId, demandSource);
    }

    private String csv(String value) {
        if (!StringUtils.hasText(value)) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String safeFileName(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("[\\\\\"\\r\\n]", "_") : "evidence.bin";
    }

    private Certification toCertification(CertificationMasterRequest request) {
        if (request == null) {
            return null;
        }
        Certification certification = new Certification();
        certification.setTenantId(request.tenantId());
        certification.setDisplayName(request.displayName());
        certification.setIssuerDisplay(request.issuerDisplay());
        certification.setExternalCode(request.externalCode());
        certification.setExpiryType(request.expiryType());
        certification.setExpiryMonths(request.expiryMonths());
        certification.setRuleVersion(request.ruleVersion());
        certification.setActiveFlag(request.activeFlag());
        return certification;
    }

    private TrainingCourseMasterService.TrainingCourseCommand toTrainingCourseCommand(
            TrainingCourseMasterRequest request) {
        if (request == null) {
            return null;
        }
        return new TrainingCourseMasterService.TrainingCourseCommand(request.tenantId(), request.provider(),
                request.name(), request.description(), request.costJpy(), request.periodDays(), request.capacity(),
                request.activeFlag(), request.version(), request.requiredSkillIds());
    }

    private Integer version(ApprovalCommand command) { return command == null ? null : command.expectedVersion(); }
    private String comment(ApprovalCommand command) { return command == null ? null : command.comment(); }

    public record ApprovalCommand(Integer expectedVersion, String comment) { }

    public record CertificationMasterRequest(String tenantId, String displayName, String issuerDisplay,
                                             String externalCode, String expiryType, Integer expiryMonths,
                                             Integer ruleVersion, Integer activeFlag) { }

    public record CertificationVerificationCommand(Integer expectedVersion, Long evidenceDocumentId,
                                                   Long evidenceDocumentVersionId, String evidenceHash) { }

    public record CertificationStateCommand(Integer expectedVersion, String reason) { }

    public record TrainingCourseMasterRequest(String tenantId, String provider, String name, String description,
                                              java.math.BigDecimal costJpy, Integer periodDays, Integer capacity,
                                              Integer activeFlag, Integer version, List<Long> requiredSkillIds) { }
}
