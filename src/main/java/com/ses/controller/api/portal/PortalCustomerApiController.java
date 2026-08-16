package com.ses.controller.api.portal;

import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalAcceptanceActionRequest;
import com.ses.dto.portal.PortalAcceptanceDto;
import com.ses.dto.portal.PortalContractDto;
import com.ses.dto.portal.PortalInvoiceDto;
import com.ses.dto.portal.PortalInvoiceRegisterRequest;
import com.ses.dto.portal.PortalQuotationDto;
import com.ses.dto.portal.PortalSalesOrderDto;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuthorizationService;
import com.ses.service.portal.PortalCustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
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

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 顧客ポータルAPI（/api/portal/customer/**。R2）。
 * 全endpointはPortalAuthorizationServiceが解決した自組織customer_idをSQL境界に使い、
 * 他組織のID直接指定は404秘匿になる（R4.3・R5）。portal user以外は到達できない（portal専用chain）。
 */
@RestController
@RequestMapping("/api/portal/customer")
@RequiredArgsConstructor
public class PortalCustomerApiController {

    private final PortalCustomerService customerService;
    private final PortalAuthorizationService authorizationService;
    private final com.ses.service.portal.PortalAuditService auditService;

    private void audit(String action, String targetType, Long targetId, jakarta.servlet.http.HttpServletRequest request) {
        try {
            auditService.record(authorizationService.requireUser(), action, targetType, targetId, request);
        } catch (RuntimeException ignored) {
            // 監査はbest-effort
        }
    }

    private Long customerId() {
        PortalLoginUser user = authorizationService.requireUser();
        if (!authorizationService.isCustomerOrg(user)) {
            throw com.ses.common.exception.BusinessException.of(403, "error.forbidden");
        }
        return user.getCustomerId();
    }

    // ===== 見積 =====

    @GetMapping("/quotations")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalQuotationDto>> quotations(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResult.success(customerService.quotations(current, size, customerId()));
    }

    @GetMapping("/quotations/{id}/download")
    public ResponseEntity<byte[]> quotationPdf(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_QUOTATION", "QUOTATION", id, request);
        byte[] bytes = customerService.quotationPdf(id, customerId(), Locale.getDefault());
        return pdfResponse(bytes, "見積書_" + id + ".pdf");
    }

    // ===== 注文請 =====

    @GetMapping("/sales-orders")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalSalesOrderDto>> salesOrders(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResult.success(customerService.salesOrders(current, size, customerId()));
    }

    @GetMapping("/sales-orders/{id}/acknowledgement/download")
    public ResponseEntity<InputStreamResource> acknowledgementPdf(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_SALES_ORDER", "SALES_ORDER", id, request);
        InputStream stream = customerService.acknowledgementPdf(id, customerId());
        return streamResponse(stream, "注文請書_" + id + ".pdf");
    }

    // ===== 契約 =====

    @GetMapping("/contracts")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalContractDto>> contracts(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResult.success(customerService.contracts(current, size, customerId(), status));
    }

    @GetMapping("/contracts/{id}")
    public ApiResult<PortalContractDto> contract(@PathVariable Long id) {
        return ApiResult.success(customerService.contract(id, customerId()));
    }

    @GetMapping("/contracts/{id}/document/download")
    public ResponseEntity<InputStreamResource> contractDocumentPdf(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_CONTRACT", "CONTRACT", id, request);
        InputStream stream = customerService.contractDocumentPdf(id, customerId());
        return streamResponse(stream, "契約書_" + id + ".pdf");
    }

    // ===== 作業報告・検収 =====

    @GetMapping("/acceptances")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalAcceptanceDto>> acceptances(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String workMonth,
            @RequestParam(required = false) String status) {
        return ApiResult.success(customerService.acceptances(current, size, customerId(), workMonth, status));
    }

    @GetMapping("/acceptances/{id}")
    public ApiResult<PortalAcceptanceDto> acceptance(@PathVariable Long id) {
        return ApiResult.success(customerService.acceptance(id, customerId()));
    }

    /** 検収（提出済→検収済。order specの状態CAS。二重検収は2件目が409: R5） */
    @PostMapping("/acceptances/{id}/accept")
    public ApiResult<PortalAcceptanceDto> accept(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest httpRequest,
                                                 @RequestBody(required = false) PortalAcceptanceActionRequest request) {
        Long contactId = request == null ? null : request.getCustomerContactId();
        audit("ACCEPT", "ACCEPTANCE", id, httpRequest);
        customerService.portalAccept(id, contactId, customerId());
        return ApiResult.success(customerService.acceptance(id, customerId()));
    }

    /** 差戻し（提出済→差戻し。理由必須） */
    @PostMapping("/acceptances/{id}/reject")
    public ApiResult<PortalAcceptanceDto> reject(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest httpRequest,
                                                 @Valid @RequestBody PortalAcceptanceActionRequest request) {
        audit("REJECT", "ACCEPTANCE", id, httpRequest);
        customerService.portalReject(id, request.getComment(), customerId());
        return ApiResult.success(customerService.acceptance(id, customerId()));
    }

    @GetMapping("/acceptances/{id}/document/download")
    public ResponseEntity<InputStreamResource> acceptanceDocumentPdf(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_ACCEPTANCE", "ACCEPTANCE", id, request);
        InputStream stream = customerService.acceptanceDocumentPdf(id, customerId());
        return streamResponse(stream, "検収書_" + id + ".pdf");
    }

    // ===== 請求 =====

    @GetMapping("/invoices")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalInvoiceDto>> invoices(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResult.success(customerService.invoices(current, size, customerId()));
    }

    @GetMapping("/invoices/{id}")
    public ApiResult<PortalInvoiceDto> invoice(@PathVariable Long id) {
        return ApiResult.success(customerService.invoice(id, customerId()));
    }

    @GetMapping("/invoices/{id}/download")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_INVOICE", "INVOICE", id, request);
        byte[] bytes = customerService.invoicePdf(id, customerId(), Locale.getDefault());
        return pdfResponse(bytes, "請求書_" + id + ".pdf");
    }

    /** 受領確認・支払予定日・問い合わせの登録（R2.3。入金済状態の変更APIは存在しない） */
    @PostMapping("/invoices/{id}/register")
    public ApiResult<PortalInvoiceDto> registerInvoice(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest httpRequest,
                                                       @Valid @RequestBody PortalInvoiceRegisterRequest request) {
        audit("INVOICE_REGISTER", "INVOICE", id, httpRequest);
        return ApiResult.success(customerService.registerInvoice(id, customerId(), request));
    }

    // ===== ヘルパー =====

    private ResponseEntity<byte[]> pdfResponse(byte[] bytes, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(bytes);
    }

    private ResponseEntity<InputStreamResource> streamResponse(InputStream stream, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(new InputStreamResource(stream));
    }
}
