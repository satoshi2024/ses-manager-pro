package com.ses.service.security;

import java.util.Set;

/**
 * データスコープ（行レベル可視性）解決サービス。
 * 発動条件は「config scope.sales-own-data-only=true かつ 現在ユーザーが営業ロール」または
 * 「組織scopeが有効なマネージャー」。管理者・一般ロールは既存の全件/DataScope規則を維持する。
 *
 * <p>適用パターンは2種に限定する（散在防止）:
 * <ul>
 *   <li>一覧/検索: ページングクエリに {@code in("id", allowedIds)} を追加。空集合なら空ページを返す。</li>
 *   <li>詳細/ID直指定: 取得後に {@code if (isScoped() && !allowed.contains(id)) throw 404}。</li>
 * </ul>
 * ID 集合は数百件規模を想定（大規模化時は EXISTS サブクエリ化を検討）。
 */
public interface DataScopeService {

    /** スコープ発動中か（営業のDataScopeまたはマネージャーの組織scope）。 */
    boolean isScoped();

    /** 営業ロール固有のDataScopeが発動中か。組織scopeは含めない。 */
    boolean isSalesDataScoped();

    /** 現任担当（t_engineer_sales.released_at IS NULL）の要員ID集合。 */
    Set<Long> allowedEngineerIds();

    /** sales_user_id=自分 ∪ sales_user_id IS NULL（未帰属は可視）の契約ID集合。 */
    Set<Long> allowedContractIds();

    /** 担当契約・担当要員の提案の顧客ID集合。 */
    Set<Long> allowedCustomerIds();

    /** proposed_by=自分 ∪ engineer_id ∈ allowedEngineerIds の提案ID集合。 */
    Set<Long> allowedProposalIds();

    /** 担当案件のID集合。 */
    Set<Long> allowedProjectIds();

    /** 担当契約から導出した組織ID。組織scopeとの積集合にのみ利用する。 */
    default Set<Long> allowedOrganizationIds() { return Set.of(); }

    /**
     * asOf時点の許可契約ID集合（order-acceptance-workflow R09-P1-04対応）。
     * マネージャーの組織scopeはasOf時点の所属で解決し、異動前後の過去月でも母集団を一致させる。
     * 営業のDataScopeは契約の現行sales_user_id帰属（時変でない）のためasOfは影響しない。
     */
    default Set<Long> allowedContractIdsAsOf(java.time.LocalDate asOf) { return allowedContractIds(); }

    void assertAllowedCustomer(Long customerId);
    void assertAllowedEngineer(Long engineerId);
    void assertAllowedContract(Long contractId);

    /**
     * asOf時点の許可契約ID集合で契約へのアクセスを検証する（order-acceptance-workflow R09-P1-04）。
     * マネージャーの組織scopeはasOf時点の所属で解決するため、異動前後の過去月でも
     * 検収のlist/detail/count/submit/通知が同一母集団になる。営業のDataScopeは契約の
     * 現行sales_user_id帰属（時変でない）のためasOfは影響しない。
     */
    default void assertAllowedContractAsOf(Long contractId, java.time.LocalDate asOf) {
        if (isScoped() && !allowedContractIdsAsOf(asOf).contains(contractId)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }
    void assertAllowedProject(Long projectId);
    void assertAllowedProposal(Long proposalId);
}
