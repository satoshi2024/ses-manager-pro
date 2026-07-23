package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
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
    private com.ses.service.CustomerService customerService;

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
        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防する
        Page<Quotation> page = PageUtils.safePage(current, size);
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
        Page<Quotation> pageResult = quotationService.page(page, query);
        
        java.util.List<com.ses.dto.quotation.QuotationListDto> dtoList = pageResult.getRecords().stream().map(q -> {
            com.ses.dto.quotation.QuotationListDto dto = new com.ses.dto.quotation.QuotationListDto();
            org.springframework.beans.BeanUtils.copyProperties(q, dto);
            return dto;
        }).collect(java.util.stream.Collectors.toList());

        java.util.Set<Long> customerIds = dtoList.stream().map(com.ses.dto.quotation.QuotationListDto::getCustomerId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!customerIds.isEmpty()) {
            java.util.Map<Long, String> customerMap = customerService.listByIds(customerIds).stream()
                    .collect(java.util.stream.Collectors.toMap(com.ses.entity.Customer::getId, com.ses.entity.Customer::getCompanyName));
            dtoList.forEach(dto -> dto.setCustomerName(customerMap.get(dto.getCustomerId())));
        }
        
        Page<com.ses.dto.quotation.QuotationListDto> dtoPage = new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
        dtoPage.setRecords(dtoList);
        return ApiResult.success(dtoPage);
    }

    private Quotation getVisibleQuotationOr404(Long id) {
        Quotation q = quotationService.getById(id);
        if (q == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        if (dataScopeService.isScoped()) {
            boolean visible = (q.getCustomerId() != null && dataScopeService.allowedCustomerIds().contains(q.getCustomerId()))
                    || (q.getEngineerId() != null && dataScopeService.allowedEngineerIds().contains(q.getEngineerId()));
            if (!visible) {
                throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
            }
        }
        return q;
    }

    @GetMapping("/{id}")
    public ApiResult<?> get(@PathVariable Long id) {
        return ApiResult.success(getVisibleQuotationOr404(id));
    }

    /** payload が参照する顧客・案件・要員が担当スコープ内であることを検証する（R3R-32）。 */
    private void assertReferencedAllowed(com.ses.dto.quotation.QuotationSaveRequest request) {
        if (!dataScopeService.isScoped()) return;
        if (request.getCustomerId() != null) dataScopeService.assertAllowedCustomer(request.getCustomerId());
        if (request.getEngineerId() != null) dataScopeService.assertAllowedEngineer(request.getEngineerId());
        if (request.getProjectId() != null) dataScopeService.assertAllowedProject(request.getProjectId());
    }

    @PostMapping
    public ApiResult<?> create(@jakarta.validation.Valid @RequestBody com.ses.dto.quotation.QuotationSaveRequest request) {
        assertReferencedAllowed(request);
        Quotation quotation = new Quotation();
        quotation.setCustomerId(request.getCustomerId());
        quotation.setProjectId(request.getProjectId());
        quotation.setEngineerId(request.getEngineerId());
        quotation.setProposalId(request.getProposalId());
        quotation.setTitle(request.getTitle());
        quotation.setUnitPrice(request.getUnitPrice());
        quotation.setSettlementHoursMin(request.getSettlementHoursMin());
        quotation.setSettlementHoursMax(request.getSettlementHoursMax());
        quotation.setValidUntil(request.getValidUntil());
        quotation.setRemarks(request.getRemarks());
        
        quotationService.saveWithBusinessRules(quotation);
        return ApiResult.success(quotation);
    }

    @PutMapping("/{id}")
    public ApiResult<?> update(@PathVariable Long id, @jakarta.validation.Valid @RequestBody com.ses.dto.quotation.QuotationSaveRequest request) {
        getVisibleQuotationOr404(id);
        assertReferencedAllowed(request);
        // 終端状態の拒否はサービス側（行ロック内）でも実施する（R3R-24/25）。
        Quotation quotation = new Quotation();
        quotation.setId(id);
        quotation.setCustomerId(request.getCustomerId());
        quotation.setProjectId(request.getProjectId());
        quotation.setEngineerId(request.getEngineerId());
        quotation.setProposalId(request.getProposalId());
        quotation.setTitle(request.getTitle());
        quotation.setUnitPrice(request.getUnitPrice());
        quotation.setSettlementHoursMin(request.getSettlementHoursMin());
        quotation.setSettlementHoursMax(request.getSettlementHoursMax());
        quotation.setValidUntil(request.getValidUntil());
        quotation.setRemarks(request.getRemarks());
        
        quotationService.updateWithBusinessRules(quotation);
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/remarks")
    public ApiResult<?> appendRemark(@PathVariable Long id, @jakarta.validation.Valid @RequestBody com.ses.dto.quotation.QuotationRemarkAppendRequest req) {
        getVisibleQuotationOr404(id);
        // 行ロック内で最新値へ追記する（並行追記の履歴喪失防止 / R3R-24）。
        quotationService.appendRemark(id, req.getAdditionalRemark());
        return ApiResult.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResult<?> delete(@PathVariable Long id) {
        getVisibleQuotationOr404(id);
        boolean success = quotationService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    @PutMapping("/{id}/status")
    public ApiResult<?> changeStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        getVisibleQuotationOr404(id);
        quotationService.changeStatus(id, request.getStatus());
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/create-draft")
    public ApiResult<?> createDraft(@PathVariable Long id) {
        getVisibleQuotationOr404(id);
        Contract contract = quotationService.createDraftFromQuotation(id);
        return ApiResult.success(contract);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Quotation quotation = getVisibleQuotationOr404(id);
        byte[] bytes = quotationPdfService.generate(quotation);
        String fileName = "見積書_" + (quotation.getQuotationNo() != null ? quotation.getQuotationNo() : id) + ".pdf";
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
