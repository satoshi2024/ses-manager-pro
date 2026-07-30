package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.document.DocumentDetailDTO;
import com.ses.dto.document.DocumentListDTO;
import com.ses.dto.document.DocumentSearchQuery;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.service.DocumentService;
import com.ses.service.security.impl.FileScopeValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;

/**
 * 文書台帳 REST API コントローラー（T024）。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentApiController {

    private final DocumentService documentService;
    private final com.ses.service.DocumentExportService documentExportService;
    private final FileScopeValidationService fileScopeValidationService;

    @GetMapping("/export/zip")
    public void exportZip(DocumentSearchQuery query, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tax_documents_export.zip\"");
        documentExportService.exportTaxZip(query, response.getOutputStream());
    }

    @GetMapping
    public ApiResult<Page<DocumentListDTO>> list(DocumentSearchQuery query) {
        Page<DocumentListDTO> result = documentService.searchDocuments(query);
        return ApiResult.success(result);
    }

    @GetMapping("/{id}")
    public ApiResult<DocumentDetailDTO> detail(@PathVariable("id") Long id) {
        DocumentDetailDTO detail = documentService.getDocumentDetail(id);
        return ApiResult.success(detail);
    }

    @GetMapping("/{id}/versions/{versionNo}/download")
    public ResponseEntity<InputStreamResource> downloadVersion(
            @PathVariable("id") Long id,
            @PathVariable("versionNo") Integer versionNo) {

        DocumentDetailDTO detail = documentService.getDocumentDetail(id);
        String storageKey = null;
        String fileName = "document.pdf";
        if (detail.getVersions() != null) {
            for (var v : detail.getVersions()) {
                if (v.getVersionNo().equals(versionNo)) {
                    storageKey = v.getStorageKey();
                    fileName = v.getOriginalName();
                    break;
                }
            }
        }

        if (storageKey != null) {
            fileScopeValidationService.assertDownloadAllowed(storageKey);
        }

        InputStream stream = documentService.download(id, versionNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    @PostMapping("/{id}/legal-hold")
    public ApiResult<Void> setLegalHold(
            @PathVariable("id") Long id,
            @RequestParam("hold") boolean hold,
            @RequestParam(value = "reason", required = false) String reason) {
        documentService.placeLegalHold(id, hold, reason != null ? reason : "API要求");
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/disposal")
    public ApiResult<DocumentDisposalRequest> requestDisposal(
            @PathVariable("id") Long id,
            @RequestParam("reason") String reason) {
        DocumentDisposalRequest req = documentService.requestDisposal(id, reason);
        return ApiResult.success(req);
    }

    @PostMapping("/disposal/{requestId}/approve")
    public ApiResult<Void> approveDisposal(@PathVariable("requestId") Long requestId) {
        documentService.approveDisposal(requestId);
        return ApiResult.success(null);
    }

    @PostMapping("/disposal/{requestId}/reject")
    public ApiResult<Void> rejectDisposal(
            @PathVariable("requestId") Long requestId,
            @RequestParam("reason") String reason) {
        documentService.rejectDisposal(requestId, reason);
        return ApiResult.success(null);
    }

    @PostMapping("/disposal/{requestId}/execute")
    public ApiResult<Void> executeDisposal(@PathVariable("requestId") Long requestId) {
        documentService.executeDisposal(requestId);
        return ApiResult.success(null);
    }
}
