package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Contract;
import com.ses.entity.EngineerSales;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DataScopeService} の実装。営業ロールの担当データのみ可視化するオプトイン。
 * 各集合はメソッド呼び出しごとに解決する（数百件規模想定）。
 *
 * <p><b>シングルトンである理由</b>: 本サービスは業務サービス（例:
 * {@code WorkRecordService#approve}）から呼ばれ、業務サービスはHTTPリクエスト以外
 * （バッチ、{@code @Async}、ワーカースレッド）からも実行される。{@code @RequestScope}のままだと
 * そこで{@code ScopeNotActiveException}になり業務処理自体が失敗する。
 * キャッシュはリクエスト属性へ退避し、リクエスト外ではキャッシュせず都度算出する。
 */
@Service
@RequiredArgsConstructor
public class DataScopeServiceImpl implements DataScopeService {

    private static final String CONFIG_KEY = "scope.sales-own-data-only";

    /** リクエスト属性へ退避するキャッシュの格納キー。 */
    private static final String CACHE_ATTRIBUTE = DataScopeServiceImpl.class.getName() + ".CACHE";

    private final SystemConfigService systemConfigService;
    private final EngineerSalesMapper engineerSalesMapper;
    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final ProjectMapper projectMapper;
    private final SalesActivityMapper salesActivityMapper;
    private final ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeServiceProvider;

    /**
     * リクエスト単位のキャッシュ。リクエスト外では毎回新しい入れ物を返してキャッシュしない。
     *
     * <p>スレッドローカルにしてはならない。ワーカースレッドが使い回されるため、
     * リクエスト境界で捨てないと別の営業担当者の可視ID集合をそのまま返してしまう。
     */
    private Caches cache() {
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return new Caches();
        }
        Object existing = attributes.getAttribute(CACHE_ATTRIBUTE,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Caches caches) {
            return caches;
        }
        Caches created = new Caches();
        attributes.setAttribute(CACHE_ATTRIBUTE, created,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return created;
    }

    private static final class Caches {
        private Set<Long> engineerIds;
        private Set<Long> contractIds;
        private Set<Long> proposalIds;
        private Set<Long> customerIds;
        private Set<Long> projectIds;
    }

    @Override
    public boolean isScoped() {
        if (isOrganizationScoped()) {
            return true;
        }
        return isSalesDataScoped();
    }

    @Override
    public boolean isSalesDataScoped() {
        return "true".equalsIgnoreCase(systemConfigService.getString(CONFIG_KEY, "false"))
                && "営業".equals(SecurityUtils.currentRole());
    }

    private boolean isOrganizationScoped() {
        com.ses.service.security.OrganizationScopeService scope = organizationScopeServiceProvider == null
                ? null : organizationScopeServiceProvider.getIfAvailable();
        return scope != null && !scope.hasFullAccess();
    }

    private com.ses.service.security.OrganizationScopeService organizationScope() {
        return organizationScopeServiceProvider == null ? null : organizationScopeServiceProvider.getIfAvailable();
    }

    @Override
    public Set<Long> allowedEngineerIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedEngineerIds(java.time.LocalDate.now());
        }
        return computeEngineerIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedContractIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedContractIds(java.time.LocalDate.now());
        }
        return computeContractIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedContractIdsAsOf(java.time.LocalDate asOf) {
        java.time.LocalDate date = asOf == null ? java.time.LocalDate.now() : asOf;
        if (isOrganizationScoped()) {
            return organizationScope().allowedContractIds(date);
        }
        // 営業DataScopeは契約の現行sales_user_id帰属（時変でない）のためasOfは影響しない
        return computeContractIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedProposalIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedProposalIds(java.time.LocalDate.now());
        }
        return computeProposalIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedCustomerIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedCustomerIds(java.time.LocalDate.now());
        }
        return computeCustomerIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedProjectIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedProjectIds(java.time.LocalDate.now());
        }
        return computeProjectIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedOrganizationIds() {
        if (isOrganizationScoped()) {
            return organizationScope().allowedOrganizationIds(java.time.LocalDate.now());
        }
        if (!isScoped()) return Collections.emptySet();
        Set<Long> contracts = allowedContractIds();
        if (contracts.isEmpty()) return Collections.emptySet();
        List<Long> ids = contractMapper.selectOrganizationIdsByContractIds(new java.util.ArrayList<>(contracts));
        return ids == null ? Collections.emptySet() : new HashSet<>(ids);
    }

    @Override
    public void assertAllowedCustomer(Long customerId) {
        if (isScoped() && !allowedCustomerIds().contains(customerId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @Override
    public void assertAllowedEngineer(Long engineerId) {
        if (isScoped() && !allowedEngineerIds().contains(engineerId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @Override
    public void assertAllowedContract(Long contractId) {
        if (isScoped() && !allowedContractIds().contains(contractId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @Override
    public void assertAllowedProject(Long projectId) {
        if (isScoped() && !allowedProjectIds().contains(projectId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @Override
    public void assertAllowedProposal(Long proposalId) {
        if (isScoped() && !allowedProposalIds().contains(proposalId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    // ===== 純ロジック（テスト容易化のため userId を引数に取る） =====

    Set<Long> computeEngineerIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Caches caches = cache();
        if (caches.engineerIds != null) return caches.engineerIds;
        caches.engineerIds = engineerSalesMapper.selectList(new QueryWrapper<EngineerSales>()
                        .eq("sales_user_id", userId)
                        .isNull("released_at"))
                .stream().map(EngineerSales::getEngineerId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return caches.engineerIds;
    }

    Set<Long> computeContractIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Caches caches = cache();
        if (caches.contractIds != null) return caches.contractIds;
        // sales_user_id=自分 ∪ 未帰属(NULL) を可視とする。
        caches.contractIds = contractMapper.selectList(new QueryWrapper<Contract>()
                        .and(w -> w.eq("sales_user_id", userId).or().isNull("sales_user_id")))
                .stream().map(Contract::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return caches.contractIds;
    }

    Set<Long> computeProposalIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Caches caches = cache();
        if (caches.proposalIds != null) return caches.proposalIds;
        Set<Long> engineerIds = computeEngineerIds(userId);
        caches.proposalIds = proposalMapper.selectList(new QueryWrapper<Proposal>()
                        .and(w -> {
                            w.eq("proposed_by", userId);
                            if (!engineerIds.isEmpty()) {
                                w.or().in("engineer_id", engineerIds);
                            }
                        }))
                .stream().map(Proposal::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return caches.proposalIds;
    }

    Set<Long> computeProjectIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Caches caches = cache();
        if (caches.projectIds != null) return caches.projectIds;
        Set<Long> proposalIds = computeProposalIds(userId);
        if (proposalIds.isEmpty()) {
            caches.projectIds = Collections.emptySet();
        } else {
            caches.projectIds = proposalMapper.selectList(new QueryWrapper<Proposal>().in("id", proposalIds))
                    .stream().map(Proposal::getProjectId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }
        return caches.projectIds;
    }

    Set<Long> computeCustomerIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Caches caches = cache();
        if (caches.customerIds != null) return caches.customerIds;
        Set<Long> customerIds = new HashSet<>();
        // 担当契約の顧客。customer権限は「自分が担当」の契約由来のみとする（未帰属契約1件で顧客全体へ
        // 権限拡大するのを防ぐ / R3R-34）。未帰属契約は contract 一覧では見えるが顧客アクセス根拠にはしない。
        contractMapper.selectList(new QueryWrapper<Contract>()
                        .eq("sales_user_id", userId))
                .forEach(c -> { if (c.getCustomerId() != null) customerIds.add(c.getCustomerId()); });
        // 担当要員/自分の提案の案件→顧客
        Set<Long> proposalIds = computeProposalIds(userId);
        if (!proposalIds.isEmpty()) {
            Set<Long> projectIds = proposalMapper.selectList(new QueryWrapper<Proposal>()
                            .in("id", proposalIds))
                    .stream().map(Proposal::getProjectId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!projectIds.isEmpty()) {
                projectMapper.selectList(new QueryWrapper<Project>().in("id", projectIds))
                        .forEach(p -> { if (p.getCustomerId() != null) customerIds.add(p.getCustomerId()); });
            }
        }
        // 営業活動の担当が自分由来の顧客を追加
        salesActivityMapper.selectList(new QueryWrapper<com.ses.entity.SalesActivity>()
                        .eq("created_by", userId))
                .forEach(sa -> { if (sa.getCustomerId() != null) customerIds.add(sa.getCustomerId()); });
                
        caches.customerIds = customerIds;
        return caches.customerIds;
    }
}
