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
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import jakarta.validation.Valid;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoicePdfService;
import com.ses.service.InvoiceService;
import com.ses.service.EmailTemplateService;
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
    private EmailTemplateService emailTemplateService;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private com.ses.service.security.DataScopeService dataScopeService;

    private void assertInvoiceVisible(Long id) {
        if (!dataScopeService.isScoped()) return;
        Invoice inv = invoiceService.getById(id);
        if (inv == null || !dataScopeService.allowedCustomerIds().contains(inv.getCustomerId())) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

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
        dataScopeService.assertAllowedCustomer(request.getCustomerId());
        Invoice invoice = invoiceService.generate(request.getCustomerId(), request.getBillingMonth());
        return ApiResult.success(invoice);
    }

    @GetMapping("/{id}")
    public ApiResult<?> detail(@PathVariable Long id) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.detail(id));
    }

    /** 請求書PDFダウンロード。 */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        assertInvoiceVisible(id);
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
        assertInvoiceVisible(id);
        invoiceService.changeStatus(id, request.getStatus(), request.getPaidDate());
        return ApiResult.success(null);
    }

    @PutMapping("/{id}/void")
    public ApiResult<?> voidInvoice(@PathVariable Long id) {
        assertInvoiceVisible(id);
        invoiceService.voidInvoice(id);
        return ApiResult.success(null);
    }

    // ===== 債権管理（ar-management / P2） =====

    @GetMapping("/{id}/payments")
    public ApiResult<?> listPayments(@PathVariable Long id) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.listPayments(id));
    }

    @PostMapping("/{id}/payments")
    public ApiResult<?> addPayment(@PathVariable Long id, @RequestBody @Valid InvoicePaymentCreateRequest request) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.addPayment(id, request));
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    public ApiResult<?> deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        assertInvoiceVisible(id);
        invoiceService.deletePayment(id, paymentId);
        return ApiResult.success(null);
    }

    /** エイジング（債権年齢）レポート。asOf 省略時は今日基準。 */
    @GetMapping("/aging")
    public ApiResult<?> aging(@RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.success(invoiceService.aging(asOf));
    }

    /** エイジング表のセル（顧客×区分×基準日）を構成する請求書明細（R3R-22）。 */
    @GetMapping("/aging/detail")
    public ApiResult<?> agingDetail(@RequestParam Long customerId,
                                    @RequestParam String bucket,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        // 顧客スコープ検証（担当外顧客の明細を返さない）。
        dataScopeService.assertAllowedCustomer(customerId);
        return ApiResult.success(invoiceService.agingDetail(customerId, bucket, asOf));
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
    @GetMapping("/reminder-templates")
    public ApiResult<?> reminderTemplates() {
        return ApiResult.success(emailTemplateService.list());
    }



    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.sendReminder(id, request.getTemplateId()));
    }

    /** 請求書単位の督促履歴（宛先・件名・状態・日時・失敗理由）を返す（R3R-23）。 */
    @GetMapping("/{id}/reminders")
    public ApiResult<?> reminders(@PathVariable Long id) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.listReminders(id));
    }

    /** 督促メール送信リクエスト。 */

    @PostMapping("/reminders")
    public ApiResult<?> sendRemindersBulk(@RequestBody BulkReminderRequest request) {
        if (request == null || request.getInvoiceIds() == null || request.getInvoiceIds().isEmpty()) {
            throw com.ses.common.exception.BusinessException.of(400, "error.invoice.reminderNoTarget");
        }
        if (request.getTemplateId() == null) {
            throw com.ses.common.exception.BusinessException.of(400, "error.proposal.templateNotSelected");
        }
        // 担当外の請求書IDを一括督促の対象に混ぜられないよう各IDを可視性検証する（R3R-20）。
        for (Long id : request.getInvoiceIds()) {
            assertInvoiceVisible(id);
        }
        return ApiResult.success(invoiceService.sendReminders(request.getInvoiceIds(), request.getTemplateId(), request.getAsOf()));
    }

    public static class BulkReminderRequest {
        private List<Long> invoiceIds;
        private Long templateId;
        private LocalDate asOf;
        public List<Long> getInvoiceIds() { return invoiceIds; }
        public void setInvoiceIds(List<Long> invoiceIds) { this.invoiceIds = invoiceIds; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public LocalDate getAsOf() { return asOf; }
        public void setAsOf(LocalDate asOf) { this.asOf = asOf; }
    }

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
        assertInvoiceVisible(id);
        invoiceService.changeBpPaymentStatus(id, request.getStatus(), request.getPaidDate());
        return ApiResult.success(null);
    }
}
