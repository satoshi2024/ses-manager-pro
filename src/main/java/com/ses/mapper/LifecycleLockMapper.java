package com.ses.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code LifecycleLockPlan.Rank} ごとのlocking SQL（NF01細粒度epoch計画 §3.3。呼出しは {@code LifecycleLockExecutor} だけ）。
 *
 * <ul>
 *   <li>1 rank につき1文。各文は主キーの一点検索で1行だけをlockし、IDだけを返す（行が無ければ {@code null}）。
 *       epoch列は読まない（epoch値は {@link AuthorizationEpochMapper} が読む。全体epoch行のlockも同Mapperが持つ）。</li>
 *   <li>tenant列を持つ表は {@code id + tenant_id} で照合する。tenant列を持たない表（組織・営業割当・会計履歴・提案・案件・
 *       営業活動・資産・貸与・検収・廃棄申請）はIDだけで照合する。</li>
 *   <li>論理削除フラグでは絞らない（物理行をlockし、削除状態は呼出側の述語再評価で判定する）。</li>
 *   <li>共有lock（{@code FOR SHARE}）を受け付けるのは計画 §3.1 で reader が S を取る行（利用者・組織・要員・文書・版）だけで、
 *       {@code exclusive=false} のときだけ {@code FOR SHARE} になる。他の行は常に {@code FOR UPDATE}。</li>
 *   <li>タスク行は、同じtransactionで既にX lockした案件（{@code caseIds}）に属する場合だけlockする（case X → task X）。</li>
 * </ul>
 * <p>{@code FOR SHARE} はH2で解釈されないため、共有lockはMySQLでだけ実行する。</p>
 * <p>各文は実行前にMyBatisのsession cacheを消す（{@code flushCache}）。planの取得後、呼出側は依存先を通常読取で列挙し直して
 * discoveryと比べるが、同じtransactionでlock前に同じ照会を実行していると、cacheがlock前の結果を返して待っていた相手の
 * commitを見落とし、再discoveryが常に一致してしまう（W3-16b M6で判明）。</p>
 */
@Mapper
public interface LifecycleLockMapper {

    String MODE = "<choose><when test='exclusive'>FOR UPDATE</when><otherwise>FOR SHARE</otherwise></choose>";

    // 順2
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_approval_request WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockApprovalRequest(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順3
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM sys_user WHERE id = #{id} AND tenant_id = #{tenantId} " + MODE + "</script>")
    Long lockSysUser(@Param("id") long id, @Param("tenantId") String tenantId, @Param("exclusive") boolean exclusive);

    // 順4
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_lifecycle_case WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockLifecycleCase(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順5（案件はlock済み。案件の一覧はtenant照合済みのIDだけを渡す）
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM t_lifecycle_task WHERE id = #{id} AND case_id IN "
            + "<foreach collection='caseIds' item='caseId' open='(' separator=',' close=')'>#{caseId}</foreach> "
            + "FOR UPDATE</script>")
    Long lockLifecycleTask(@Param("id") long id, @Param("caseIds") List<Long> caseIds);

    // 順6
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM m_organization_unit WHERE id = #{id} " + MODE + "</script>")
    Long lockOrganization(@Param("id") long id, @Param("exclusive") boolean exclusive);

    // 順7
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM t_engineer WHERE id = #{id} AND tenant_id = #{tenantId} " + MODE + "</script>")
    Long lockEngineer(@Param("id") long id, @Param("tenantId") String tenantId, @Param("exclusive") boolean exclusive);

    // 順8
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_user_organization WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockUserOrganization(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順9
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_engineer_account_link WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockEngineerAccountLink(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順10
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_engineer_sales WHERE id = #{id} FOR UPDATE")
    Long lockEngineerSales(@Param("id") long id);

    // 順11
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_engineer_accounting_history WHERE id = #{id} FOR UPDATE")
    Long lockEngineerAccountingHistory(@Param("id") long id);

    // 順12
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_portal_user_engineer_link WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockPortalEngineerLink(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順13（業務親。初回sliceのreaderは取らない）
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM m_customer WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockCustomer(@Param("id") long id, @Param("tenantId") String tenantId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_contract WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockContract(@Param("id") long id, @Param("tenantId") String tenantId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_proposal WHERE id = #{id} FOR UPDATE")
    Long lockProposal(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_project WHERE id = #{id} FOR UPDATE")
    Long lockProject(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_sales_activity WHERE id = #{id} FOR UPDATE")
    Long lockSalesActivity(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM m_asset WHERE id = #{id} FOR UPDATE")
    Long lockAsset(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_asset_assignment WHERE id = #{id} FOR UPDATE")
    Long lockAssetAssignment(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_acceptance WHERE id = #{id} FOR UPDATE")
    Long lockAcceptance(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_service_request WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockServiceRequest(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順14
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_external_account_reference WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockExternalAccountReference(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順15
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM t_document WHERE id = #{id} AND tenant_id = #{tenantId} " + MODE + "</script>")
    Long lockDocument(@Param("id") long id, @Param("tenantId") String tenantId, @Param("exclusive") boolean exclusive);

    // 順16
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id FROM t_document_version WHERE id = #{id} AND tenant_id = #{tenantId} " + MODE + "</script>")
    Long lockDocumentVersion(@Param("id") long id, @Param("tenantId") String tenantId,
                             @Param("exclusive") boolean exclusive);

    // 順17
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_document_link WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lockDocumentLink(@Param("id") long id, @Param("tenantId") String tenantId);

    // 順18
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM t_document_disposal_request WHERE id = #{id} FOR UPDATE")
    Long lockDocumentDisposalRequest(@Param("id") long id);
}
