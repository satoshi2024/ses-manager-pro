package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ComplianceDocumentDeliveryDto;
import com.ses.dto.compliance.ComplianceDocumentGenerateRequest;
import com.ses.service.ComplianceDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 法定帳票の生成・交付・受領確認・ダウンロードAPI（T064 B1）。
 * /api/contracts 配下のため契約メニュー（4管理ロール）の権限で保護される。
 * 営業は生成・確認・ダウンロード不可（403）。PDFはscanStatus CLEAN確認後に配信する（fail-closed）。
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ComplianceDocumentApiController {

    private final ComplianceDocumentService complianceDocumentService;

    @GetMapping("/{id}/compliance-documents")
    public ApiResult<List<ComplianceDocumentDeliveryDto>> list(@PathVariable Long id) {
        return ApiResult.success(complianceDocumentService.list(id));
    }

    @PostMapping("/{id}/compliance-documents/generate")
    public ApiResult<ComplianceDocumentDeliveryDto> generate(@PathVariable Long id,
                                                             @RequestBody ComplianceDocumentGenerateRequest request) {
        return ApiResult.success(complianceDocumentService.generate(id, request));
    }

    @PostMapping("/{id}/compliance-documents/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id,
                                          @RequestBody ComplianceDocumentGenerateRequest request) {
        byte[] pdf = complianceDocumentService.preview(id, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview-" + (request == null ? "doc" : request.getDocumentType()) + "-" + id + ".pdf\"")
                .header("X-Compliance-Preview", "true")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/{id}/compliance-documents/{deliveryId}/confirm")
    public ApiResult<ComplianceDocumentDeliveryDto> confirm(@PathVariable Long id,
                                                            @PathVariable Long deliveryId,
                                                            @RequestParam(required = false) String note) {
        return ApiResult.success(complianceDocumentService.confirm(id, deliveryId, note));
    }

    @GetMapping("/{id}/compliance-documents/{deliveryId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long deliveryId) {
        byte[] pdf = complianceDocumentService.download(id, deliveryId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"compliance-" + deliveryId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
