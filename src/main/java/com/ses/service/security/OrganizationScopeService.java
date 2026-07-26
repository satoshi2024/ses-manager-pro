package com.ses.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.ses.entity.OrganizationUnit;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 組織スコープをDBクエリ境界で適用するサービス。
 * メニュー権限や既存DataScopeを置換せず、必要な場合は積集合として利用する。
 */
public interface OrganizationScopeService {

    boolean hasFullAccess();

    Set<Long> allowedOrganizationIds(LocalDate asOf);

    default Set<Long> allowedOrganizationIds() {
        return allowedOrganizationIds(LocalDate.now());
    }

    List<OrganizationUnit> listVisibleOrganizations(Long legalEntityId, LocalDate asOf);

    long countVisibleOrganizations(Long legalEntityId, LocalDate asOf);

    List<OrganizationUnit> exportVisibleOrganizations(Long legalEntityId, LocalDate asOf);

    <T> LambdaQueryWrapper<T> applyOrganizationScope(LambdaQueryWrapper<T> query,
                                                       SFunction<T, ?> organizationColumn,
                                                       LocalDate asOf);

    default <T> LambdaQueryWrapper<T> applyOrganizationScope(LambdaQueryWrapper<T> query,
                                                               SFunction<T, ?> organizationColumn) {
        return applyOrganizationScope(query, organizationColumn, LocalDate.now());
    }

    /**
     * 組織scopeと既存DataScopeの結合。menu roleは別の認可ゲートとして維持し、
     * 同じ対象ID母集団を渡した場合だけ積集合を返す（scopeの拡張には使わない）。
     */
    Set<Long> intersectWithDataScope(Collection<Long> organizationIds, Collection<Long> dataScopeIds);

    void assertAllowedOrganization(Long organizationId, LocalDate asOf);

    default void assertAllowedOrganization(Long organizationId) {
        assertAllowedOrganization(organizationId, LocalDate.now());
    }
}
