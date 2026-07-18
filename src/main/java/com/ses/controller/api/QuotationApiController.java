package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Contract;
import com.ses.entity.Quotation;
import com.ses.service.QuotationPdfService;
import com.ses.service.QuotationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 見積API。既存 quotation メニューの api_prefix 配下。
 */
@RestController
@RequestMapping("/api/quotations")
public class QuotationApiController {

    @Autowired
    private QuotationService quotationService;

    @Autowired
    private QuotationPdfService quotationPdfService;

    @Autowired
    private com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                             @RequestParam(defaultValue = "10") long size,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Long customerId,
                             @RequestParam(required = false) String keyword) {
        Page<Quotation> page = new Page<>(current, size);
        QueryWrapper<Quotation> query = new QueryWrapper<>();
        // データスコープ: 営業ロール制限時は担当顧客∪担当要員由来の見積のみ。
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> custIds = dataScopeService.allowedCustomerIds();
            java.util.Set<Long> engIds = dataScopeService.allowedEngineerIds();
            if (custIds.isEmpty() && engIds.isEmpty()) {
                return ApiResult.success(new Page<>(current, size, 0));
            }
            query.and(w -> {
                boolean first = true;
                if (!custIds.isEmpty()) { w.in("customer_id", custIds); first = false; }
                if (!engIds.isEmpty()) { if (!first) w.or(); w.in("engineer_id", engIds); }
            });
        }
        if (status != null && !status.isEmpty()) {
            query.eq("status", status);
        }
        if (customerId != null) {
            query.eq("customer_id", customerId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            query.and(w -> w.like("title", keyword).or().like("quotation_no", keyword));
        }
        query.orderByDesc("id");
        return ApiResult.success(quotationService.page(page, query));
    }

    @GetMapping("/{id}")
    public ApiResult<?> get(@PathVariable Long id) {
        Quotation q = quotationService.getById(id);
        if (dataScopeService.isScoped() && q != null) {
            boolean visible = (q.getCustomerId() != null && dataScopeService.allowedCustomerIds().contains(q.getCustomerId()))
                    || (q.getEngineerId() != null && dataScopeService.allowedEngineerIds().contains(q.getEngineerId()));
            if (!visible) {
                throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
            }
        }
        return ApiResult.success(q);
    }

    @PostMapping
    public ApiResult<?> create(@RequestBody Quotation quotation) {
        quotation.setId(null);
        quotationService.saveWithBusinessRules(quotation);
        return ApiResult.success(quotation);
    }

    @PutMapping("/{id}")
    public ApiResult<?> update(@PathVariable Long id, @RequestBody Quotation quotation) {
        quotation.setId(id);
        quotationService.updateWithBusinessRules(quotation);
        return ApiResult.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResult<?> delete(@PathVariable Long id) {
        return ApiResult.success(quotationService.removeById(id));
    }

    @PutMapping("/{id}/status")
    public ApiResult<?> changeStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        quotationService.changeStatus(id, request.getStatus());
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/create-draft")
    public ApiResult<?> createDraft(@PathVariable Long id) {
        Contract contract = quotationService.createDraftFromQuotation(id);
        return ApiResult.success(contract);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Quotation quotation = quotationService.getById(id);
        byte[] bytes = quotationPdfService.generate(id);
        String fileName = "見積書_" + (quotation != null ? quotation.getQuotationNo() : id) + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    public static class StatusRequest {
        private String status;
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
