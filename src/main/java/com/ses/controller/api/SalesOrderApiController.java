package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.order.SalesOrderDetailDto;
import com.ses.dto.order.SalesOrderListDto;
import com.ses.dto.order.SalesOrderSaveRequest;
import com.ses.entity.SalesOrder;
import com.ses.service.SalesOrderService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注文API（order-acceptance-workflow / A1）。
 * 一覧・詳細・CRUD・状態遷移・原本受領・注文請PDF・文書download・承認申請・契約化。
 */
@RestController
@RequestMapping("/api/sales-orders")
@RequiredArgsConstructor
public class SalesOrderApiController {

    private final SalesOrderService salesOrderService;
    private final ApprovalTargetAdapterRegistry approvalRegistry;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                             @RequestParam(defaultValue = "20") long size,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ApiResult.success(salesOrderService.pageOrders(current, size, status, keyword, dateFrom, dateTo));
    }

    @GetMapping("/{id}")
    public ApiResult<SalesOrderDetailDto> detail(@PathVariable Long id) {
        return ApiResult.success(salesOrderService.detail(id));
    }

    /** PO重複の警告判定（拒否しない。R2.4の警告と拒否を混同しない）。 */
    @GetMapping("/po-duplicate")
    public ApiResult<Map<String, Object>> poDuplicate(@RequestParam Long customerId,
                                                      @RequestParam(required = false) String customerPoNo,
                                                      @RequestParam(required = false) Long excludeOrderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("duplicate", salesOrderService.isCustomerPoDuplicate(customerId, customerPoNo, excludeOrderId));
        return ApiResult.success(data);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@jakarta.validation.Valid @RequestBody SalesOrderSaveRequest request) {
        boolean poWarning = salesOrderService.isCustomerPoDuplicate(request.getCustomerId(), request.getCustomerPoNo(), null);
        SalesOrder order = salesOrderService.createFromRequest(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("poWarning", poWarning);
        return ApiResult.success(data);
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable Long id,
                                                 @jakarta.validation.Valid @RequestBody SalesOrderSaveRequest request) {
        SalesOrder order = salesOrderService.updateFromRequest(id, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("poWarning", salesOrderService.isCustomerPoDuplicate(order.getCustomerId(), order.getCustomerPoNo(), id));
        return ApiResult.success(data);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        salesOrderService.deleteOrder(id);
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/status")
    public ApiResult<SalesOrder> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResult.success(salesOrderService.changeStatus(id, body.get("status")));
    }

    /** 注文から契約ドラフトを生成する（R2.2）。1明細→1契約（冪等）。 */
    @PostMapping("/{id}/contract-drafts")
    public ApiResult<?> createContracts(@PathVariable Long id) {
        return ApiResult.success(salesOrderService.createContractDrafts(id));
    }

    /** 受領した注文書原本を文書台帳（ORDER_RECEIVED）へ登録する（R1.4）。同一hashは拒否（R2.4）。 */
    @PostMapping("/{id}/source-document")
    public ApiResult<SalesOrder> uploadSourceDocument(@PathVariable Long id,
                                                      @RequestParam("file") MultipartFile file) {
        return ApiResult.success(salesOrderService.uploadSourceDocument(id, file));
    }

    /** 注文請書PDFを生成・文書台帳（ORDER_ACKNOWLEDGEMENT）へ登録する（R1.4）。 */
    @PostMapping("/{id}/acknowledgement-pdf")
    public ResponseEntity<byte[]> acknowledgementPdf(@PathVariable Long id,
                                                     @RequestParam(required = false) String lang,
                                                     java.util.Locale locale) {
        java.util.Locale target = "en".equals(lang) ? java.util.Locale.ENGLISH : locale;
        byte[] pdf = salesOrderService.generateAcknowledgementPdf(id, target == null ? java.util.Locale.JAPANESE : target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"order_ack_" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** archive済み注文請書の正本をdownloadする。生成・状態変更は行わない。 */
    @GetMapping("/{id}/acknowledgement-pdf/download")
    public ResponseEntity<InputStreamResource> downloadAcknowledgementPdf(@PathVariable Long id) {
        java.io.InputStream stream = salesOrderService.downloadAcknowledgementPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"order_ack_" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(stream));
    }

    /** 注文の原本/注文請書をdownloadする（注文一覧と同じscope。document側に別ACLを作らない）。 */
    @GetMapping("/{id}/documents/{documentId}/download")
    public ResponseEntity<InputStreamResource> downloadDocument(@PathVariable Long id,
                                                                @PathVariable Long documentId) {
        java.io.InputStream stream = salesOrderService.downloadDocument(id, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"order_document_" + documentId + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    /** 契約化済み注文の取消を承認申請する（design §5.3: 契約化→取消は承認必須）。 */
    @PostMapping("/{id}/cancel-approval")
    public ApiResult<?> requestCancelApproval(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("operation", "cancel");
        if (body != null) {
            command.put("reason", body.getOrDefault("reason", ""));
        }
        return ApiResult.success(approvalRegistry.request("order.cancel", "SALES_ORDER", id, command));
    }

    /** 注文条件が見積/契約と異なる場合の承認申請（R2.3）。 */
    @PostMapping("/{id}/condition-diff-approval")
    public ApiResult<?> requestConditionDiffApproval(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("operation", "conditionDiff");
        if (body != null) {
            command.put("reason", body.getOrDefault("reason", ""));
        }
        return ApiResult.success(approvalRegistry.request("order.conditionDiff", "SALES_ORDER", id, command));
    }
}
