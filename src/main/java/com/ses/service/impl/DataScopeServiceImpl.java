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
import org.springframework.web.context.annotation.RequestScope;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DataScopeService} の実装。営業ロールの担当データのみ可視化するオプトイン。
 * 各集合はメソッド呼び出しごとに解決する（数百件規模想定）。
 */
@Service
@RequestScope
@RequiredArgsConstructor
public class DataScopeServiceImpl implements DataScopeService {

    private static final String CONFIG_KEY = "scope.sales-own-data-only";

    private final SystemConfigService systemConfigService;
    private final EngineerSalesMapper engineerSalesMapper;
    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final ProjectMapper projectMapper;
    private final SalesActivityMapper salesActivityMapper;

    private Set<Long> engineerIdsCache;
    private Set<Long> contractIdsCache;
    private Set<Long> proposalIdsCache;
    private Set<Long> customerIdsCache;

    @Override
    public boolean isScoped() {
        if (!"true".equalsIgnoreCase(systemConfigService.getString(CONFIG_KEY, "false"))) {
            return false;
        }
        return "営業".equals(SecurityUtils.currentRole());
    }

    @Override
    public Set<Long> allowedEngineerIds() {
        return computeEngineerIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedContractIds() {
        return computeContractIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedProposalIds() {
        return computeProposalIds(SecurityUtils.currentUserId());
    }

    @Override
    public Set<Long> allowedCustomerIds() {
        return computeCustomerIds(SecurityUtils.currentUserId());
    }

    // ===== 純ロジック（テスト容易化のため userId を引数に取る） =====

    Set<Long> computeEngineerIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (engineerIdsCache != null) return engineerIdsCache;
        engineerIdsCache = engineerSalesMapper.selectList(new QueryWrapper<EngineerSales>()
                        .eq("sales_user_id", userId)
                        .isNull("released_at"))
                .stream().map(EngineerSales::getEngineerId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return engineerIdsCache;
    }

    Set<Long> computeContractIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (contractIdsCache != null) return contractIdsCache;
        // sales_user_id=自分 ∪ 未帰属(NULL) を可視とする。
        contractIdsCache = contractMapper.selectList(new QueryWrapper<Contract>()
                        .and(w -> w.eq("sales_user_id", userId).or().isNull("sales_user_id")))
                .stream().map(Contract::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return contractIdsCache;
    }

    Set<Long> computeProposalIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (proposalIdsCache != null) return proposalIdsCache;
        Set<Long> engineerIds = computeEngineerIds(userId);
        proposalIdsCache = proposalMapper.selectList(new QueryWrapper<Proposal>()
                        .and(w -> {
                            w.eq("proposed_by", userId);
                            if (!engineerIds.isEmpty()) {
                                w.or().in("engineer_id", engineerIds);
                            }
                        }))
                .stream().map(Proposal::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return proposalIdsCache;
    }

    private Set<Long> projectIdsCache;

    Set<Long> computeProjectIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (projectIdsCache != null) return projectIdsCache;
        Set<Long> proposalIds = computeProposalIds(userId);
        if (proposalIds.isEmpty()) {
            projectIdsCache = Collections.emptySet();
        } else {
            projectIdsCache = proposalMapper.selectList(new QueryWrapper<Proposal>().in("id", proposalIds))
                    .stream().map(Proposal::getProjectId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }
        return projectIdsCache;
    }

    Set<Long> computeCustomerIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (customerIdsCache != null) return customerIdsCache;
        Set<Long> customerIds = new HashSet<>();
        // 担当契約の顧客
        contractMapper.selectList(new QueryWrapper<Contract>()
                        .and(w -> w.eq("sales_user_id", userId).or().isNull("sales_user_id")))
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
                
        customerIdsCache = customerIds;
        return customerIdsCache;
    }
}
