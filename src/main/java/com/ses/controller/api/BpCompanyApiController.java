package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.bpcompany.BpCompanyDto;
import com.ses.dto.bpcompany.BpCompanySaveDto;
import com.ses.dto.bpcompany.BpTermsDto;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.service.BpCompanyService;
import com.ses.service.BpComplianceService;
import com.ses.service.BpPriceNegotiationService;
import com.ses.service.BpRiskDashboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bp-companies")
@RequiredArgsConstructor
public class BpCompanyApiController {

    private final BpCompanyService bpCompanyService;
    private final BpComplianceService bpComplianceService;
    private final BpPriceNegotiationService bpPriceNegotiationService;
    private final BpRiskDashboardService bpRiskDashboardService;

    @GetMapping
    public ApiResult<Page<BpCompanyDto>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<BpCompanyDto> result = bpCompanyService.searchBpCompanies(keyword, entityType, status, page, size);
        return ApiResult.success(result);
    }

    @GetMapping("/autocomplete")
    public ApiResult<List<BpCompanyDto>> autocomplete(@RequestParam(required = false) String q) {
        // 取引停止 (SUSPENDED) 状態はSQLレベルで候補から自動除外される ("ACTIVE"限定)
        Page<BpCompanyDto> pageResult = bpCompanyService.searchBpCompanies(q, null, "ACTIVE", 1, 50);
        return ApiResult.success(pageResult.getRecords());
    }

    @GetMapping("/{id}")
    public ApiResult<BpCompanyDto> getDetail(@PathVariable Long id) {
        return ApiResult.success(bpCompanyService.getBpCompanyDetail(id));
    }

    @PostMapping
    public ApiResult<BpCompany> create(@RequestBody BpCompanySaveDto req) {
        BpCompany bpCompany = BpCompany.builder()
                .legalName(req.getLegalName())
                .nameKana(req.getNameKana())
                .entityType(req.getEntityType())
                .corporateNumber(req.getCorporateNumber())
                .invoiceRegistrationNumber(req.getInvoiceRegistrationNumber())
                .capitalBand(req.getCapitalBand())
                .employeeBand(req.getEmployeeBand())
                .address(req.getAddress())
                .representative(req.getRepresentative())
                .primarySalesUserId(req.getPrimarySalesUserId())
                .applicabilityNote(req.getApplicabilityNote())
                .status("ACTIVE")
                .tenantId(1L)
                .build();
        return ApiResult.success(bpCompanyService.createBpCompany(bpCompany));
    }

    @PutMapping("/{id}")
    public ApiResult<BpCompany> update(@PathVariable Long id, @RequestBody BpCompanySaveDto req) {
        BpCompany bpCompany = BpCompany.builder()
                .legalName(req.getLegalName())
                .nameKana(req.getNameKana())
                .entityType(req.getEntityType())
                .corporateNumber(req.getCorporateNumber())
                .invoiceRegistrationNumber(req.getInvoiceRegistrationNumber())
                .capitalBand(req.getCapitalBand())
                .employeeBand(req.getEmployeeBand())
                .address(req.getAddress())
                .representative(req.getRepresentative())
                .primarySalesUserId(req.getPrimarySalesUserId())
                .applicabilityNote(req.getApplicabilityNote())
                .build();
        bpCompany.setId(id);
        return ApiResult.success(bpCompanyService.updateBpCompany(bpCompany));
    }

    @PutMapping("/{id}/compliance-applicability")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<Void> updateApplicability(
            @PathVariable Long id,
            @RequestBody ApplicabilityReq req) {
        requireAdmin();
        Long currentUserId = SecurityUtils.currentUserId();
        if (currentUserId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        bpCompanyService.updateComplianceApplicability(id, req.getApplicability(), req.getNote(), currentUserId);
        return ApiResult.success(null);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<BpCompany> updateStatus(@PathVariable Long id, @RequestBody StatusUpdateReq req) {
        requireAdmin();
        BpCompany bpCompany = bpCompanyService.getById(id);
        if (bpCompany == null) {
            throw new BusinessException(404, "BP会社が見つかりません");
        }
        bpCompany.setStatus(req.getStatus());
        if ("SUSPENDED".equalsIgnoreCase(req.getStatus())) {
            bpCompany.setSuspensionReason(req.getReason());
            bpCompany.setSuspensionStartDate(req.getStartDate());
            bpCompany.setSuspensionEndDate(req.getEndDate());
            bpCompany.setSuspensionApprovedBy(SecurityUtils.currentUserId());
        } else {
            bpCompany.setSuspensionReason(null);
            bpCompany.setSuspensionStartDate(null);
            bpCompany.setSuspensionEndDate(null);
            bpCompany.setSuspensionApprovedBy(null);
        }
        return ApiResult.success(bpCompanyService.updateBpCompany(bpCompany));
    }

    @PostMapping("/{id}/bank-accounts")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<BpBankAccountDto> addBankAccount(
            @PathVariable Long id,
            @RequestBody BankAccountReq req) {
        requireAdmin();
        BpBankAccountDto account = bpCompanyService.addBankAccount(
                id, req.getBankName(), req.getBranchName(), req.getAccountType(),
                req.getAccountNumber(), req.getAccountHolder(), req.getValidFrom(), req.getValidTo());
        return ApiResult.success(account);
    }

    @PutMapping("/{id}/bank-accounts/{accountId}/approval")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<Void> updateBankAccountApproval(
            @PathVariable Long id,
            @PathVariable Long accountId,
            @RequestBody BankAccountApprovalReq req) {
        requireAdmin();
        bpCompanyService.updateBankAccountApproval(accountId,
                Boolean.TRUE.equals(req.getApproved()) ? "APPROVED" : "REJECTED",
                SecurityUtils.currentUserId());
        return ApiResult.success(null);
    }

    @GetMapping("/{id}/bank-accounts")
    public ApiResult<List<BpBankAccountDto>> getBankAccounts(@PathVariable Long id) {
        return ApiResult.success(bpCompanyService.getBankAccounts(id));
    }

    @PostMapping("/{id}/terms")
    public ApiResult<BpTerms> addTerms(@PathVariable Long id, @RequestBody com.ses.dto.bpcompany.BpTermsSaveDto dto) {
        BpTerms terms = new BpTerms();
        terms.setEffectiveFrom(dto.getEffectiveFrom());
        terms.setEffectiveTo(dto.getEffectiveTo());
        if (dto.getClosingDay() != null) terms.setClosingDay(dto.getClosingDay());
        if (dto.getPaymentMonthOffset() != null) terms.setPaymentMonthOffset(dto.getPaymentMonthOffset());
        if (dto.getPaymentDay() != null) terms.setPaymentDay(dto.getPaymentDay());
        if (dto.getFeeBearer() != null) terms.setFeeBearer(dto.getFeeBearer());
        if (dto.getPaymentMethod() != null) terms.setPaymentMethod(dto.getPaymentMethod());
        terms.setFeeBearerExceptionReason(dto.getFeeBearerExceptionReason());
        if (dto.getMaxPaymentDays() != null) terms.setMaxPaymentDays(dto.getMaxPaymentDays());
        // feeBearerApprovedBy / feeBearerApprovedAt は入力 DTO から除外し、安全性を確保
        return ApiResult.success(bpCompanyService.addTerms(id, terms));
    }

    @GetMapping("/{id}/terms/active")
    public ApiResult<BpTermsDto> getActiveTerms(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        LocalDate date = targetDate != null ? targetDate : LocalDate.now();
        return ApiResult.success(bpCompanyService.getActiveTermsAsOf(id, date));
    }

    @PostMapping("/{id}/compliance-check")
    public ApiResult<List<com.ses.dto.compliance.ProcurementComplianceFinding>> checkCompliance(
            @PathVariable Long id,
            @RequestBody com.ses.entity.Contract contract,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receiptDate) {
        return ApiResult.success(bpComplianceService.evaluateContractCompliance(id, contract, receiptDate));
    }

    @PostMapping("/{id}/price-negotiations")
    public ApiResult<com.ses.entity.BpPriceNegotiation> requestNegotiation(
            @PathVariable Long id,
            @RequestBody PriceNegotiationReq req) {
        return ApiResult.success(bpPriceNegotiationService.requestNegotiation(id, req.getRequestedAmount(), req.getSummary(), req.getDocumentId()));
    }

    @GetMapping("/risk-summary")
    public ApiResult<com.ses.dto.bpcompany.BpRiskSummaryDto> getRiskSummary() {
        return ApiResult.success(bpRiskDashboardService.getRiskSummary());
    }

    @PostMapping("/generate-risk-notifications")
    public ApiResult<Integer> generateRiskNotifications() {
        return ApiResult.success(bpRiskDashboardService.generateRiskNotifications());
    }

    @GetMapping("/{id}/price-negotiations")
    public ApiResult<List<com.ses.entity.BpPriceNegotiation>> getNegotiations(@PathVariable Long id) {
        return ApiResult.success(bpPriceNegotiationService.getNegotiationsByBpCompany(id));
    }

    @Data
    public static class ApplicabilityReq {
        private String applicability;
        private String note;
    }

    @Data
    public static class StatusUpdateReq {
        private String status;
        private String reason;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    public static class BankAccountReq {
        private String bankName;
        private String branchName;
        private String accountType;
        private String accountNumber;
        private String accountHolder;
        private LocalDate validFrom;
        private LocalDate validTo;
    }

    @Data
    public static class BankAccountApprovalReq {
        private Boolean approved;
    }

    @Data
    public static class PriceNegotiationReq {
        private java.math.BigDecimal requestedAmount;
        private String summary;
        private Long documentId;
    }

    private void requireAdmin() {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(403, "この操作は管理者のみ実行できます");
        }
    }
}
