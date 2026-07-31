package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.bpcompany.BpCompanyDto;
import com.ses.dto.bpcompany.BpTermsDto;
import com.ses.entity.BpBankAccount;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.service.BpCompanyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bp-companies")
@RequiredArgsConstructor
public class BpCompanyApiController {

    private final BpCompanyService bpCompanyService;
    private final com.ses.service.BpComplianceService bpComplianceService;
    private final com.ses.service.BpPriceNegotiationService bpPriceNegotiationService;
    private final com.ses.service.BpRiskDashboardService bpRiskDashboardService;

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
        Page<BpCompanyDto> pageResult = bpCompanyService.searchBpCompanies(q, null, "ACTIVE", 1, 50);
        return ApiResult.success(pageResult.getRecords());
    }

    @GetMapping("/{id}")
    public ApiResult<BpCompanyDto> getDetail(@PathVariable Long id) {
        return ApiResult.success(bpCompanyService.getBpCompanyDetail(id));
    }

    @PostMapping
    public ApiResult<BpCompany> create(@RequestBody BpCompany bpCompany) {
        return ApiResult.success(bpCompanyService.createBpCompany(bpCompany));
    }

    @PutMapping("/{id}")
    public ApiResult<BpCompany> update(@PathVariable Long id, @RequestBody BpCompany bpCompany) {
        bpCompany.setId(id);
        return ApiResult.success(bpCompanyService.updateBpCompany(bpCompany));
    }

    @PutMapping("/{id}/compliance-applicability")
    public ApiResult<Void> updateApplicability(
            @PathVariable Long id,
            @RequestBody ApplicabilityReq req) {
        requireAdmin();
        bpCompanyService.updateComplianceApplicability(id, req.getApplicability(), req.getNote(), SecurityUtils.currentUserId());
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/bank-accounts")
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
    public ApiResult<BpTerms> addTerms(@PathVariable Long id, @RequestBody BpTerms terms) {
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
