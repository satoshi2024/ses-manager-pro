package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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

    @PutMapping("/versions/{versionId}")
    public ApiResult<ReportTemplateVersion> updateVersion(@PathVariable Long versionId,
                                                          @RequestBody ReportTemplateVersionCreateRequest request) {
        return ApiResult.success(templateService.updateVersion(versionId, request));
    }

    @PostMapping("/runs")
    public ApiResult<ReportGenerationResult> generate(@RequestBody ReportRunRequest request) {
        if (request == null || request.recipientPreviewHash() == null
                || request.recipientPreviewHash().isBlank()) {
            throw BusinessException.of(400, "error.managementReport.recipientPreviewRequired");
        }
        ReportGenerationCommand command = new ReportGenerationCommand(request.templateVersionId(),
                YearMonth.parse(request.period()), request.cutoffKind(), false, null, false, null,
                null, request.recipientPreviewHash(), null);
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
        snapshotService.assertAccessible(previous);
        // 再生成では元runのscopeをそのまま母集団にせず、現在principalのscopeでpreviewを取り直す。
        // generate側も直前に現在scopeを再解決し、異動済みentity IDを再利用しない。
        ReportRecipientPreviewResult preview = recipientPreviewService.preview(
                previous.getTemplateVersionId(), YearMonth.from(previous.getPeriodFrom()));
        return ApiResult.success(snapshotService.generate(new ReportGenerationCommand(
                previous.getTemplateVersionId(), YearMonth.from(previous.getPeriodFrom()),
                previous.getCutoffKind(), true, previous.getScheduleId(), false, null,
                previous.getId(), preview.getPreviewHash(), null)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResult<ReportGenerationResult> run(@PathVariable Long runId) {
        ReportRun run = snapshotService.findRun(runId);
        snapshotService.assertAccessible(run);
        List<ReportSectionSnapshot> sections = snapshotService.listSections(runId);
        return ApiResult.success(new ReportGenerationResult(run, sections, true));
    }

    @PostMapping("/runs/{runId}/documents/{format}")
    public ApiResult<com.ses.dto.report.ReportDocumentArtifact> registerDocument(@PathVariable Long runId,
                                                                                   @PathVariable String format) {
        snapshotService.assertAccessible(snapshotService.findRun(runId));
        return ApiResult.success(reportDocumentService.register(runId, format));
    }

    public record ReportRunRequest(Long templateVersionId, String period, String cutoffKind,
                                   String recipientPreviewHash) {
    }

    public record ReportPreviewRequest(String period) {
    }
}
