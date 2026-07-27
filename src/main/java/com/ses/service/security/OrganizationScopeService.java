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

    /** 管理者直属として個別に扱うユーザーID。組織全体へは拡張しない。 */
    default Set<Long> allowedDirectUserIds(LocalDate asOf) { return Set.of(); }

    /** 指定ユーザーの勤怠・承認対象が現在ユーザーの組織scope内か。 */
    default boolean isAllowedUser(Long userId, LocalDate asOf) { return hasFullAccess(); }

    default void assertAllowedUser(Long userId, LocalDate asOf) {
        if (!isAllowedUser(userId, asOf)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.organization.scope.notFound");
        }
    }

    default Set<Long> allowedOrganizationIds() {
        return allowedOrganizationIds(LocalDate.now());
    }

    /** 組織所属から導出した要員ID。非管理者では空集合を全件扱いしない。 */
    Set<Long> allowedEngineerIds(LocalDate asOf);

    /** 組織所属から導出した契約ID。契約の要員所属を基準にする。 */
    Set<Long> allowedContractIds(LocalDate asOf);

    /** 組織所属から導出した請求書ID。請求書に紐づく契約要員を基準にする。 */
    Set<Long> allowedInvoiceIds(LocalDate asOf);

    /** 組織所属契約から導出した顧客ID。顧客自体に組織列がないためSQLで関係を辿る。 */
    Set<Long> allowedCustomerIds(LocalDate asOf);

    /** 組織所属契約から導出した案件ID。顧客単位の後フィルターには依存しない。 */
    Set<Long> allowedProjectIds(LocalDate asOf);

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
