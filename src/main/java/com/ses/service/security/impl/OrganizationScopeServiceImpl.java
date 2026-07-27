package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 組織スコープの実装。組織ID条件はSQL wrapperへ追加し、画面後処理では絞り込まない。 */
@Service
@RequestScope
@RequiredArgsConstructor
public class OrganizationScopeServiceImpl implements OrganizationScopeService {

    private static final String ROLE_ADMIN = "管理者";
    private static final String ROLE_MANAGER = "マネージャー";

    private final OrganizationUnitMapper organizationUnitMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final ContractMapper contractMapper;
    private final InvoiceMapper invoiceMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final OrganizationService organizationService;
    private final ObjectProvider<DataScopeService> dataScopeServiceProvider;

    /** 既存DBの所属backfill完了前はscopeを公開しないための明示的な rollout gate。 */
    @Value("${organization.scope.enabled:false}")
    private boolean organizationScopeEnabled;

    private ScopeCacheKey cachedKey;
    private Set<Long> cachedIds;

    @Override
    public boolean hasFullAccess() {
        return !organizationScopeEnabled || ROLE_ADMIN.equals(SecurityUtils.currentRole());
    }

    @Override
    public Set<Long> allowedOrganizationIds(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Long userId = resolveCurrentUserId();
        String role = SecurityUtils.currentRole();
        if (ROLE_ADMIN.equals(role)) {
            return Collections.emptySet();
        }
        if (userId == null || role == null) {
            return Collections.emptySet();
        }

        List<UserOrganization> assignments = activeAssignments(userId, date);
        List<UserOrganization> managedAssignments = ROLE_MANAGER.equals(role)
                ? userOrganizationMapper.selectActiveByManagerUserId(userId, date)
                : List.of();
        List<UserOrganization> cacheAssignments = new java.util.ArrayList<>(assignments);
        cacheAssignments.addAll(managedAssignments);
        LocalDateTime version = cacheAssignments.stream()
                .map(UserOrganization::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MIN);
        ScopeCacheKey key = new ScopeCacheKey(null, userId, role, date, version);
        if (key.equals(cachedKey) && cachedIds != null) {
            return cachedIds;
        }

        Set<Long> result = new HashSet<>();
        for (UserOrganization assignment : assignments) {
            if (assignment.getOrganizationId() == null) {
                continue;
            }
            if (ROLE_MANAGER.equals(role) && Integer.valueOf(1).equals(assignment.getPrimaryFlag())) {
                result.addAll(organizationService.descendantIds(assignment.getOrganizationId(), date));
            } else if (!ROLE_MANAGER.equals(role)) {
                // 営業/HR/一般ユーザーは所属組織だけ。子組織への拡張はしない。
                result.add(assignment.getOrganizationId());
            }
        }
        cachedKey = key;
        cachedIds = Collections.unmodifiableSet(result);
        return cachedIds;
    }

    @Override
    public Set<Long> allowedDirectUserIds(LocalDate asOf) {
        if (!ROLE_MANAGER.equals(SecurityUtils.currentRole()) || resolveCurrentUserId() == null) {
            return Set.of();
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return userOrganizationMapper.selectActiveByManagerUserId(resolveCurrentUserId(), date)
                .stream().map(UserOrganization::getUserId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<Long> allowedEngineerIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(engineerAccountLinkMapper.selectEngineerIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedContractIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectContractIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedInvoiceIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(invoiceMapper.selectInvoiceIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedCustomerIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        Set<Long> contractIds = allowedContractIds(asOf);
        if (contractIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectCustomerIdsByContractIds(new java.util.ArrayList<>(contractIds)));
    }

    @Override
    public Set<Long> allowedProjectIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        Set<Long> contractIds = allowedContractIds(asOf);
        if (contractIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectProjectIdsByContractIds(new java.util.ArrayList<>(contractIds)));
    }

    @Override
    public boolean isAllowedUser(Long targetUserId, LocalDate asOf) {
        if (hasFullAccess()) {
            return true;
        }
        if (targetUserId == null || resolveCurrentUserId() == null) {
            return false;
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (ROLE_MANAGER.equals(SecurityUtils.currentRole())
                && allowedDirectUserIds(date).contains(targetUserId)) {
            return true;
        }
        for (UserOrganization assignment : activeAssignments(targetUserId, date)) {
            if (assignment.getOrganizationId() != null
                    && allowedOrganizationIds(date).contains(assignment.getOrganizationId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<OrganizationUnit> listVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        return organizationUnitMapper.selectList(visibleQuery(legalEntityId, asOf, true));
    }

    @Override
    public long countVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        return organizationUnitMapper.selectCount(visibleQuery(legalEntityId, asOf, false));
    }

    @Override
    public List<OrganizationUnit> exportVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        // exportも同じSQL母集団を使う。画面一覧を取得してから絞り込まない。
        return organizationUnitMapper.selectList(visibleQuery(legalEntityId, asOf, true));
    }

    @Override
    public <T> LambdaQueryWrapper<T> applyOrganizationScope(LambdaQueryWrapper<T> query,
                                                              SFunction<T, ?> organizationColumn,
                                                              LocalDate asOf) {
        if (hasFullAccess()) {
            return query;
        }
        Set<Long> ids = allowedOrganizationIds(asOf);
        if (ids.isEmpty()) {
            // ID列に存在しない値を入れ、SQL側で0件にする。取得後filterは行わない。
            return query.eq(organizationColumn, -1L);
        }
        return query.in(organizationColumn, ids);
    }

    @Override
    public Set<Long> intersectWithDataScope(Collection<Long> organizationIds,
                                             Collection<Long> dataScopeIds) {
        Set<Long> orgIds = organizationIds == null ? Set.of() : new HashSet<>(organizationIds);
        DataScopeService dataScope = dataScopeServiceProvider.getIfAvailable();
        if (dataScope == null || !dataScope.isScoped()) {
            return Collections.unmodifiableSet(orgIds);
        }
        if (dataScopeIds == null) {
            return Set.of();
        }
        orgIds.retainAll(dataScopeIds);
        return Collections.unmodifiableSet(orgIds);
    }

    @Override
    public void assertAllowedOrganization(Long organizationId, LocalDate asOf) {
        if (organizationId == null || (!hasFullAccess()
                && !allowedOrganizationIds(asOf).contains(organizationId))) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
    }

    private List<UserOrganization> activeAssignments(Long userId, LocalDate asOf) {
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, asOf)
                .and(w -> w.isNull(UserOrganization::getValidTo)
                        .or().ge(UserOrganization::getValidTo, asOf))
                .orderByAsc(UserOrganization::getId));
    }

    private Long resolveCurrentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null || sysUserMapper == null) {
            return userId;
        }
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            return null;
        }
        com.ses.entity.SysUser user = sysUserMapper.selectByUsername(username);
        return user == null ? null : user.getId();
    }

    private LambdaQueryWrapper<OrganizationUnit> visibleQuery(Long legalEntityId,
                                                                LocalDate asOf,
                                                                boolean ordered) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LambdaQueryWrapper<OrganizationUnit> query = new LambdaQueryWrapper<OrganizationUnit>()
                .eq(legalEntityId != null, OrganizationUnit::getLegalEntityId, legalEntityId)
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date))
                .eq(OrganizationUnit::getStatus, "有効");
        if (ordered) {
            query.orderByAsc(OrganizationUnit::getParentId)
                    .orderByAsc(OrganizationUnit::getCode);
        }
        return applyOrganizationScope(query, OrganizationUnit::getId, date);
    }

    private record ScopeCacheKey(Long tenantId, Long userId, String role,
                                 LocalDate asOf, LocalDateTime version) {
    }
}
