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
 *
 * <h2>結合規則（R3.1／R3.2の明文化）</h2>
 * <ol>
 *   <li><b>メニュー権限</b>（{@code m_menu}/{@code t_role_menu}/{@code MenuPermissionFilter}）は
 *       独立した認可ゲートであり、組織スコープはこれを置換も緩和もしない。</li>
 *   <li><b>管理者</b>は全件。組織条件を一切付けない。</li>
 *   <li><b>部門責任者（マネージャー）</b>は主所属組織とその子孫。加えて {@code manager_user_id} で
 *       直接管理するユーザー個人を個別に許可する（そのユーザーの所属組織全体へは広げない）。</li>
 *   <li><b>営業/HR/要員などの一般ユーザー</b>は<b>既存のrole・DataScopeの範囲がそのまま母集団</b>であり、
 *       組織スコープでさらに狭めない。組織で追加的に絞ると、営業部の営業が技術部所属の要員の契約を
 *       担当するという通常の運用で積集合が空になり、自分の担当データすら見えなくなるため。</li>
 *   <li>マネージャーに対しては<b>組織スコープ ∩ DataScope</b>（同一ID母集団同士の積集合）を適用する。
 *       スコープの<b>拡張には決して使わない</b>。</li>
 * </ol>
 * 適用箇所は一覧・詳細・件数・export・download・通知・ダッシュボードで同一母集団とし、
 * 条件はすべてSQLへ渡す（画面取得後の絞り込みは行わない）。
 */
public interface OrganizationScopeService {

    /**
     * 組織階層によるscopeを受けないか（管理者・一般ユーザー・機能無効時はtrue）。
     * trueのとき {@link #allowedOrganizationIds(LocalDate)} は空集合を返すが、
     * これは「該当0件」ではなく「組織条件を付けない」の意味なので、必ず本メソッドを先に評価すること。
     */
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
