package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.metadata.ImportCsvIngestionService;
import com.ses.importcenter.metadata.ImportMetadataService;
import com.ses.importcenter.metadata.ImportRawUploadHeaders;
import com.ses.importcenter.metadata.ImportUploadJobDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CUSTOMER専用raw CSV import API。bodyはservice admission完了後にだけ取得する。 */
@RestController
@RequestMapping("/api/import-center")
public class ImportCenterApiController {
    private final ImportCsvIngestionService ingestion;
    private final ImportMetadataService metadata;

    public ImportCenterApiController(ImportCsvIngestionService ingestion, ImportMetadataService metadata) {
        this.ingestion = ingestion;
        this.metadata = metadata;
    }

    @PostMapping(value = "/uploads", consumes = "text/csv")
    public ApiResult<ImportUploadJobDetail> upload(HttpServletRequest request) {
        String sourceCode = request.getHeader("X-Import-Source-Code");
        String rawEncoding = request.getHeader("X-Import-Encoding");
        ImportEncoding encoding;
        try {
            encoding = ImportEncoding.valueOf(rawEncoding);
        } catch (RuntimeException invalid) {
            throw BusinessException.of(400, "error.import.invalidUploadHeaders");
        }
        ImportRawUploadHeaders headers = new ImportRawUploadHeaders(sourceCode, encoding);
        return ApiResult.success(ingestion.upload(headers, request::getInputStream));
    }

    @GetMapping("/jobs/{id}")
    public ApiResult<ImportUploadJobDetail> getJob(@PathVariable long id) {
        return ApiResult.success(metadata.getUploadedJobDetail(id));
    }
}
