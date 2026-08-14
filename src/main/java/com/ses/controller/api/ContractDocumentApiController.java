package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.ContractDocument;
import com.ses.entity.ContractTemplate;
import com.ses.mapper.ContractTemplateMapper;
import com.ses.service.ContractDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/contract-documents")
@RequiredArgsConstructor
public class ContractDocumentApiController {
    private final ContractDocumentService service;
    private final ContractTemplateMapper templates;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.cloudsign.CloudSignSyncService cloudSignSyncService;

    /** 書類IDから契約IDを解決し、親契約のスコープを検証する（R3R-31/32）。 */
    private void assertDocumentAllowed(Long documentId) {
        ContractDocument doc = service.getById(documentId);
        if (doc == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
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
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
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
    public ApiResult<List<ContractDocument>> list(@PathVariable Long contractId) {
        assertContractVisible(contractId);
        return ApiResult.success(service.lambdaQuery().eq(ContractDocument::getContractId, contractId).list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<ContractDocument> create(@RequestParam Long contractId,
                                               @RequestParam Long templateId,
                                               @RequestParam String recipientName,
                                               @RequestParam String recipientEmail) {
        assertContractVisible(contractId);
        return ApiResult.success(service.create(contractId, templateId, recipientName, recipientEmail));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<com.ses.entity.ContractDocument> send(@PathVariable Long id,
                                                           @RequestBody com.ses.dto.cloudsign.ConfirmedSendRequest request) {
        assertDocumentAllowed(id);
        // durable queue受付であり、provider送信完了ではない（HFP-02-AC-10-02）
        return ApiResult.success("送信処理を受け付けました", service.queueSend(id, request));
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<Boolean> sync(@PathVariable Long id) {
        assertDocumentAllowed(id);
        // HRは参照のみ（HFP-02-AC-08-01）。manual syncも更新系のためHRは拒否。
        cloudSignSyncService.syncDocument(id);
        return ApiResult.success(true);
    }
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        assertDocumentAllowed(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(service.download(id));
    }
}
