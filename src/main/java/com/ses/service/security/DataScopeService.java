package com.ses.service.security;

import java.util.Set;

/**
 * データスコープ（行レベル可視性）解決サービス。
 * 発動条件は「config scope.sales-own-data-only=true かつ 現在ユーザーが営業ロール」。
 * 管理者・マネージャー等は常に全件（isScoped=false）。
 *
 * <p>適用パターンは2種に限定する（散在防止）:
 * <ul>
 *   <li>一覧/検索: ページングクエリに {@code in("id", allowedIds)} を追加。空集合なら空ページを返す。</li>
 *   <li>詳細/ID直指定: 取得後に {@code if (isScoped() && !allowed.contains(id)) throw 404}。</li>
 * </ul>
 * ID 集合は数百件規模を想定（大規模化時は EXISTS サブクエリ化を検討）。
 */
public interface DataScopeService {

    /** スコープ発動中か（config=true かつ 現在ユーザーが営業）。 */
    boolean isScoped();

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

    void assertAllowedCustomer(Long customerId);
    void assertAllowedEngineer(Long engineerId);
    void assertAllowedContract(Long contractId);
    void assertAllowedProject(Long projectId);
    void assertAllowedProposal(Long proposalId);
}
