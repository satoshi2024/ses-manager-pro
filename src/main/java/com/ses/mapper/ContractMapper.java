package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.entity.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Collection;

/**
 * 契約マッパー
 */
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.contract.ContractListDto;
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.dto.contract.RenewalCalendarItemDto;
import com.ses.dto.accounting.ManagementAccountingContractRow;
import java.time.LocalDate;

@Mapper
public interface ContractMapper extends BaseMapper<Contract> {

    /** 契約と関連する要員・案件・営業のownershipをSQL境界で固定する。 */
    String CONTRACT_REFERENCE_OWNERSHIP_C = ""
            + " AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = c.engineer_id"
            + " AND e.tenant_id IS NOT NULL AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)"
            + " AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id"
            + " AND p.customer_id = c.customer_id AND p.deleted_flag = 0"
            + " AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu"
            + " WHERE pu.id = p.created_by AND pu.tenant_id IS NOT NULL"
            + " AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))"
            + " AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su"
            + " WHERE su.id = c.sales_user_id AND su.tenant_id IS NOT NULL"
            + " AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0)) ";

    String CONTRACT_REFERENCE_OWNERSHIP_CT = ""
            + " AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = ct.engineer_id"
            + " AND e.tenant_id IS NOT NULL AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)"
            + " AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = ct.project_id"
            + " AND p.customer_id = ct.customer_id AND p.deleted_flag = 0"
            + " AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu"
            + " WHERE pu.id = p.created_by AND pu.tenant_id IS NOT NULL"
            + " AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))"
            + " AND (ct.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su"
            + " WHERE su.id = ct.sales_user_id AND su.tenant_id IS NOT NULL"
            + " AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0)) ";

    String CONTRACT_REFERENCE_OWNERSHIP_UNQUALIFIED = ""
            + " AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = engineer_id"
            + " AND e.tenant_id IS NOT NULL AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)"
            + " AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = project_id"
            + " AND p.customer_id = customer_id AND p.deleted_flag = 0"
            + " AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu"
            + " WHERE pu.id = p.created_by AND pu.tenant_id IS NOT NULL"
            + " AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))"
            + " AND (sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su"
            + " WHERE su.id = sales_user_id AND su.tenant_id IS NOT NULL"
            + " AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0)) ";

    /** 組織scopeの関連行は要員・連携ユーザー・所属のtenantを全て一致させる。 */
    String TENANT_LINK_ORGANIZATION_OWNERSHIP = ""
            + " AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0"
            + " AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id"
            + " AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)"
            + " AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}"
            + " AND uo.user_id = l.sys_user_id ";

    /** 任意の検索条件を保ったまま、契約・顧客のownershipをSQL境界で強制する。 */
    @Select("<script>SELECT c.* FROM t_contract c "
            + "WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0) "
            + CONTRACT_REFERENCE_OWNERSHIP_C
            + "<if test='ew != null and ew.nonEmptyOfWhere'>AND ${ew.sqlSegment}</if></script>")
    List<Contract> selectListForTenant(@org.apache.ibatis.annotations.Param(Constants.WRAPPER) Wrapper<Contract> wrapper,
                                       @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 検索結果のページングも同じownership SQLを通す。 */
    @Select("<script>SELECT c.* FROM t_contract c "
            + "WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0) "
            + CONTRACT_REFERENCE_OWNERSHIP_C
            + "<if test='ew != null and ew.nonEmptyOfWhere'>AND ${ew.sqlSegment}</if></script>")
    Page<Contract> selectPageForTenant(Page<Contract> page,
                                       @org.apache.ibatis.annotations.Param(Constants.WRAPPER) Wrapper<Contract> wrapper,
                                       @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** export上限確認も同じtenant ownershipを数える。 */
    @Select("<script>SELECT COUNT(*) FROM t_contract c "
            + "WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0) "
            + CONTRACT_REFERENCE_OWNERSHIP_C
            + "<if test='ew != null and ew.nonEmptyOfWhere'>AND ${ew.sqlSegment}</if></script>")
    long selectCountForTenant(@org.apache.ibatis.annotations.Param(Constants.WRAPPER) Wrapper<Contract> wrapper,
                              @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 月次snapshotの契約母集団を顧客ownershipへ限定する。 */
    @Select("<script>SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + CONTRACT_REFERENCE_OWNERSHIP_C
            + "<choose><when test='ids != null and ids.size() > 0'>AND c.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>AND 1 = 0</otherwise></choose></script>")
    List<Contract> selectByIdsForTenant(@org.apache.ibatis.annotations.Param("ids") Collection<Long> ids,
                                        @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT ct.* FROM t_contract ct INNER JOIN m_customer c ON c.id = ct.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ct.id = #{id} AND ct.customer_id = #{customerId} "
            + "AND ct.tenant_id IS NOT NULL AND ct.tenant_id = #{tenantId} AND ct.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_CT)
    Contract selectByIdForCustomerAndTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                                             @org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                             @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT ct.* FROM t_contract ct INNER JOIN m_customer c ON c.id = ct.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ct.id = #{id} AND ct.tenant_id IS NOT NULL AND ct.tenant_id = #{tenantId} "
            + "AND ct.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_CT)
    Contract selectByIdForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                                 @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 契約更新系の共通ロック。契約と顧客のtenantが一致しない行はロック対象にしない。 */
    @Select("SELECT ct.* FROM t_contract ct INNER JOIN m_customer c ON c.id = ct.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ct.id = #{id} AND ct.tenant_id IS NOT NULL AND ct.tenant_id = #{tenantId} "
            + "AND ct.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_CT + " FOR UPDATE")
    Contract selectByIdForUpdateForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                                          @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 契約選択肢も契約・顧客のownershipを同時に検証する。 */
    @Select("""
        <script>
        SELECT c.id, CONCAT(COALESCE(c.contract_no, 'No Number'), ' - ', c.status) AS name
        FROM t_contract c INNER JOIN m_customer mc ON mc.id = c.customer_id
             AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        """ + CONTRACT_REFERENCE_OWNERSHIP_C + """
        <if test="allowedIds != null">
          <choose>
            <when test="allowedIds.size() > 0">AND c.id IN <foreach collection="allowedIds" item="id" open="(" separator="," close=")">#{id}</foreach></when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        ORDER BY c.id DESC
        </script>
        """)
    List<com.ses.dto.common.OptionDto> selectOptionsForTenant(
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
            @org.apache.ibatis.annotations.Param("allowedIds") Collection<Long> allowedIds);

    /** tenant・version CASによる通常更新。更新対象はサービス側で旧値を回填した全列。 */
    @org.apache.ibatis.annotations.Update("""
        UPDATE t_contract
           SET contract_no = #{contract.contractNo}, proposal_id = #{contract.proposalId},
               engineer_id = #{contract.engineerId}, project_id = #{contract.projectId},
               position_id = #{contract.positionId}, customer_id = #{contract.customerId},
               contract_type = #{contract.contractType}, start_date = #{contract.startDate},
               contract_date = #{contract.contractDate}, job_description = #{contract.jobDescription},
               work_location = #{contract.workLocation}, inspection_due_date = #{contract.inspectionDueDate},
               payment_due_date = #{contract.paymentDueDate}, payment_method = #{contract.paymentMethod},
               end_date = #{contract.endDate}, selling_price = #{contract.sellingPrice},
               cost_price = #{contract.costPrice}, cost_center_id = #{contract.costCenterId},
               settlement_hours_min = #{contract.settlementHoursMin}, settlement_hours_max = #{contract.settlementHoursMax},
               fraction_rule = #{contract.fractionRule}, auto_renew = #{contract.autoRenew},
               remarks = #{contract.remarks}, direct_command_flag = #{contract.directCommandFlag},
               sales_user_id = #{contract.salesUserId}, commission_base_type = #{contract.commissionBaseType},
               commission_rate = #{contract.commissionRate}, renewed_from_contract_id = #{contract.renewedFromContractId},
               quotation_id = #{contract.quotationId}, order_line_id = #{contract.orderLineId},
               acceptance_required = #{contract.acceptanceRequired},
               acceptance_exemption_reason = #{contract.acceptanceExemptionReason},
               renewal_decision = #{contract.renewalDecision}, updated_at = CURRENT_TIMESTAMP,
               version = version + 1
         WHERE id = #{contract.id} AND tenant_id IS NOT NULL AND tenant_id = #{tenantId}
           AND version = #{expectedVersion} AND deleted_flag = 0
            AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = customer_id
                        AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
            AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = engineer_id
                        AND e.tenant_id IS NOT NULL AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)
            AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = project_id
                        AND p.customer_id = customer_id AND p.deleted_flag = 0)
            AND (sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su
                        WHERE su.id = sales_user_id AND su.tenant_id IS NOT NULL
                          AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
        """)
    int updateByIdForTenant(@org.apache.ibatis.annotations.Param("contract") Contract contract,
                            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                            @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion);

    /** tenant・version CASによる論理削除。 */
    @org.apache.ibatis.annotations.Update("UPDATE t_contract SET deleted_flag = 1, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND tenant_id IS NOT NULL "
            + "AND tenant_id = #{tenantId} AND version = #{expectedVersion} AND deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)"
            + CONTRACT_REFERENCE_OWNERSHIP_UNQUALIFIED)
    int deleteByIdForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                            @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                            @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion);

    /** 状態遷移のtenant・version CAS。 */
    @org.apache.ibatis.annotations.Update("UPDATE t_contract SET status = #{status}, end_date = #{endDate}, "
            + "updated_at = CURRENT_TIMESTAMP, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id IS NOT NULL AND tenant_id = #{tenantId} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)"
            + CONTRACT_REFERENCE_OWNERSHIP_UNQUALIFIED)
    int updateStatusForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                              @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                              @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion,
                              @org.apache.ibatis.annotations.Param("status") String status,
                              @org.apache.ibatis.annotations.Param("endDate") LocalDate endDate);

    /** 更新判断のtenant・version CAS。 */
    @org.apache.ibatis.annotations.Update("UPDATE t_contract SET renewal_decision = #{decision}, "
            + "updated_at = CURRENT_TIMESTAMP, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id IS NOT NULL AND tenant_id = #{tenantId} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)"
            + CONTRACT_REFERENCE_OWNERSHIP_UNQUALIFIED)
    int updateRenewalDecisionForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                                       @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                                       @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion,
                                       @org.apache.ibatis.annotations.Param("decision") String decision);

    /** 単価同期/改定のtenant・version CAS。 */
    @org.apache.ibatis.annotations.Update("UPDATE t_contract SET selling_price = #{sellingPrice}, cost_price = #{costPrice}, "
            + "updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = #{id} "
            + "AND tenant_id IS NOT NULL AND tenant_id = #{tenantId} AND version = #{expectedVersion} "
            + "AND deleted_flag = 0 AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)"
            + CONTRACT_REFERENCE_OWNERSHIP_UNQUALIFIED)
    int updatePriceOnlyForTenant(@org.apache.ibatis.annotations.Param("id") Long id,
                                 @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                                 @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion,
                                 @org.apache.ibatis.annotations.Param("sellingPrice") java.math.BigDecimal sellingPrice,
                                 @org.apache.ibatis.annotations.Param("costPrice") java.math.BigDecimal costPrice);

    /** 契約の顧客・案件・要員参照を同一tenantで検証する。 */
    @Select("SELECT COUNT(*) FROM m_customer mc JOIN t_project p ON p.customer_id = mc.id "
            + "AND p.id = #{projectId} AND p.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = #{engineerId} AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0 "
            + "WHERE mc.id = #{customerId} AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0")
    long countOwnedReferencesForTenant(@org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                        @org.apache.ibatis.annotations.Param("projectId") Long projectId,
                                        @org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                        @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.engineer_id = #{engineerId} "
            + "AND c.status = '稼動中' AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0" + CONTRACT_REFERENCE_OWNERSHIP_C)
    long countActiveByEngineerForTenant(@org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                        @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 自動更新候補を契約・顧客ownershipで限定する。 */
    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND c.auto_renew = 1 "
            + "AND c.status = '稼動中' AND c.end_date IS NOT NULL AND c.end_date >= #{today} "
            + "AND c.end_date <= #{horizon}" + CONTRACT_REFERENCE_OWNERSHIP_C + " ORDER BY c.id")
    List<Contract> selectAutoRenewCandidatesForTenant(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                                                       @org.apache.ibatis.annotations.Param("today") LocalDate today,
                                                       @org.apache.ibatis.annotations.Param("horizon") LocalDate horizon);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.proposal_id = #{sourceId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_C + " LIMIT 1")
    Contract selectByProposalForTenant(@org.apache.ibatis.annotations.Param("sourceId") Long sourceId,
                                       @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.quotation_id = #{sourceId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_C + " LIMIT 1")
    Contract selectByQuotationForTenant(@org.apache.ibatis.annotations.Param("sourceId") Long sourceId,
                                        @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.order_line_id = #{sourceId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_C + " LIMIT 1")
    Contract selectByOrderLineForTenant(@org.apache.ibatis.annotations.Param("sourceId") Long sourceId,
                                        @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND c.status = '稼動中' "
            + "AND c.end_date BETWEEN #{today} AND #{horizon}"
            + CONTRACT_REFERENCE_OWNERSHIP_C + " ORDER BY c.id")
    List<Contract> selectEndingForTenant(@org.apache.ibatis.annotations.Param("today") LocalDate today,
                                          @org.apache.ibatis.annotations.Param("horizon") LocalDate horizon,
                                          @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT DISTINCT c.renewed_from_contract_id FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND c.renewed_from_contract_id IS NOT NULL"
            + CONTRACT_REFERENCE_OWNERSHIP_C)
    List<Long> selectRenewedOriginalIdsForTenant(@org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.engineer_id = #{engineerId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + CONTRACT_REFERENCE_OWNERSHIP_C + " ORDER BY c.end_date DESC LIMIT 1")
    Contract selectLatestByEngineerForTenant(@org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                             @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT DISTINCT c.sales_user_id FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.customer_id = #{customerId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "AND c.sales_user_id IS NOT NULL" + CONTRACT_REFERENCE_OWNERSHIP_C)
    List<Long> selectSalesUserIdsByCustomerForTenant(@org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                                      @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** SLA通知の顧客担当営業候補を、契約・顧客の両方の帰属で限定する。 */
    @Select("SELECT ct.* FROM t_contract ct INNER JOIN m_customer c ON c.id = ct.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ct.customer_id = #{customerId} AND ct.tenant_id IS NOT NULL AND ct.tenant_id = #{tenantId} "
            + "AND ct.status = '稼動中' AND ct.sales_user_id IS NOT NULL "
            + "AND ct.deleted_flag = 0" + CONTRACT_REFERENCE_OWNERSHIP_CT + " ORDER BY ct.id DESC")
    List<Contract> selectActiveByCustomerAndTenant(
            @org.apache.ibatis.annotations.Param("customerId") Long customerId,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_contract ct INNER JOIN m_customer c ON c.id = ct.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ct.customer_id = #{customerId} AND ct.engineer_id = #{engineerId} "
            + "AND ct.tenant_id IS NOT NULL AND ct.tenant_id = #{tenantId} AND ct.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_CT)
    long countByCustomerAndEngineerForTenant(@org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                             @org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                             @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.* FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "WHERE c.customer_id = #{customerId} AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND mc.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 AND mc.deleted_flag = 0 AND c.cost_center_id IS NOT NULL "
            + CONTRACT_REFERENCE_OWNERSHIP_C + " ORDER BY c.id DESC LIMIT 10")
    List<Contract> selectByCustomerAndTenant(@org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                              @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "WHERE c.customer_id = #{customerId} AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND mc.tenant_id = #{tenantId} "
            + "AND c.status = #{status} AND c.deleted_flag = 0 AND mc.deleted_flag = 0"
            + CONTRACT_REFERENCE_OWNERSHIP_C)
    long countByCustomerAndTenant(@org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                  @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                                  @org.apache.ibatis.annotations.Param("status") String status);

    /**
     * 組織スコープに入る契約ID。契約の帰属は要員の所属組織を基準にする。
     *
     * <p>asOf解決はplatform-invariants §1.1の「履歴行の存在で分岐」に従う（R09-P1-04）:
     * <ol>
     *   <li>要員会計履歴（V62）の対象日時点の版が存在すれば履歴値の組織を正とする
     *       （履歴行が明示NULL/UNKNOWNの場合は現在値・account-linkへフォールバックせず非該当）。</li>
     *   <li>履歴が無い場合は現在の {@code t_engineer.organization_id} を正とし、
     *       未設定時のみアカウント連携ユーザーの対象日時点の主所属へフォールバックする
     *       （{@code EngineerAccountLinkMapper} と同じ順序）。</li>
     * </ol>
     */
    @Select("""
        <script>
        SELECT DISTINCT c.id
        FROM t_contract c
        JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        LEFT JOIN t_engineer_accounting_history h ON h.engineer_id = e.id
             AND h.deleted_flag = 0
             AND h.valid_from &lt;= #{asOf}
             AND (h.valid_to IS NULL OR h.valid_to &gt;= #{asOf})
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
             AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
             AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
               AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{asOf}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND c.project_id IS NOT NULL
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
                      AND p.customer_id = c.customer_id AND p.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND (
            <if test="organizationIds != null and organizationIds.size() > 0">
              CASE WHEN h.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                   ELSE h.organization_id END
              IN <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="directUserIds != null and directUserIds.size() > 0">
              <if test="organizationIds != null and organizationIds.size() > 0">OR</if>
              l.sys_user_id IN <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="(organizationIds == null or organizationIds.size() == 0) and (directUserIds == null or directUserIds.size() == 0)">1 = 0</if>
          )
        </script>
        """)
    List<Long> selectContractIdsByOrganizationScope(
            @org.apache.ibatis.annotations.Param("organizationIds") List<Long> organizationIds,
            @org.apache.ibatis.annotations.Param("directUserIds") List<Long> directUserIds,
            @org.apache.ibatis.annotations.Param("asOf") LocalDate asOf,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT DISTINCT COALESCE(e.organization_id, uo.organization_id) AS organization_id
        FROM t_contract c
        JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
             AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
             AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
               AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0 AND uo.valid_to IS NULL
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND c.project_id IS NOT NULL
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
                      AND p.customer_id = c.customer_id AND p.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND COALESCE(e.organization_id, uo.organization_id) IS NOT NULL
          AND c.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<Long> selectOrganizationIdsByContractIds(@org.apache.ibatis.annotations.Param("ids") List<Long> ids,
                                                  @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("<script>SELECT DISTINCT c.customer_id FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND c.customer_id IS NOT NULL AND c.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + CONTRACT_REFERENCE_OWNERSHIP_C + "</script>")
    List<Long> selectCustomerIdsByContractIds(@org.apache.ibatis.annotations.Param("ids") List<Long> ids,
                                              @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("<script>SELECT DISTINCT c.project_id FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND c.project_id IS NOT NULL AND c.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + CONTRACT_REFERENCE_OWNERSHIP_C + "</script>")
    List<Long> selectProjectIdsByContractIds(@org.apache.ibatis.annotations.Param("ids") List<Long> ids,
                                             @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 案件に紐づく要員所属組織。組織通知の宛先解決に使用する。 */
    @Select("""
        <script>
        SELECT DISTINCT COALESCE(e.organization_id, uo.organization_id)
        FROM t_contract c
        JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
             AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
             AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
               AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{asOf}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
        WHERE c.deleted_flag = 0 AND c.project_id = #{projectId}
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
                      AND p.customer_id = c.customer_id AND p.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND COALESCE(e.organization_id, uo.organization_id) IS NOT NULL
        </script>
        """)
    List<Long> selectOrganizationIdsByProjectId(@org.apache.ibatis.annotations.Param("projectId") Long projectId,
                                                 @org.apache.ibatis.annotations.Param("asOf") LocalDate asOf,
                                                 @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 管理会計用。契約を所属組織の有効期間・primary所属でSQL絞り込みする。 */
    @Select("""
        <script>
        SELECT c.id, c.engineer_id AS engineerId, c.customer_id AS customerId, c.project_id AS projectId,
               c.sales_user_id AS salesUserId, c.cost_center_id AS costCenterId,
               c.start_date AS startDate, c.end_date AS endDate,
               c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
               COALESCE(e.organization_id, uo.organization_id) AS organizationId
        FROM t_contract c
        LEFT JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = c.engineer_id
             AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
             AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
               AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{monthStart}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{monthStart})
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_engineer e2 WHERE e2.id = c.engineer_id
                      AND e2.tenant_id IS NOT NULL AND e2.tenant_id = #{tenantId} AND e2.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
                      AND p.customer_id = c.customer_id AND p.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND c.status != '準備中'
          AND c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= #{monthStart})
          <if test="fullAccess == false">
            <choose>
              <when test="(allowedIds != null and allowedIds.size() > 0) or (directUserIds != null and directUserIds.size() > 0)">
                AND (
                <if test="allowedIds != null and allowedIds.size() > 0">
                  COALESCE(e.organization_id, uo.organization_id) IN
                  <foreach collection="allowedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </if>
                <if test="directUserIds != null and directUserIds.size() > 0">
                  <if test="allowedIds != null and allowedIds.size() > 0">OR</if>
                  l.sys_user_id IN
                  <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </if>
                )
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        ORDER BY c.id
        </script>
        """)
    List<ManagementAccountingContractRow> selectAccountingContracts(
            @org.apache.ibatis.annotations.Param("monthStart") LocalDate monthStart,
            @org.apache.ibatis.annotations.Param("monthEnd") LocalDate monthEnd,
            @org.apache.ibatis.annotations.Param("fullAccess") boolean fullAccess,
            @org.apache.ibatis.annotations.Param("allowedIds") List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("directUserIds") List<Long> directUserIds,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT c.id, c.engineer_id AS engineerId, c.customer_id AS customerId, c.project_id AS projectId,
               c.sales_user_id AS salesUserId, c.cost_center_id AS costCenterId,
               c.start_date AS startDate, c.end_date AS endDate,
               c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
               COALESCE(e.organization_id, uo.organization_id) AS organizationId
        FROM t_contract c
        LEFT JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = c.engineer_id
             AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
             AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
               AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.tenant_id IS NOT NULL AND uo.tenant_id = #{tenantId}
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{monthStart}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{monthStart})
        LEFT JOIN m_organization_unit ou ON ou.id = COALESCE(e.organization_id, uo.organization_id) AND ou.deleted_flag = 0
        WHERE c.deleted_flag = 0 AND c.status != '準備中'
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
                      AND p.customer_id = c.customer_id AND p.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_engineer e2 WHERE e2.id = c.engineer_id
                      AND e2.tenant_id IS NOT NULL AND e2.tenant_id = #{tenantId} AND e2.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= #{monthStart})
          <if test="customerId != null">AND c.customer_id = #{customerId}</if>
          <if test="projectId != null">AND c.project_id = #{projectId}</if>
          <if test="salesUserId != null">AND c.sales_user_id = #{salesUserId}</if>
          <if test="allowedContractIds != null">
            <choose>
              <when test="allowedContractIds.size() > 0">
                AND c.id IN <foreach collection="allowedContractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="costCenterId != null">AND c.cost_center_id = #{costCenterId}</if>
          <if test="legalEntityId != null">AND ou.legal_entity_id = #{legalEntityId}</if>
          <if test="legalEntityId != null">
            AND c.legal_entity_id = #{legalEntityId}
            AND EXISTS (SELECT 1 FROM t_engineer scoped_engineer
                        WHERE scoped_engineer.id = c.engineer_id
                          AND scoped_engineer.deleted_flag = 0
                          AND scoped_engineer.legal_entity_id = #{legalEntityId})
            AND EXISTS (SELECT 1 FROM t_project scoped_project
                        WHERE scoped_project.id = c.project_id
                          AND scoped_project.deleted_flag = 0
                          AND scoped_project.legal_entity_id = #{legalEntityId})
            AND EXISTS (SELECT 1 FROM m_customer scoped_customer
                        WHERE scoped_customer.id = c.customer_id
                          AND scoped_customer.deleted_flag = 0
                          AND scoped_customer.legal_entity_id = #{legalEntityId})
          </if>
          <if test="organizationId != null">AND COALESCE(e.organization_id, uo.organization_id) = #{organizationId}</if>
          <if test="fullAccess == false">
            <choose>
              <when test="(allowedIds != null and allowedIds.size() > 0) or (directUserIds != null and directUserIds.size() > 0)">
                AND (
                <if test="allowedIds != null and allowedIds.size() > 0">COALESCE(e.organization_id, uo.organization_id) IN <foreach collection="allowedIds" item="id" open="(" separator="," close=")">#{id}</foreach></if>
                <if test="directUserIds != null and directUserIds.size() > 0">
                  <if test="allowedIds != null and allowedIds.size() > 0">OR</if>
                  l.sys_user_id IN <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </if>
                )
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        ORDER BY c.id
        </script>
        """)
    List<ManagementAccountingContractRow> selectAccountingContractsFiltered(
            @org.apache.ibatis.annotations.Param("monthStart") LocalDate monthStart,
            @org.apache.ibatis.annotations.Param("monthEnd") LocalDate monthEnd,
            @org.apache.ibatis.annotations.Param("fullAccess") boolean fullAccess,
            @org.apache.ibatis.annotations.Param("allowedIds") List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("directUserIds") List<Long> directUserIds,
            @org.apache.ibatis.annotations.Param("allowedContractIds") List<Long> allowedContractIds,
            @org.apache.ibatis.annotations.Param("legalEntityId") Long legalEntityId,
            @org.apache.ibatis.annotations.Param("organizationId") Long organizationId,
            @org.apache.ibatis.annotations.Param("costCenterId") Long costCenterId,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId,
            @org.apache.ibatis.annotations.Param("projectId") Long projectId,
            @org.apache.ibatis.annotations.Param("salesUserId") Long salesUserId,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT c.engineer_id, c.start_date, c.end_date FROM t_contract c " +
            "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 " +
            "WHERE c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 " +
            "AND c.status IN ('稼動中','終了') AND c.engineer_id IS NOT NULL AND c.start_date IS NOT NULL"
            + CONTRACT_REFERENCE_OWNERSHIP_C)
    List<ContractDateRangeDto> selectActiveDateRanges(@org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT MAX(c.contract_no) FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.contract_no LIKE CONCAT(#{prefix}, '%')"
            + CONTRACT_REFERENCE_OWNERSHIP_C)
    String selectMaxContractNoIncludingDeleted(@org.apache.ibatis.annotations.Param("prefix") String prefix,
                                                @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_contract c JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 WHERE c.renewed_from_contract_id = #{originalId} "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}"
            + CONTRACT_REFERENCE_OWNERSHIP_C)
    int countRenewedDraftsIncludingDeleted(@org.apache.ibatis.annotations.Param("originalId") Long originalId,
                                            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 旧呼出しを残さず、更新系は selectByIdForUpdateForTenant を使用する。 */

    /**
     * 顧客ポータル用の契約一覧（SQL境界: customer_id。design §6.2）。
     * 原価・売上・営業情報はDTOへ出さない（field-inventory §3.1）。
     * esignStatusは最新の契約書cloudsign_status（業務状態への変換はJava側）。
     */
    @Select("""
        <script>
        SELECT
            c.id AS id,
            c.contract_no AS contractNo,
            c.contract_type AS contractType,
            c.status AS status,
            c.start_date AS startDate,
            c.end_date AS endDate,
            c.contract_date AS contractDate,
            c.job_description AS jobDescription,
            c.work_location AS workLocation,
            c.inspection_due_date AS inspectionDueDate,
            c.payment_due_date AS paymentDueDate,
            c.payment_method AS paymentMethod,
            c.settlement_hours_min AS settlementHoursMin,
            c.settlement_hours_max AS settlementHoursMax,
            c.acceptance_required AS acceptanceRequired,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            (SELECT cd.cloudsign_status FROM t_contract_document cd
              WHERE cd.contract_id = c.id ORDER BY cd.id DESC LIMIT 1) AS esignStatus,
            EXISTS (
                SELECT 1 FROM t_document_link dl
                INNER JOIN t_document_version dv ON dv.document_id = dl.document_id
                    AND dv.scan_status = 'CLEAN' AND dv.deleted_flag = 0
                WHERE dl.tenant_id IS NOT NULL AND dl.tenant_id = #{tenantId}
                  AND dv.tenant_id IS NOT NULL AND dv.tenant_id = #{tenantId}
                  AND dl.target_type = 'CONTRACT' AND dl.target_id = c.id AND dl.deleted_flag = 0
            ) AS contractDocumentAvailable
        FROM t_contract c
        INNER JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON p.id = c.project_id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
                AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          AND c.customer_id = #{customerId}
          <if test="status != null and status != ''">AND c.status = #{status}</if>
        ORDER BY c.id DESC
        </script>
        """)
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.portal.PortalContractDto> selectPortalPageDto(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.portal.PortalContractDto> page,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId,
            @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 顧客ポータル用の契約詳細（SQL境界。不一致は0件→404秘匿）。 */
    @Select("""
        SELECT
            c.id AS id,
            c.contract_no AS contractNo,
            c.contract_type AS contractType,
            c.status AS status,
            c.start_date AS startDate,
            c.end_date AS endDate,
            c.contract_date AS contractDate,
            c.job_description AS jobDescription,
            c.work_location AS workLocation,
            c.inspection_due_date AS inspectionDueDate,
            c.payment_due_date AS paymentDueDate,
            c.payment_method AS paymentMethod,
            c.settlement_hours_min AS settlementHoursMin,
            c.settlement_hours_max AS settlementHoursMax,
            c.acceptance_required AS acceptanceRequired,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            (SELECT cd.cloudsign_status FROM t_contract_document cd
              WHERE cd.contract_id = c.id ORDER BY cd.id DESC LIMIT 1) AS esignStatus,
            EXISTS (
                SELECT 1 FROM t_document_link dl
                INNER JOIN t_document_version dv ON dv.document_id = dl.document_id
                    AND dv.scan_status = 'CLEAN' AND dv.deleted_flag = 0
                WHERE dl.tenant_id IS NOT NULL AND dl.tenant_id = #{tenantId}
                  AND dv.tenant_id IS NOT NULL AND dv.tenant_id = #{tenantId}
                  AND dl.target_type = 'CONTRACT' AND dl.target_id = c.id AND dl.deleted_flag = 0
            ) AS contractDocumentAvailable
        FROM t_contract c
        INNER JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON p.id = c.project_id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
                AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        WHERE c.id = #{id} AND c.deleted_flag = 0 AND c.customer_id = #{customerId}
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND EXISTS (SELECT 1 FROM m_customer mc WHERE mc.id = c.customer_id
                      AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0)
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
                      AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
        """)
    com.ses.dto.portal.PortalContractDto selectPortalDetailDto(@org.apache.ibatis.annotations.Param("id") Long id,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT c.id, c.contract_no AS contractNo, c.engineer_id AS engineerId,
               c.customer_id AS customerId, c.project_id AS projectId,
               c.contract_type AS contractType, c.start_date AS startDate, c.end_date AS endDate,
               c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
               c.sales_user_id AS salesUserId, su.real_name AS salesUserName,
               e.full_name AS engineerName, cu.company_name AS customerName, p.project_name AS projectName
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer cu ON c.customer_id = cu.id AND cu.tenant_id IS NOT NULL
             AND cu.tenant_id = #{tenantId} AND cu.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
                AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.tenant_id IS NOT NULL
             AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND cu.tenant_id IS NOT NULL AND cu.tenant_id = #{tenantId}
          AND (c.sales_user_id IS NULL OR su.id IS NOT NULL)
          <if test="status != null and status != ''">AND c.status = #{status}</if>
          <if test="customerId != null">AND c.customer_id = #{customerId}</if>
          <if test="engineerId != null">AND c.engineer_id = #{engineerId}</if>
          <if test="projectId != null">AND c.project_id = #{projectId}</if>
          <if test="salesUserId != null">AND c.sales_user_id = #{salesUserId}</if>
          <if test="salesUnassigned != null and salesUnassigned">AND c.sales_user_id IS NULL</if>
          <if test="contractNo != null and contractNo != ''">AND c.contract_no LIKE CONCAT('%', #{contractNo}, '%')</if>
          <if test="endDateFrom != null">AND c.end_date &gt;= #{endDateFrom}</if>
          <if test="endDateTo != null">AND c.end_date &lt;= #{endDateTo}</if>
          <!-- ガント期間フィルタ (R7-08): 期間と重なる契約を取得 -->
          <if test="periodFrom != null">AND (c.end_date IS NULL OR c.end_date &gt;= #{periodFrom})</if>
          <if test="periodTo != null">AND c.start_date &lt;= #{periodTo}</if>
          <!-- データスコープ: allowedIds!=null なら担当契約のみに絞る(件数・ページングもスコープ後の値) -->
          <if test="allowedIds != null"><choose><when test="allowedIds.size() > 0">AND c.id IN <foreach collection="allowedIds" item="cid" open="(" separator="," close=")">#{cid}</foreach></when><otherwise>AND 1 = 0</otherwise></choose></if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<ContractListDto> selectPageWithNames(Page<ContractListDto> page, @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId, @org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
            @org.apache.ibatis.annotations.Param("projectId") Long projectId, @org.apache.ibatis.annotations.Param("contractNo") String contractNo,
            @org.apache.ibatis.annotations.Param("endDateFrom") LocalDate endDateFrom, @org.apache.ibatis.annotations.Param("endDateTo") LocalDate endDateTo,
            @org.apache.ibatis.annotations.Param("salesUserId") Long salesUserId,
            @org.apache.ibatis.annotations.Param("salesUnassigned") Boolean salesUnassigned,
            @org.apache.ibatis.annotations.Param("periodFrom") LocalDate periodFrom,
            @org.apache.ibatis.annotations.Param("periodTo") LocalDate periodTo,
            @org.apache.ibatis.annotations.Param("allowedIds") java.util.List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /**
     * 契約更新カレンダー(FR-06)候補の取得。指定ステータス・終了日範囲の契約を要員/顧客/営業名付きで返す。
     * 1000件上限は呼び出し側で担保する(A7-22: 黙って欠けないよう limit+1 で取得し切り詰めを検知)。
     */
    @Select("""
        <script>
        SELECT c.id AS contractId, c.contract_no AS contractNo, c.engineer_id AS engineerId,
               c.customer_id AS customerId, c.end_date AS endDate, c.status,
               c.renewal_decision AS renewalDecision,
               c.sales_user_id AS salesUserId, su.real_name AS salesUserName,
               e.full_name AS engineerName, cu.company_name AS customerName
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
             AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer cu ON c.customer_id = cu.id AND cu.tenant_id IS NOT NULL
             AND cu.tenant_id = #{tenantId} AND cu.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
                AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.tenant_id IS NOT NULL
             AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0
        WHERE c.deleted_flag = 0
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND cu.tenant_id IS NOT NULL AND cu.tenant_id = #{tenantId}
          AND (c.sales_user_id IS NULL OR su.id IS NOT NULL)
          AND c.status = #{status}
          AND c.end_date IS NOT NULL
          AND c.end_date &gt;= #{endDateFrom}
          AND c.end_date &lt;= #{endDateTo}
          <if test="allowedIds != null"><choose><when test="allowedIds.size() > 0">AND c.id IN <foreach collection="allowedIds" item="cid" open="(" separator="," close=")">#{cid}</foreach></when><otherwise>AND 1 = 0</otherwise></choose></if>
        ORDER BY c.end_date ASC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """)
    java.util.List<RenewalCalendarItemDto> selectRenewalCalendarCandidates(
            @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("endDateFrom") LocalDate endDateFrom,
            @org.apache.ibatis.annotations.Param("endDateTo") LocalDate endDateTo,
            @org.apache.ibatis.annotations.Param("allowedIds") java.util.List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("limit") Integer limit,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /**
     * 指定した元契約群を親とする更新ドラフト(renewed_from_contract_id)の状態のみを返す。
     * 状態導出(DRAFT有/確定判定)専用の軽量クエリ。
     */
    @Select("""
        <script>
        SELECT renewed_from_contract_id AS renewedFromContractId, status
        FROM t_contract c
        JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE c.deleted_flag = 0 AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND renewed_from_contract_id IN
        <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
          """ + CONTRACT_REFERENCE_OWNERSHIP_C + """
        </script>
        """)
    java.util.List<ContractDraftStatusDto> selectDraftStatusesByOriginalIds(
            @org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
            @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /*
     * 旧テスト/内部拡張向けの互換委譲。SQLを持たず、必ず明示的なtenant-awareメソッドへ委譲する。
     * 新規コードでは上記のtenant引数付きメソッドだけを使用すること。
     */
    @Deprecated
    default Contract selectByIdForUpdate(Long id) {
        return selectByIdForUpdateForTenant(id,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default Page<ContractListDto> selectPageWithNames(Page<ContractListDto> page, String status,
            Long customerId, Long engineerId, Long projectId, String contractNo,
            LocalDate endDateFrom, LocalDate endDateTo, Long salesUserId, Boolean salesUnassigned,
            LocalDate periodFrom, LocalDate periodTo, java.util.List<Long> allowedIds) {
        return selectPageWithNames(page, status, customerId, engineerId, projectId, contractNo,
                endDateFrom, endDateTo, salesUserId, salesUnassigned, periodFrom, periodTo,
                allowedIds, com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<ContractDateRangeDto> selectActiveDateRanges() {
        return selectActiveDateRanges(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default String selectMaxContractNoIncludingDeleted(String prefix) {
        return selectMaxContractNoIncludingDeleted(prefix,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default int countRenewedDraftsIncludingDeleted(Long originalId) {
        return countRenewedDraftsIncludingDeleted(originalId,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<RenewalCalendarItemDto> selectRenewalCalendarCandidates(String status,
            LocalDate endDateFrom, LocalDate endDateTo, java.util.List<Long> allowedIds, Integer limit) {
        return selectRenewalCalendarCandidates(status, endDateFrom, endDateTo, allowedIds, limit,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<ContractDraftStatusDto> selectDraftStatusesByOriginalIds(java.util.List<Long> ids) {
        return selectDraftStatusesByOriginalIds(ids,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<ManagementAccountingContractRow> selectAccountingContracts(LocalDate monthStart,
            LocalDate monthEnd, boolean fullAccess, List<Long> allowedIds, List<Long> directUserIds) {
        return selectAccountingContracts(monthStart, monthEnd, fullAccess, allowedIds, directUserIds,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<ManagementAccountingContractRow> selectAccountingContractsFiltered(LocalDate monthStart,
            LocalDate monthEnd, boolean fullAccess, List<Long> allowedIds, List<Long> directUserIds,
            List<Long> allowedContractIds, Long legalEntityId, Long organizationId, Long costCenterId,
            Long customerId, Long projectId, Long salesUserId) {
        return selectAccountingContractsFiltered(monthStart, monthEnd, fullAccess, allowedIds, directUserIds,
                allowedContractIds, legalEntityId, organizationId, costCenterId, customerId, projectId,
                salesUserId, com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }

    @Deprecated
    default List<Long> selectOrganizationIdsByContractIds(List<Long> contractIds) {
        return selectOrganizationIdsByContractIds(contractIds,
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
    }
}
