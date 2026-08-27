package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportTemplateCreateRequest;
import com.ses.dto.report.ReportTemplateVersionCreateRequest;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.entity.ReportTemplate;
import com.ses.entity.ReportTemplateVersion;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;
import java.util.List;

/** 定期管理レポートのtemplate/preview/run API。 */
@RestController
@RequestMapping("/api/management-reports")
@RequiredArgsConstructor
public class ManagementReportApiController {

    private final ReportTemplateService templateService;
    private final ReportSnapshotService snapshotService;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ReportDocumentService reportDocumentService;

    @GetMapping("/templates")
    public ApiResult<List<ReportTemplate>> templates() {
        return ApiResult.success(templateService.listTemplates());
    }

    @GetMapping("/templates/{templateId}/versions")
    public ApiResult<List<ReportTemplateVersion>> versions(@PathVariable Long templateId) {
        return ApiResult.success(templateService.listVersions(templateId));
    }

    @PostMapping("/templates")
    public ApiResult<ReportTemplate> createTemplate(@Valid @RequestBody ReportTemplateCreateRequest request) {
        return ApiResult.success(templateService.createTemplate(request.getTemplateKey(), request.getTemplateName()));
    }

    @PostMapping("/templates/{templateId}/versions")
    public ApiResult<ReportTemplateVersion> createVersion(@PathVariable Long templateId,
                                                          @RequestBody(required = false)
                                                          ReportTemplateVersionCreateRequest request) {
        return ApiResult.success(templateService.createVersion(templateId, request));
    }

    @PostMapping("/versions/{versionId}/publish")
    public ApiResult<ReportTemplateVersion> publish(@PathVariable Long versionId) {
        return ApiResult.success(templateService.publishVersion(versionId));
    }

    @PostMapping("/runs")
    public ApiResult<ReportGenerationResult> generate(@RequestBody ReportRunRequest request) {
        ReportGenerationCommand command = new ReportGenerationCommand(request.templateVersionId(),
                YearMonth.parse(request.period()), request.cutoffKind(), false, null, false, null,
                null, request.recipientPreviewHash());
        return ApiResult.success(snapshotService.generate(command));
    }

    @PostMapping("/templates/{versionId}/recipient-preview")
    public ApiResult<ReportRecipientPreviewResult> preview(@PathVariable Long versionId,
                                                           @RequestBody ReportPreviewRequest request) {
        return ApiResult.success(recipientPreviewService.preview(versionId, YearMonth.parse(request.period())));
    }

    @PostMapping("/runs/{runId}/regenerate")
    public ApiResult<ReportGenerationResult> regenerate(@PathVariable Long runId) {
        ReportRun previous = snapshotService.findRun(runId);
        return ApiResult.success(snapshotService.generate(new ReportGenerationCommand(
                previous.getTemplateVersionId(), YearMonth.from(previous.getPeriodFrom()),
                previous.getCutoffKind(), true, previous.getScheduleId(), false, null,
                previous.getId(), null)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResult<ReportGenerationResult> run(@PathVariable Long runId) {
        ReportRun run = snapshotService.findRun(runId);
        List<ReportSectionSnapshot> sections = snapshotService.listSections(runId);
        return ApiResult.success(new ReportGenerationResult(run, sections, true));
    }

    @PostMapping("/runs/{runId}/documents/{format}")
    public ApiResult<com.ses.dto.report.ReportDocumentArtifact> registerDocument(@PathVariable Long runId,
                                                                                   @PathVariable String format) {
        return ApiResult.success(reportDocumentService.register(runId, format));
    }

    @GetMapping("/runs/{runId}/documents/{format}/preview")
    public ResponseEntity<byte[]> previewDocument(@PathVariable Long runId, @PathVariable String format) {
        String normalized = format.toUpperCase(java.util.Locale.ROOT);
        byte[] bytes = reportDocumentService.render(runId, normalized);
        MediaType mediaType = "PDF".equals(normalized) ? MediaType.APPLICATION_PDF
                : "XLSX".equals(normalized)
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv; charset=UTF-8");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=management-report-" + runId + "." + format.toLowerCase())
                .contentType(mediaType).body(bytes);
    }

    public record ReportRunRequest(Long templateVersionId, String period, String cutoffKind,
                                   String recipientPreviewHash) {
    }

    public record ReportPreviewRequest(String period) {
    }
}
