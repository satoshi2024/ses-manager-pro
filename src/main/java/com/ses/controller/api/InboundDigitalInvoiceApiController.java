package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.dto.invoice.InboundPurchaseRequest;
import com.ses.entity.DigitalInvoice;
import com.ses.service.DigitalInvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/inbound-invoices")
@RequiredArgsConstructor
public class InboundDigitalInvoiceApiController {

    private final DigitalInvoiceService digitalInvoiceService;

    @GetMapping
    @PreAuthorize("hasAnyRole('管理者', 'マネージャー')")
    public ApiResult<Page<DigitalInvoice>> listInboundInvoices(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {

        try {
            Page<DigitalInvoice> page = PageUtils.safePage(current, size);
            digitalInvoiceService.lambdaQuery()
                    .eq(DigitalInvoice::getDirection, "RECEIVE")
                    .orderByDesc(DigitalInvoice::getReceivedAt)
                    .page(page);
            return ApiResult.success(page);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "INBOUND_LIST_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("受信電子請求書一覧の取得に失敗: errorCode={} exceptionClass={} detail={}",
                    "INBOUND_LIST_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error("error.invoice.inboundListFailed");
        }
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<InboundPurchaseRequest> reviewInvoice(@PathVariable Long id, @RequestParam String action) {
        CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, id);
        try {
            if ("ACCEPT".equalsIgnoreCase(action)) {
                InboundPurchaseRequest request = digitalInvoiceService.acceptInboundReview(id);
                return ApiResult.success(request);
            }
            if ("REJECT".equalsIgnoreCase(action)) {
                DigitalInvoice di = digitalInvoiceService.getById(id);
                if (di == null || !"RECEIVE".equals(di.getDirection())) {
                    return ApiResult.error("error.invoice.notFound");
                }
                if (!"PENDING_REVIEW".equals(di.getStatus())) {
                    return ApiResult.error("error.invoice.rejectFailed");
                }
                di.setStatus("REJECTED_MANUAL");
                digitalInvoiceService.updateById(di);
                return ApiResult.success(null);
            }
            return ApiResult.error("error.invoice.rejectFailed");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            String code = "ACCEPT".equalsIgnoreCase(action) ? "ACCEPT_FAILED" : "REJECT_FAILED";
            String key = "ACCEPT".equalsIgnoreCase(action) ? "error.invoice.acceptFailed" : "error.invoice.rejectFailed";
            CorrelationContext.put(CorrelationContext.ERROR_CODE, code);
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.warn("受信電子請求書レビューに失敗: digitalInvoiceId={} errorCode={} exceptionClass={} detail={}",
                    id, code, LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return ApiResult.error(key);
        }
    }
}
