package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
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
        CorrelationContext.put(CorrelationContext.INVOICE_ID, invoiceId);
        try {
            Invoice invoice = invoiceService.getById(invoiceId);
            if (invoice == null) {
                return ApiResult.error("error.invoice.notFound");
            }

            dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
            Customer customer = customerService.getById(invoice.getCustomerId());
            if (customer == null) {
                return ApiResult.error("error.invoice.customerNotFound");
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
                result.put("canSend", true);
            }

            long sentCount = digitalInvoiceService.lambdaQuery()
                    .eq(DigitalInvoice::getInvoiceId, invoiceId)
                    .eq(DigitalInvoice::getDirection, "SEND")
                    .eq(DigitalInvoice::getProfile, "Standard")
                    .notIn(DigitalInvoice::getStatus, "CANCELLED", "REVOKED")
                    .count();
            if (sentCount > 0) {
                result.put("alreadySent", true);
                result.put("canSend", false);
                result.put("reason", "すでに送信処理中です。");
            } else {
                result.put("alreadySent", false);
            }
            return ApiResult.success(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "PREVIEW_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("電子請求書プレビューに失敗: invoiceId={} errorCode={} exceptionClass={} detail={}",
                    invoiceId, "PREVIEW_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error("error.invoice.previewFailed");
        }
    }

    @PostMapping("/dispatch/{invoiceId}")
    public ApiResult<Void> dispatchInvoice(@PathVariable Long invoiceId, @RequestParam(defaultValue = "1.1.3") String specVersion) {
        CorrelationContext.put(CorrelationContext.INVOICE_ID, invoiceId);
        try {
            Invoice invoice = invoiceService.getById(invoiceId);
            if (invoice == null) {
                return ApiResult.error("error.invoice.notFound");
            }
            dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
            deliveryDispatcher.dispatch(invoiceId, invoice.getCustomerId(), specVersion);
            return ApiResult.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "DISPATCH_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("電子請求書の送信ディスパッチに失敗: invoiceId={} errorCode={} exceptionClass={} detail={}",
                    invoiceId, "DISPATCH_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error("error.invoice.dispatchFailed");
        }
    }

    @GetMapping("/{invoiceId}/status-history")
    public ApiResult<Map<String, Object>> getStatusHistory(@PathVariable Long invoiceId) {
        CorrelationContext.put(CorrelationContext.INVOICE_ID, invoiceId);
        try {
            Invoice invoice = invoiceService.getById(invoiceId);
            if (invoice == null) {
                return ApiResult.error("error.invoice.notFound");
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

            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, di.getId());
            Map<String, Object> result = new HashMap<>();
            result.put("digitalInvoiceId", di.getId());
            result.put("status", di.getStatus());
            result.put("events", java.util.Collections.emptyList());

            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean isSales = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_営業"));
            if (!isSales) {
                result.put("canViewXml", true);
                result.put("xmlUrl", "/api/digital-invoices/" + di.getId() + "/xml");
            } else {
                result.put("canViewXml", false);
            }
            return ApiResult.success(result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "STATUS_HISTORY_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("電子請求書ステータス履歴取得に失敗: invoiceId={} errorCode={} exceptionClass={} detail={}",
                    invoiceId, "STATUS_HISTORY_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error("error.invoice.statusHistoryFailed");
        }
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<Void> cancelInvoice(@PathVariable Long id) {
        CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, id);
        try {
            DigitalInvoice di = digitalInvoiceService.getById(id);
            if (di == null || !"SEND".equals(di.getDirection())) {
                return ApiResult.error("error.invoice.notFound");
            }
            CorrelationContext.put(CorrelationContext.INVOICE_ID, di.getInvoiceId());
            Invoice invoice = invoiceService.getById(di.getInvoiceId());
            if (invoice != null) {
                dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
            }
            digitalInvoiceService.cancelInvoice(id);
            return ApiResult.success(null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "CANCEL_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("電子請求書の取消に失敗: digitalInvoiceId={} invoiceId={} errorCode={} exceptionClass={} detail={}",
                    id, CorrelationContext.get(CorrelationContext.INVOICE_ID), "CANCEL_FAILED",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error("error.invoice.cancelFailed");
        }
    }

    @GetMapping(value = "/{id}/xml")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadXml(@PathVariable Long id) {
        CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, id);
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean isSales = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_営業"));
            if (isSales) {
                return org.springframework.http.ResponseEntity.status(403).build();
            }

            DigitalInvoice di = digitalInvoiceService.getById(id);
            if (di == null) return org.springframework.http.ResponseEntity.notFound().build();
            CorrelationContext.put(CorrelationContext.INVOICE_ID, di.getInvoiceId());
            if (di.getInvoiceId() != null) {
                Invoice invoice = invoiceService.getById(di.getInvoiceId());
                if (invoice == null) return org.springframework.http.ResponseEntity.notFound().build();
                dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
            }
            if (di.getXmlDocumentId() == null) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            java.io.InputStream is = documentService.download(di.getXmlDocumentId(), null);
            org.springframework.core.io.InputStreamResource resource = new org.springframework.core.io.InputStreamResource(is);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"invoice_" + (di.getInvoiceId() != null ? di.getInvoiceId() : di.getMessageId()) + ".xml\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                    .body(resource);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "DOWNLOAD_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.error("電子請求書XMLのダウンロードに失敗: digitalInvoiceId={} invoiceId={} errorCode={} exceptionClass={} detail={}",
                    id, CorrelationContext.get(CorrelationContext.INVOICE_ID), "DOWNLOAD_FAILED",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            throw new BusinessException(500, "error.invoice.downloadFailed");
        }
    }
}
