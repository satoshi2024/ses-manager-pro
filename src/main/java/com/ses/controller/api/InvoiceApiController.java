package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.dto.invoice.InvoiceGenerateRequest;
import com.ses.dto.invoice.InvoiceStatusUpdateRequest;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.entity.BpPayment;
import com.ses.entity.Invoice;
import com.ses.entity.InvoicePayment;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoicePdfService;
import com.ses.service.InvoiceService;
import com.ses.service.export.ExcelExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

    @Autowired
    private ExcelExportService excelExportService;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) String month,
                          @RequestParam(required = false) Long customerId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) Boolean overdue) {
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
        // 支払期限超過(未入金かつ期限日 < 今日)のみに絞り込む
        if (Boolean.TRUE.equals(overdue)) {
            query.ne("status", "入金済")
                 .isNotNull("due_date")
                 .lt("due_date", java.time.LocalDate.now());
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

    // ===== 債権管理（ar-management / P2） =====

    @GetMapping("/{id}/payments")
    public ApiResult<?> listPayments(@PathVariable Long id) {
        return ApiResult.success(invoiceService.listPayments(id));
    }

    @PostMapping("/{id}/payments")
    public ApiResult<?> addPayment(@PathVariable Long id, @RequestBody InvoicePayment payment) {
        return ApiResult.success(invoiceService.addPayment(id, payment));
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    public ApiResult<?> deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        invoiceService.deletePayment(id, paymentId);
        return ApiResult.success(null);
    }

    /** エイジング（債権年齢）レポート。asOf 省略時は今日基準。 */
    @GetMapping("/aging")
    public ApiResult<?> aging(@RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.success(invoiceService.aging(asOf));
    }

    /** エイジングレポートのExcel出力。 */
    @GetMapping("/aging-export")
    public ResponseEntity<byte[]> agingExport(@RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        AgingReportDto report = invoiceService.aging(asOf);
        byte[] bytes = excelExportService.exportAging(report);
        String fileName = "エイジングレポート_" + report.getAsOf() + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    /** 督促メール送信。body: {"templateId": N}。 */
    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {
        return ApiResult.success(invoiceService.sendReminder(id, request.getTemplateId()));
    }

    /** 督促メール送信リクエスト。 */
    public static class ReminderRequest {
        private Long templateId;
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
    }

    @GetMapping("/bp-payments")
    public ApiResult<?> bpPaymentsList(@RequestParam(required = false) String month,
                                    @RequestParam(required = false) String status) {
        List<BpPaymentListDto> list = bpPaymentMapper.selectListWithDetails(month, status);
        return ApiResult.success(list);
    }

    /**
     * 請求一覧画面からのBP支払ステータス更新。
     * 階層データ自体の編集は BpPaymentApiController の
     * /api/invoices/bp-payments/{id}/layer が担当する。
     */
    @PutMapping("/bp-payments/{id}")
    public ApiResult<?> updateBpPaymentStatus(@PathVariable Long id, @RequestBody InvoiceStatusUpdateRequest request) {
        invoiceService.changeBpPaymentStatus(id, request.getStatus(), request.getPaidDate());
        return ApiResult.success(null);
    }
}
