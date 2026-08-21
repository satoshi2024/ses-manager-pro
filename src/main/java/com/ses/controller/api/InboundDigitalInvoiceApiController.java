package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
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

        Page<DigitalInvoice> page = PageUtils.safePage(current, size);
        digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getDirection, "RECEIVE")
                .orderByDesc(DigitalInvoice::getReceivedAt)
                .page(page);

        return ApiResult.success(page);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<InboundPurchaseRequest> reviewInvoice(@PathVariable Long id, @RequestParam String action) {
        if ("ACCEPT".equalsIgnoreCase(action)) {
            try {
                InboundPurchaseRequest request = digitalInvoiceService.acceptInboundReview(id);
                return ApiResult.success(request);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("受信電子請求書のACCEPTに失敗しました digitalInvoiceId={}", id, e);
                return ApiResult.error("error.invoice.acceptFailed");
            }
        }
        if ("REJECT".equalsIgnoreCase(action)) {
            DigitalInvoice di = digitalInvoiceService.getById(id);
            if (di == null || !"RECEIVE".equals(di.getDirection())) {
                return ApiResult.error("対象が見つかりません。");
            }
            if (!"PENDING_REVIEW".equals(di.getStatus())) {
                return ApiResult.error("レビュー待ちのインボイスではありません。");
            }
            di.setStatus("REJECTED_MANUAL");
            digitalInvoiceService.updateById(di);
            return ApiResult.success(null);
        }
        return ApiResult.error("不明なアクションです。");
    }
}
