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

    /** 書類IDから契約IDを解決し、親契約のスコープを検証する（R3R-31/32）。 */
    private void assertDocumentAllowed(Long documentId) {
        ContractDocument doc = service.getById(documentId);
        if (doc == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(doc.getContractId());
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
        dataScopeService.assertAllowedContract(contractId);
        return ApiResult.success(service.lambdaQuery().eq(ContractDocument::getContractId, contractId).list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<ContractDocument> create(@RequestParam Long contractId,
                                               @RequestParam Long templateId,
                                               @RequestParam String recipientName,
                                               @RequestParam String recipientEmail) {
        dataScopeService.assertAllowedContract(contractId);
        return ApiResult.success(service.create(contractId, templateId, recipientName, recipientEmail));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAnyRole('管理者','営業','マネージャー')")
    public ApiResult<Boolean> send(@PathVariable Long id) {
        assertDocumentAllowed(id);
        service.send(id);
        return ApiResult.success(true);
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ApiResult<Boolean> sync(@PathVariable Long id) {
        assertDocumentAllowed(id);
        service.sync(id);
        return ApiResult.success(true);
    }
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('管理者','営業','HR','マネージャー')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        assertDocumentAllowed(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(service.download(id));
    }
}
