package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.contractdocument.CloudSignOperationDto;
import com.ses.dto.contractdocument.ContractDocumentDetailDto;
import com.ses.dto.contractdocument.ContractDocumentListDto;
import com.ses.entity.ContractDocument;
import com.ses.entity.ContractTemplate;
import com.ses.mapper.ContractTemplateMapper;
import com.ses.service.ContractDocumentService;
import com.ses.service.cloudsign.CloudSignArtifactService;
import com.ses.service.cloudsign.CloudSignSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 契約書API（HFP-02-07）。
 * entityを返さずallow-list DTOのみ。list/detail/artifact downloadはno-store。
 * 送信/syncはqueue受付または状態同期であり、provider完了を偽装しない。
 */
@RestController
@RequestMapping("/api/contract-documents")
@RequiredArgsConstructor
public class ContractDocumentApiController {
    private final ContractDocumentService service;
    private final ContractTemplateMapper templates;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final CloudSignSyncService cloudSignSyncService;
    private final CloudSignArtifactService cloudSignArtifactService;

    /** 書類IDから契約IDを解決し、親契約のスコープを検証する（R3R-31/32）。 */
    private void assertDocumentAllowed(Long documentId) {
        ContractDocument doc = service.getById(documentId);
        if (doc == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        assertContractVisible(doc.getContractId());
    }

    private void assertContractVisible(Long contractId) {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedContractIds() : null;
        java.util.Set<Long> allowed = organizationScopeService.hasFullAccess()
                ? (dataIds == null ? null : new java.util.HashSet<>(dataIds))
                : organizationScopeService.intersectWithDataScope(
                        organizationScopeService.allowedContractIds(java.time.LocalDate.now()), dataIds);
        if (allowed != null && !allowed.contains(contractId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(contractId);
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ApiResult<List<ContractTemplate>> templates() {
        return ApiResult.success(templates.selectList(null));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<ContractTemplate> createTemplate(@RequestBody ContractTemplate template) {
        if (template.getHtmlContent() == null || template.getHtmlContent().matches("(?is).*<script.*")) {
            throw new IllegalArgumentException("許可されないHTMLです");
        }
        if (template.getVersion() == null) template.setVersion(1);
        if (template.getActiveFlag() == null) template.setActiveFlag(1);
        templates.insert(template);
        return ApiResult.success(template);
    }

    @GetMapping("/contract/{contractId}")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ResponseEntity<ApiResult<List<ContractDocumentListDto>>> list(@PathVariable Long contractId) {
        assertContractVisible(contractId);
        List<ContractDocumentListDto> rows = service.lambdaQuery()
                .eq(ContractDocument::getContractId, contractId)
                .list()
                .stream()
                .map(ContractDocumentListDto::of)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(rows));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ResponseEntity<ApiResult<ContractDocumentDetailDto>> detail(@PathVariable Long id) {
        assertDocumentAllowed(id);
        ContractDocument doc = service.getById(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(ContractDocumentDetailDto.of(doc)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<ContractDocumentDetailDto> create(@RequestParam Long contractId,
                                                       @RequestParam Long templateId,
                                                       @RequestParam String recipientName,
                                                       @RequestParam String recipientEmail) {
        assertContractVisible(contractId);
        ContractDocument created = service.create(contractId, templateId, recipientName, recipientEmail);
        return ApiResult.success(ContractDocumentDetailDto.of(created));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<CloudSignOperationDto> send(@PathVariable Long id,
                                                 @RequestBody com.ses.dto.cloudsign.ConfirmedSendRequest request) {
        assertDocumentAllowed(id);
        // durable queue受付であり、provider送信完了ではない（HFP-02-AC-10-02）
        ContractDocument queued = service.queueSend(id, request);
        return ApiResult.success("送信処理を受け付けました", new CloudSignOperationDto(
                queued.getId(), queued.getOperationId(), queued.getDispatchState()));
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ResponseEntity<ApiResult<ContractDocumentDetailDto>> sync(@PathVariable Long id) {
        assertDocumentAllowed(id);
        // HRは参照のみ（HFP-02-AC-08-01）。manual syncも更新系のためHRは拒否。
        cloudSignSyncService.syncDocument(id);
        ContractDocument doc = service.getById(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(ContractDocumentDetailDto.of(doc)));
    }

    /** 三artifact別download（source/signed/certificate）。no-store・attachment・scope検証済み。 */
    @GetMapping("/{id}/artifacts/{kind}")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> artifact(
            @PathVariable Long id, @PathVariable String kind) {
        assertDocumentAllowed(id);
        ContractDocument doc = service.getById(id);
        switch (kind) {
            case "source" -> {
                return attachment(new java.io.ByteArrayInputStream(service.download(id)),
                        "document-" + id + ".pdf");
            }
            case "signed" -> {
                CloudSignArtifactService.ArtifactDownload signed = cloudSignArtifactService.downloadSigned(doc);
                return attachment(signed.stream(), signed.fileName());
            }
            case "certificate" -> {
                CloudSignArtifactService.ArtifactDownload cert = cloudSignArtifactService.downloadCertificate(doc);
                return attachment(cert.stream(), cert.fileName());
            }
            default -> throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private ResponseEntity<org.springframework.core.io.InputStreamResource> attachment(
            java.io.InputStream body, String fileName) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .body(new org.springframework.core.io.InputStreamResource(body));
    }
}
