package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.dto.certificationlearninggap.CertificationEvidenceView;
import com.ses.dto.certificationlearninggap.CertificationSelfDashboard;
import com.ses.dto.certificationlearninggap.CertificationSelfView;
import com.ses.dto.certificationlearninggap.LearningPlanSelfView;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.entity.TrainingEnrollment;
import com.ses.service.certificationlearninggap.CertificationLearningGapSelfService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 要員本人の資格申請・学習計画API。engineerIdはaccount linkから解決し、入力値を信用しない。 */
@RestController
@RequestMapping("/api/my/certification-learning-gap")
@RequiredArgsConstructor
public class MyCertificationLearningGapApiController {

    private final CertificationLearningGapSelfService selfService;
    private final com.ses.service.certificationlearninggap.CertificationEvidenceAccessService evidenceAccessService;

    @GetMapping
    public ApiResult<CertificationSelfDashboard> dashboard() {
        return ApiResult.success(selfService.dashboard(userId()));
    }

    @GetMapping("/certifications")
    public ApiResult<List<CertificationSelfView>> certifications() {
        return ApiResult.success(selfService.certifications(userId()));
    }

    @GetMapping("/certifications/{recordId}")
    public ApiResult<CertificationSelfView> certification(@PathVariable Long recordId) {
        return ApiResult.success(selfService.certification(userId(), recordId));
    }

    @GetMapping("/certifications/{recordId}/evidence/{documentId}/versions/{versionNo}/download")
    public ResponseEntity<InputStreamResource> downloadEvidence(@PathVariable Long recordId,
                                                                @PathVariable Long documentId,
                                                                @PathVariable Integer versionNo) {
        var evidence = evidenceAccessService.downloadForSelf(userId(), recordId, documentId, versionNo);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName(evidence.fileName()) + "\"")
                .contentType(StringUtils.hasText(evidence.contentType())
                        ? MediaType.parseMediaType(evidence.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(evidence.content()));
    }

    @PostMapping("/certifications")
    public ApiResult<EngineerCertificationViewDto> apply(@RequestBody CertificationApplyRequest request) {
        return ApiResult.success(selfService.applyCertification(userId(), request.engineerId(), request.certificationId(),
                request.acquiredOn(), request.expiresOn(), request.certificateNumber()));
    }

    @PostMapping(value = "/certifications/{recordId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<CertificationLearningGapSelfService.CertificationEvidenceUpload> uploadEvidence(
            @PathVariable Long recordId, @RequestParam("file") MultipartFile file) {
        return ApiResult.success(selfService.uploadEvidence(userId(), recordId, file));
    }

    @PostMapping("/certifications/{recordId}/withdraw")
    public ApiResult<EngineerCertification> withdraw(@PathVariable Long recordId,
                                                     @RequestBody(required = false) StateCommand command) {
        return ApiResult.success(selfService.withdrawCertification(userId(), recordId, version(command), reason(command)));
    }

    @PostMapping("/certifications/{recordId}/correct")
    public ApiResult<EngineerCertification> correct(@PathVariable Long recordId,
                                                    @RequestBody CertificationCorrectionRequest request) {
        return ApiResult.success(selfService.correctCertification(userId(), recordId, request.expectedVersion(),
                request.acquiredOn(), request.expiresOn(), request.reason()));
    }

    @PostMapping("/certifications/{recordId}/resubmit")
    public ApiResult<EngineerCertificationViewDto> resubmit(@PathVariable Long recordId,
                                                            @RequestBody(required = false) ResubmitRequest request) {
        return ApiResult.success(selfService.resubmitCertification(userId(), recordId,
                request == null ? null : request.certificateNumber()));
    }

    @GetMapping("/learning-plans")
    public ApiResult<List<LearningPlanSelfView>> learningPlans() {
        return ApiResult.success(selfService.learningPlans(userId()));
    }

    @GetMapping("/learning-plans/{planId}")
    public ApiResult<LearningPlanSelfView> learningPlan(@PathVariable Long planId) {
        return ApiResult.success(selfService.learningPlan(userId(), planId));
    }

    @PostMapping("/learning-plans")
    public ApiResult<LearningPlan> createPlan(@RequestBody LearningPlan draft) {
        return ApiResult.success(selfService.createPlan(userId(), draft));
    }

    @PutMapping("/learning-plans/{planId}")
    public ApiResult<LearningPlan> updatePlan(@PathVariable Long planId, @RequestBody LearningPlan draft) {
        return ApiResult.success(selfService.updatePlan(userId(), planId, draft.getVersion(), draft));
    }

    @PostMapping("/learning-plans/{planId}/submit")
    public ApiResult<LearningPlan> submitPlan(@PathVariable Long planId, @RequestBody(required = false) PlanCommand command) {
        return ApiResult.success(selfService.submitPlan(userId(), planId, version(command),
                command == null ? null : command.zeroCostReason()));
    }

    @PostMapping("/learning-plans/{planId}/withdraw")
    public ApiResult<LearningPlan> withdrawPlan(@PathVariable Long planId, @RequestBody PlanCommand command) {
        return ApiResult.success(selfService.withdrawPlan(userId(), planId, version(command), reason(command)));
    }

    @PostMapping("/learning-plans/{planId}/resubmit")
    public ApiResult<LearningPlanSelfView> resubmitPlan(@PathVariable Long planId) {
        return ApiResult.success(selfService.resubmitPlan(userId(), planId));
    }

    @PostMapping("/learning-plans/{planId}/enrollments")
    public ApiResult<TrainingEnrollment> enroll(@PathVariable Long planId, @RequestBody EnrollmentRequest request) {
        return ApiResult.success(selfService.enroll(userId(), planId, request.courseId()));
    }

    @PostMapping("/enrollments/{enrollmentId}/start")
    public ApiResult<TrainingEnrollment> startEnrollment(@PathVariable Long enrollmentId,
                                                         @RequestBody StateCommand command) {
        return ApiResult.success(selfService.startEnrollment(userId(), enrollmentId, version(command)));
    }

    @PostMapping("/enrollments/{enrollmentId}/complete")
    public ApiResult<TrainingEnrollment> completeEnrollment(@PathVariable Long enrollmentId,
                                                             @RequestBody EnrollmentCompletionRequest request) {
        return ApiResult.success(selfService.completeEnrollment(userId(), enrollmentId, request.expectedVersion(),
                request.completedOn(), request.score()));
    }

    @PostMapping("/enrollments/{enrollmentId}/cancel")
    public ApiResult<TrainingEnrollment> cancelEnrollment(@PathVariable Long enrollmentId,
                                                          @RequestBody StateCommand command) {
        return ApiResult.success(selfService.cancelEnrollment(userId(), enrollmentId, version(command), reason(command)));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpServletResponse response) {
        StringBuilder csv = new StringBuilder("certificationId,certificationName,acquiredOn,expiresOn,state,evidenceCount\n");
        for (CertificationSelfView item : selfService.certifications(userId())) {
            EngineerCertificationViewDto record = item.record();
            csv.append(record.getId()).append(',').append(csv(record.getCertificationDisplayName())).append(',')
                    .append(value(record.getAcquiredOn())).append(',').append(value(record.getExpiresOn())).append(',')
                    .append(csv(record.getRecordState())).append(',').append(item.evidences().size()).append('\n');
        }
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''my-certification-learning-gap.csv")
                .body(body);
    }

    private Long userId() { return SecurityUtils.currentUserId(); }

    private Integer version(StateCommand command) { return command == null ? null : command.expectedVersion(); }
    private Integer version(PlanCommand command) { return command == null ? null : command.expectedVersion(); }
    private String reason(StateCommand command) { return command == null ? null : command.reason(); }
    private String reason(PlanCommand command) { return command == null ? null : command.reason(); }
    private String value(Object value) { return value == null ? "" : value.toString(); }
    private String csv(String value) { return !StringUtils.hasText(value) ? "" : "\"" + value.replace("\"", "\"\"") + "\""; }
    private String safeFileName(String value) { return StringUtils.hasText(value) ? value.replaceAll("[\\\\\"\\r\\n]", "_") : "evidence.bin"; }

    public record CertificationApplyRequest(Long engineerId, Long certificationId, LocalDate acquiredOn,
                                            LocalDate expiresOn, String certificateNumber) { }
    public record CertificationCorrectionRequest(Integer expectedVersion, LocalDate acquiredOn, LocalDate expiresOn,
                                                String reason) { }
    public record ResubmitRequest(String certificateNumber) { }
    public record StateCommand(Integer expectedVersion, String reason) { }
    public record PlanCommand(Integer expectedVersion, String reason, String zeroCostReason) { }
    public record EnrollmentRequest(Long courseId) { }
    public record EnrollmentCompletionRequest(Integer expectedVersion, LocalDate completedOn,
                                             java.math.BigDecimal score) { }
}
