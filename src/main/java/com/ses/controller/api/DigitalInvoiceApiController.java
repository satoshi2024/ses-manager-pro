package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.Customer;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.invoice.InvoiceDeliveryDispatcher;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/digital-invoices")
@RequiredArgsConstructor
public class DigitalInvoiceApiController {

    private final DigitalInvoiceService digitalInvoiceService;
    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final PeppolParticipantService peppolParticipantService;
    private final InvoiceDeliveryDispatcher deliveryDispatcher;
    private final DataScopeService dataScopeService;
    private final com.ses.service.DocumentService documentService;

    @GetMapping("/preview/{invoiceId}")
    public ApiResult<Map<String, Object>> previewDelivery(@PathVariable Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.failed("請求書が見つかりません。");
        }
        
        dataScopeService.assertAllowedCustomer(invoice.getCustomerId()); Customer customer = customerService.getById(invoice.getCustomerId());
        if (customer == null) {
            return ApiResult.failed("請求先の顧客が見つかりません。");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("invoiceId", invoice.getId());
        result.put("deliveryPreference", customer.getDeliveryPreference());
        
        if ("PEPPOL".equalsIgnoreCase(customer.getDeliveryPreference())) {
            PeppolParticipant participant = peppolParticipantService.lambdaQuery()
                    .eq(PeppolParticipant::getOwnerType, "CUSTOMER")
                    .eq(PeppolParticipant::getOwnerId, customer.getId())
                    .one();
                    
            if (participant != null && participant.getVerifiedAt() != null) {
                result.put("peppolStatus", "VERIFIED");
                result.put("canSend", true);
            } else {
                result.put("peppolStatus", "UNVERIFIED");
                result.put("canSend", false);
                result.put("reason", "宛先のPeppol Participant IDが未検証です。");
            }
        } else {
            result.put("canSend", true); // EMAILやPDF等
        }
        
        // 既存の送信状態確認
        long sentCount = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .in(DigitalInvoice::getStatus, "QUEUED", "SENT", "DELIVERED")
                .count();
        if (sentCount > 0) {
            result.put("alreadySent", true);
            result.put("canSend", false);
            result.put("reason", "すでに送信処理中です。");
        } else {
            result.put("alreadySent", false);
        }
        
        return ApiResult.success(result);
    }

    @PostMapping("/dispatch/{invoiceId}")
    public ApiResult<Void> dispatchInvoice(@PathVariable Long invoiceId, @RequestParam(defaultValue = "1.1.3") String specVersion) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.failed("請求書が見つかりません。");
        }
        dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
        try {
            deliveryDispatcher.dispatch(invoiceId, invoice.getCustomerId(), specVersion);
            return ApiResult.success();
        } catch (Exception e) {
            return ApiResult.failed(e.getMessage());
        }
    }

    @GetMapping("/{invoiceId}/status-history")
    public ApiResult<Map<String, Object>> getStatusHistory(@PathVariable Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.failed("請求書が見つかりません。");
        }
        
        dataScopeService.assertAllowedCustomer(invoice.getCustomerId());

        DigitalInvoice di = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .orderByDesc(DigitalInvoice::getCreatedAt)
                .last("LIMIT 1")
                .one();

        if (di == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("digitalInvoiceId", null);
            return ApiResult.success(empty);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("digitalInvoiceId", di.getId());
        result.put("status", di.getStatus());
        
        result.put("events", java.util.Collections.emptyList());
        
        // 権限チェック (Field Masking: 営業にはXMLを見せない)
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isSales = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_営業"));
        
        if (!isSales) {
            result.put("canViewXml", true);
            result.put("xmlUrl", "/api/digital-invoices/" + di.getId() + "/xml");
        } else {
            result.put("canViewXml", false);
        }

        return ApiResult.success(result);
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<Void> cancelInvoice(@PathVariable Long id) {
        DigitalInvoice di = digitalInvoiceService.getById(id);
        if (di == null || !"SEND".equals(di.getDirection())) {
            return ApiResult.failed("対象のインボイスが見つかりません。");
        }
        
        Invoice invoice = invoiceService.getById(di.getInvoiceId());
        if (invoice != null) {
            dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
        }

        if ("QUEUED".equals(di.getStatus())) {
            di.setStatus("CANCELLED");
            digitalInvoiceService.updateById(di);
            return ApiResult.success();
        } else if ("SENT".equals(di.getStatus()) || "DELIVERED".equals(di.getStatus())) {
            // R4.1 PINT送信済みの場合は新しいメッセージIDで打ち消しレコードを作成
            DigitalInvoice cancelRow = new DigitalInvoice();
            cancelRow.setInvoiceId(di.getInvoiceId());
            cancelRow.setDirection("SEND");
            cancelRow.setProfile(di.getProfile());
            cancelRow.setSpecificationVersion(di.getSpecificationVersion());
            cancelRow.setMessageId("MSG-CANCEL-" + java.util.UUID.randomUUID().toString());
            cancelRow.setStatus("CANCELLED");
            digitalInvoiceService.save(cancelRow);
            return ApiResult.success();
        }
        
        return ApiResult.failed("キャンセルできないステータスです。");
    }

    @GetMapping(value = "/{id}/xml")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadXml(@PathVariable Long id) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isSales = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_営業"));
        if (isSales) {
            return org.springframework.http.ResponseEntity.status(403).build();
        }

        DigitalInvoice di = digitalInvoiceService.getById(id);
        if (di == null) return org.springframework.http.ResponseEntity.notFound().build();
        
        if (di.getInvoiceId() != null) {
            Invoice invoice = invoiceService.getById(di.getInvoiceId());
            if (invoice == null) return org.springframework.http.ResponseEntity.notFound().build();
            dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
        }

        if (di.getXmlDocumentId() == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        try {
            java.io.InputStream is = documentService.download(di.getXmlDocumentId(), null);
            org.springframework.core.io.InputStreamResource resource = new org.springframework.core.io.InputStreamResource(is);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"invoice_" + (di.getInvoiceId() != null ? di.getInvoiceId() : di.getMessageId()) + ".xml\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                    .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }
}
