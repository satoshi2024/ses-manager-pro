package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.DigitalInvoice;
import com.ses.service.DigitalInvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inbound-invoices")
@RequiredArgsConstructor
public class InboundDigitalInvoiceApiController {

    private final DigitalInvoiceService digitalInvoiceService;

    @GetMapping
    @PreAuthorize("hasAnyRole('管理者', 'マネージャー', '財務')")
    public ApiResult<Page<DigitalInvoice>> listInboundInvoices(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        
        Page<DigitalInvoice> page = new Page<>(current, size);
        digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getDirection, "RECEIVE")
                .orderByDesc(DigitalInvoice::getReceivedAt)
                .page(page);
                
        return ApiResult.success(page);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> reviewInvoice(@PathVariable Long id, @RequestParam String action) {
        DigitalInvoice di = digitalInvoiceService.getById(id);
        if (di == null || !"RECEIVE".equals(di.getDirection())) {
            return ApiResult.failed("対象が見つかりません。");
        }
        
        if (!"PENDING_REVIEW".equals(di.getStatus())) {
            return ApiResult.failed("レビュー待ちのインボイスではありません。");
        }

        if ("ACCEPT".equalsIgnoreCase(action)) {
            // 受信invoiceを自動で支払確定しない（R5）。必ずreview queueを経由し、人が確定する。
            // BP Purchase作成はreview確定後にaccounting canonical DTOへ渡すなどの処理を行う。
            di.setStatus("ACCEPTED");
        } else if ("REJECT".equalsIgnoreCase(action)) {
            di.setStatus("REJECTED_MANUAL");
        } else {
            return ApiResult.failed("不明なアクションです。");
        }

        digitalInvoiceService.updateById(di);
        return ApiResult.success();
    }
}
