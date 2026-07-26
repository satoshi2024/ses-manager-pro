package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.OrganizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    private final UserOrganizationMapper userOrganizationMapper;
    private final OrganizationService organizationService;
    private final ObjectProvider<DataScopeService> dataScopeServiceProvider;

    private ScopeCacheKey cachedKey;
    private Set<Long> cachedIds;

    @Override
    public boolean hasFullAccess() {
        return ROLE_ADMIN.equals(SecurityUtils.currentRole());
    }

    @Override
    public Set<Long> allowedOrganizationIds(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Long userId = SecurityUtils.currentUserId();
        String role = SecurityUtils.currentRole();
        if (ROLE_ADMIN.equals(role)) {
            return Collections.emptySet();
        }
        if (userId == null || role == null) {
            return Collections.emptySet();
        }

        List<UserOrganization> assignments = activeAssignments(userId, date);
        LocalDateTime version = assignments.stream()
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
            throw BusinessException.of("error.organization.scope.notAllowed");
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
