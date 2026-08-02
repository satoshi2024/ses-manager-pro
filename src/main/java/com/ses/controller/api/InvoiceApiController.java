package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.dto.invoice.InvoiceGenerateRequest;
import com.ses.dto.invoice.InvoiceStatusUpdateRequest;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.entity.Invoice;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import jakarta.validation.Valid;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoicePdfService;
import com.ses.service.InvoiceService;
import com.ses.service.EmailTemplateService;
import com.ses.service.CustomerContactService;
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
    private com.ses.service.approval.ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private CustomerContactService customerContactService;

    @Autowired
    private com.ses.service.security.DataScopeService dataScopeService;

    @Autowired
    private com.ses.service.BpPaymentService bpPaymentService;

    @Autowired
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) String month,
                          @RequestParam(required = false) Long customerId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) Boolean overdue) {
        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ
        Page<Invoice> page = PageUtils.safePage(current, size);
        QueryWrapper<Invoice> query = new QueryWrapper<>();
        
        java.util.Set<Long> organizationInvoiceIds = effectiveInvoiceIds(month);
        if (organizationInvoiceIds != null) {
            if (organizationInvoiceIds.isEmpty()) return ApiResult.success(new Page<>());
            query.in("id", organizationInvoiceIds);
        }
        if (dataScopeService.isSalesDataScoped()) {
            java.util.Set<Long> allowedCustomers = dataScopeService.allowedCustomerIds();
            if (allowedCustomers.isEmpty()) return ApiResult.success(new Page<>());
            query.in("customer_id", allowedCustomers);
        }
        
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
            com.ses.service.InvoiceService.applyOverdueFilter(query);
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
        String requestType = "送付済".equals(request.getStatus()) ? "invoice.send" : "invoice.status";
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("operation", "send"); command.put("status", request.getStatus()); command.put("paidDate", request.getPaidDate());
        return ApiResult.success(approvalTargetAdapterRegistry.request(requestType, "INVOICE", id, command));
    }

    @PutMapping("/{id}/void")
    public ApiResult<?> voidInvoice(@PathVariable Long id) {
        assertInvoiceVisible(id);
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<>(); command.put("operation", "void");
        return ApiResult.success(approvalTargetAdapterRegistry.request("invoice.void", "INVOICE", id, command));
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
        if (dataScopeService.isSalesDataScoped()) {
            dataScopeService.assertAllowedCustomer(customerId);
        }
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

    /** 請求書の顧客に対する、基準日時点で有効な督促宛先候補。 */
    @GetMapping("/{id}/recipient-candidates")
    public ApiResult<?> recipientCandidates(@PathVariable Long id) {
        assertInvoiceVisible(id);
        Invoice invoice = invoiceService.getById(id);
        return ApiResult.success(customerContactService.recipientCandidates(invoice.getCustomerId(), LocalDate.now()));
    }



    @PostMapping("/{id}/reminder")
    public ApiResult<?> sendReminder(@PathVariable Long id, @RequestBody ReminderRequest request) {
        assertInvoiceVisible(id);
        return ApiResult.success(invoiceService.sendReminder(id, request.getTemplateId(), request.getContactId()));
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
        request.getInvoiceIds().forEach(this::assertInvoiceVisible);
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
        private Long contactId;
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public Long getContactId() { return contactId; }
        public void setContactId(Long contactId) { this.contactId = contactId; }
    }

    @GetMapping("/bp-payments")
    public ApiResult<?> bpPaymentsList(@RequestParam(required = false) String month,
                                    @RequestParam(required = false) String status) {
        List<BpPaymentListDto> list;
        boolean organizationScoped = !organizationScopeService.hasFullAccess();
        boolean dataScoped = dataScopeService.isSalesDataScoped();
        LocalDate asOf = asOfForMonth(month);
        if (!organizationScoped && !dataScoped) {
            list = bpPaymentMapper.selectListWithDetails(month, status);
        } else {
            java.util.List<Long> organizationIds = organizationScoped ? new java.util.ArrayList<>(
                    organizationScopeService.allowedOrganizationIds(asOf)) : null;
            java.util.List<Long> directUserIds = organizationScoped ? new java.util.ArrayList<>(
                    organizationScopeService.allowedDirectUserIds(asOf)) : null;
            java.util.List<Long> contractIds = dataScoped
                    ? new java.util.ArrayList<>(dataScopeService.allowedContractIds()) : null;
            if (contractIds != null && contractIds.isEmpty()) {
                return ApiResult.success(List.of());
            }
            if (organizationScoped && organizationIds.isEmpty() && directUserIds.isEmpty()) {
                return ApiResult.success(List.of());
            }
            list = bpPaymentMapper.selectListWithDetailsScoped(month, status, contractIds, organizationIds,
                    directUserIds, asOf);
        }
        return ApiResult.success(list);
    }

    /**
     * 請求一覧画面からのBP支払ステータス更新。
     * 階層データ自体の編集は BpPaymentApiController の
     * /api/invoices/bp-payments/{id}/layer が担当する。
     */
    @PutMapping("/bp-payments/{id}")
    public ApiResult<?> updateBpPaymentStatus(@PathVariable Long id, @RequestBody InvoiceStatusUpdateRequest request) {
        // BP支払は請求書に紐づかない原価側データのため、請求書スコープ検証(assertInvoiceVisible)は
        // 誤り（BP支払IDを請求書IDとして扱ってしまう）。BP支払はメニュー権限で保護される管理業務であり、
        // データスコープ対象外のため請求書可視性検証は行わない（R3R-35）。
        bpPaymentService.assertAllowed(id);
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("status", request.getStatus()); command.put("paidDate", request.getPaidDate());
        return ApiResult.success(approvalTargetAdapterRegistry.request("bp_payment.confirm", "BP_PAYMENT", id, command));
    }

    private java.util.Set<Long> effectiveInvoiceIds(String month) {
        return organizationScopeService.hasFullAccess()
                ? null : organizationScopeService.allowedInvoiceIds(asOfForMonth(month));
    }

    private void assertInvoiceVisible(Long id) {
        Invoice invoice = invoiceService.getById(id);
        if (invoice == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        java.util.Set<Long> allowed = effectiveInvoiceIds(invoice.getBillingMonth());
        if (allowed != null && !allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        if (dataScopeService.isSalesDataScoped()) {
            dataScopeService.assertAllowedCustomer(invoice.getCustomerId());
        }
    }

    private LocalDate asOfForMonth(String month) {
        return month == null || month.isBlank()
                ? LocalDate.now()
                : com.ses.common.util.DateUtils.parseYearMonth(month).atDay(1);
    }
}
