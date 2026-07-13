package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.dto.invoice.InvoiceGenerateRequest;
import com.ses.dto.invoice.InvoiceStatusUpdateRequest;
import com.ses.entity.BpPayment;
import com.ses.entity.Invoice;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoicePdfService;
import com.ses.service.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceApiController {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoicePdfService invoicePdfService;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) String month,
                          @RequestParam(required = false) Long customerId,
                          @RequestParam(required = false) String status) {
        Page<Invoice> page = new Page<>(current, size);
        QueryWrapper<Invoice> query = new QueryWrapper<>();
        if (month != null && !month.isEmpty()) {
            query.eq("billing_month", month);
        }
        if (customerId != null) {
            query.eq("customer_id", customerId);
        }
        if (status != null && !status.isEmpty()) {
            query.eq("status", status);
        }
        query.orderByDesc("id");
        return ApiResult.success(invoiceService.page(page, query));
    }

    @PostMapping("/generate")
    public ApiResult<?> generate(@RequestBody InvoiceGenerateRequest request) {
        Invoice invoice = invoiceService.generate(request.getCustomerId(), request.getBillingMonth());
        return ApiResult.success(invoice);
    }

    @GetMapping("/{id}")
    public ApiResult<?> detail(@PathVariable Long id) {
        return ApiResult.success(invoiceService.detail(id));
    }

    /** 請求書PDFダウンロード。 */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        InvoiceDetailDto detail = invoiceService.detail(id);
        byte[] bytes = invoicePdfService.generate(detail);
        String fileName = "請求書_" + detail.getInvoiceNo() + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    @PutMapping("/{id}/status")
    public ApiResult<?> changeStatus(@PathVariable Long id, @RequestBody InvoiceStatusUpdateRequest request) {
        invoiceService.changeStatus(id, request.getStatus(), request.getPaidDate());
        return ApiResult.success(null);
    }

    @PutMapping("/{id}/void")
    public ApiResult<?> voidInvoice(@PathVariable Long id) {
        invoiceService.voidInvoice(id);
        return ApiResult.success(null);
    }

    @GetMapping("/bp-payments")
    public ApiResult<?> bpPaymentsList(@RequestParam(required = false) String month,
                                    @RequestParam(required = false) String status) {
        List<BpPaymentListDto> list = bpPaymentMapper.selectListWithDetails(month, status);
        return ApiResult.success(list);
    }

    @PutMapping("/bp-payments/{id}")
    public ApiResult<?> updateBpPaymentStatus(@PathVariable Long id, @RequestBody InvoiceStatusUpdateRequest request) {
        invoiceService.changeBpPaymentStatus(id, request.getStatus(), request.getPaidDate());
        return ApiResult.success(null);
    }
}
