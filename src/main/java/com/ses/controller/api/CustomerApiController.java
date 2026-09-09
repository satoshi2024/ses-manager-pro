package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.customer.CustomerSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SalesActivity;
import com.ses.service.ContractService;
import com.ses.service.CustomerService;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import com.ses.service.SalesActivityService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 顧客APIコントローラー
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerApiController {

    private final CustomerService customerService;
    private final ProjectService projectService;
    private final ProposalService proposalService;
    private final ContractService contractService;
    private final SalesActivityService salesActivityService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.security.AuthorizationService authorizationService;
    private final TenantOwnershipResolver tenantOwnershipResolver;
    private final com.ses.mapper.ProjectMapper projectMapper;
    private final com.ses.mapper.ContractMapper contractMapper;

    /**
     * 顧客一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Customer>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String commercialFlow,
            @RequestParam(required = false) String trustLevel) {

        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ
        Page<Customer> page = PageUtils.safePage(current, size);
        // データスコープ: 営業ロール制限時は担当顧客のみ。
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        Page<Customer> result = customerService.pageForTenant(page, tenantId, allowed,
                companyName, commercialFlow, trustLevel);
        result.getRecords().forEach(this::maskLegacyContact);
        return ApiResult.success(result);
    }

    /**
     * ドロップダウン用顧客一覧（軽量化）
     */
    @GetMapping("/options")
    public ApiResult<List<com.ses.dto.common.OptionDto>> getOptions() {
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        List<com.ses.dto.common.OptionDto> options = tenantOwnershipResolver
                .selectCustomers(tenantId, allowed, null).stream()
                .map(c -> new com.ses.dto.common.OptionDto(c.getId(), c.getCompanyName()))
                .collect(Collectors.toList());
        return ApiResult.success(options);
    }

    /**
     * 顧客詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Customer> getById(@PathVariable Long id) {
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        if (!allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        var entity = tenantOwnershipResolver.selectCustomer(tenantId, id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        maskLegacyContact(entity);
        return ApiResult.success(entity);
    }

    /**
     * 顧客登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody com.ses.dto.customer.CustomerSaveDto customerDto) {
        Customer customer = new Customer();
        org.springframework.beans.BeanUtils.copyProperties(customerDto, customer);
        clearLegacyContactWrite(customer);
        com.ses.common.util.EntityProtectUtil.protectForCreate(customer);
        return ApiResult.success(customerService.save(customer));
    }

    /**
     * 顧客更新
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody com.ses.dto.customer.CustomerSaveDto customerDto) {
        Customer customer = new Customer();
        org.springframework.beans.BeanUtils.copyProperties(customerDto, customer);
        clearLegacyContactWrite(customer);
        customer.setId(id);
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        if (allowed != null && !allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedCustomer(id);
        customerService.updateWithOptimisticLock(customer);
        return ApiResult.success(true);
    }

    /**
     * 顧客削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id, @RequestParam Integer version) {
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        if (!allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedCustomer(id);
        boolean success = customerService.removeById(id, version);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * 実績サマリ
     */
    @GetMapping("/{id}/summary")
    public ApiResult<CustomerSummaryDto> getSummary(@PathVariable Long id) {
        String tenantId = currentTenant();
        java.util.Set<Long> allowed = effectiveCustomerIds(tenantId);
        if (!allowed.contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedCustomer(id);

        CustomerSummaryDto dto = new CustomerSummaryDto();

        // 案件数
        // The validated customer is resolved again by the ownership SQL before aggregation.
        List<Long> projectIds = projectMapper.selectIdsByCustomerAndTenant(id, tenantId);
        long projectCount = projectIds.size();
        dto.setProjectCount(projectCount);

        // 稼動中契約数
        long activeContractCount = contractMapper.countByCustomerAndTenant(id, tenantId, "稼動中");
        dto.setActiveContractCount(activeContractCount);

        // 要フォロー活動数
        long pendingFollowUpCount = salesActivityService.count(new LambdaQueryWrapper<SalesActivity>()
                .eq(SalesActivity::getCustomerId, id)
                .le(SalesActivity::getNextActionDate, LocalDate.now())
                .eq(SalesActivity::getCompletedFlag, 0));
        dto.setPendingFollowUpCount(pendingFollowUpCount);

        // 提案関連
        if (projectIds.isEmpty()) {
            dto.setProposalCount(0L);
            dto.setWonCount(0L);
            dto.setWinRate(null);
        } else {
            long proposalCount = proposalService.count(new LambdaQueryWrapper<Proposal>()
                    .in(Proposal::getProjectId, projectIds));
            dto.setProposalCount(proposalCount);

            long wonCount = proposalService.count(new LambdaQueryWrapper<Proposal>()
                    .in(Proposal::getProjectId, projectIds)
                    .eq(Proposal::getStatus, "成約"));
            dto.setWonCount(wonCount);

            long lostCount = proposalService.count(new LambdaQueryWrapper<Proposal>()
                    .in(Proposal::getProjectId, projectIds)
                    .eq(Proposal::getStatus, "見送り"));

            if (wonCount + lostCount == 0) {
                dto.setWinRate(null);
            } else {
                dto.setWinRate((double) wonCount / (wonCount + lostCount));
            }
        }

        return ApiResult.success(dto);
    }

    private java.util.Set<Long> effectiveCustomerIds(String tenantId) {
        java.util.Set<Long> ownedIds = tenantOwnershipResolver.resolveCustomerIds(tenantId);
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedCustomerIds() : null;
        java.util.Set<Long> result = new java.util.HashSet<>(ownedIds);
        if (organizationScopeService.hasFullAccess()) {
            if (dataIds != null) result.retainAll(dataIds);
            return result;
        }
        java.util.Set<Long> scoped = organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedCustomerIds(java.time.LocalDate.now()), dataIds);
        if (scoped == null) return result;
        result.retainAll(scoped);
        return result;
    }

    private String currentTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    /** 旧contact_*列は移行後の互換表示専用。新規書込み経路へ流さない。 */
    private void clearLegacyContactWrite(Customer customer) {
        customer.setContactPerson(null);
        customer.setContactEmail(null);
        customer.setContactPhone(null);
    }

    /** 既存列を返す必要がある旧画面でも、連絡先APIと同じPIIマスク規則を適用する。 */
    private void maskLegacyContact(Customer customer) {
        if (customer == null || canViewPii()) return;
        customer.setContactEmail(maskEmail(customer.getContactEmail()));
        customer.setContactPhone(maskPhone(customer.getContactPhone()));
    }

    private String maskEmail(String value) {
        if (value == null || value.isBlank()) return value;
        int at = value.indexOf('@');
        return at > 1 ? value.charAt(0) + "***" + value.substring(at) : "***";
    }

    private String maskPhone(String value) {
        if (value == null || value.isBlank()) return value;
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }

    private boolean canViewPii() {
        return authorizationService.isAllowed(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(),
                "customer.pii.view");
    }
}
