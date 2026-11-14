package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.importcenter.metadata.ImportPreviewPage;
import com.ses.importcenter.metadata.ImportValidationResultService;
import com.ses.importcenter.metadata.ImportValidationRunService;
import com.ses.importcenter.metadata.ImportValidationRunSummary;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * NF-06 A1a: upload済みjobの検証・preview・error export API。apply系（apply・restart・rollback・compensation）は
 * 持たない（B-NF06-3）。認可・scope・監査はservice側で操作ごとに再確認する。
 */
@RestController
@RequestMapping("/api/import-center")
public class ImportCenterValidationApiController {
    private final ImportValidationRunService runs;
    private final ImportValidationResultService results;

    public ImportCenterValidationApiController(ImportValidationRunService runs, ImportValidationResultService results) {
        this.runs = runs;
        this.results = results;
    }

    /** 検証を同期実行する（120秒のsoft上限、超過は503）。UPLOADEDのjobはbodyの{@code mappingId}を束縛する。 */
    @PostMapping("/jobs/{id}/validations")
    public ApiResult<ImportValidationRunSummary> validate(@PathVariable long id,
                                                          @RequestBody(required = false) ValidationRequest request) {
        return ApiResult.success(runs.validate(id, request == null ? null : request.mappingId()));
    }

    /** 公開済み検証runのpreview（マスク済み）。sizeは上限200へ丸める。 */
    @GetMapping("/jobs/{id}/preview")
    public ApiResult<ImportPreviewPage> preview(@PathVariable long id,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "50") long size,
                                                @RequestParam(required = false) String disposition) {
        return ApiResult.success(results.preview(id, page, size, disposition));
    }

    /** REJECTED行のfinding codeをCSVでstreamする。事前確認（認可・公開run・必須監査）が済むまで何も書かない。 */
    @GetMapping("/jobs/{id}/error-export")
    public void exportErrors(@PathVariable long id, HttpServletResponse response) throws IOException {
        ImportValidationResultService.ErrorExport export = results.prepareErrorExport(id);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"import-job-" + export.jobId() + "-errors.csv\"");
        response.setHeader("Cache-Control", "no-store");
        results.writeErrorExport(export, response.getOutputStream());
    }

    /** 検証要求。MAPPED以降のjobでは省略するか、束縛済みのmappingと同じ値にする。 */
    public record ValidationRequest(Long mappingId) { }
}
