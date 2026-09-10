package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkRecordMapper extends BaseMapper<WorkRecord> {
    /** 勤怠が参照する契約・要員・案件・営業のownershipを一つのSQL条件で固定する。 */
    String CONTRACT_OWNERSHIP = ""
            + " AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id"
            + " AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)"
            + " AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id"
            + " AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0"
            + " AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0"
            + " WHERE pu0.id = p0.created_by AND pu0.tenant_id IS NOT NULL"
            + " AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))"
            + " AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0"
            + " WHERE su0.id = c.sales_user_id AND su0.tenant_id IS NOT NULL"
            + " AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0)) ";
    /** 原価部門削除参照も契約・顧客ownershipを現在tenantへ固定する。 */
    @Select("SELECT COUNT(*) FROM t_work_record w "
            + "INNER JOIN t_contract c ON c.id = w.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "INNER JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.cost_center_id = #{costCenterId}" + CONTRACT_OWNERSHIP)
    long countByCostCenterIdForTenant(@Param("costCenterId") Long costCenterId,
                                      @Param("tenantId") String tenantId);

    /** 契約削除・単価改定の参照件数。契約と顧客ownershipを同一SQLで検証する。 */
    @Select("SELECT COUNT(*) FROM t_work_record w JOIN t_contract c ON c.id = w.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId}" + CONTRACT_OWNERSHIP)
    long countByContractIdForTenant(@Param("contractId") Long contractId,
                                    @Param("tenantId") String tenantId);

    /** 単価改定対象の未確定実績を契約ownershipで限定する。 */
    @Select("SELECT w.* FROM t_work_record w JOIN t_contract c ON c.id = w.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId} AND w.work_month >= #{workMonth} "
            + "AND w.status <> '確定'" + CONTRACT_OWNERSHIP)
    List<WorkRecord> selectUnconfirmedByContractIdForTenant(@Param("contractId") Long contractId,
                                                            @Param("workMonth") String workMonth,
                                                            @Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_work_record w JOIN t_contract c ON c.id = w.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId} AND w.status = '確定' "
            + "AND w.work_month >= #{workMonth}" + CONTRACT_OWNERSHIP)
    long countConfirmedByContractIdForTenant(@Param("contractId") Long contractId,
                                             @Param("workMonth") String workMonth,
                                             @Param("tenantId") String tenantId);

    /** 承認の組織解決対象も契約・顧客ownershipでtenantを固定する。 */
    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.id = #{id}" + CONTRACT_OWNERSHIP)
    WorkRecord selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** 月次snapshotの実績も契約の顧客ownershipでtenantを固定する。 */
    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.work_month = #{workMonth} AND w.status = '確定' "
            + CONTRACT_OWNERSHIP + " ORDER BY w.id")
    List<WorkRecord> selectConfirmedByWorkMonthForTenant(@Param("workMonth") String workMonth,
                                                         @Param("tenantId") String tenantId);

    @Select("SELECT w.work_month FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.id = #{id}" + CONTRACT_OWNERSHIP)
    String selectWorkMonthByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.id = #{id}" + CONTRACT_OWNERSHIP + " FOR UPDATE")
    WorkRecord selectByIdForUpdateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId} AND w.work_month = #{workMonth} "
            + CONTRACT_OWNERSHIP + " FOR UPDATE")
    WorkRecord selectByContractIdAndMonthForUpdateForTenant(@Param("contractId") Long contractId,
                                                             @Param("workMonth") String workMonth,
                                                             @Param("tenantId") String tenantId);

    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId} AND w.work_month = #{workMonth}"
            + CONTRACT_OWNERSHIP)
    WorkRecord selectByContractIdAndMonthForTenant(@Param("contractId") Long contractId,
                                                   @Param("workMonth") String workMonth,
                                                   @Param("tenantId") String tenantId);

    @Select("SELECT w.* FROM t_work_record w "
            + "JOIN t_contract c ON c.id = w.contract_id AND c.tenant_id IS NOT NULL "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL "
            + "AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.contract_id = #{contractId} "
            + CONTRACT_OWNERSHIP + " ORDER BY w.work_month, w.id")
    List<WorkRecord> selectByContractIdForTenant(@Param("contractId") Long contractId,
                                                 @Param("tenantId") String tenantId);

    default String selectWorkMonthById(@Param("id") Long id) {
        return selectWorkMonthByIdForTenant(id, AccountingTenantContextHolder.requireTenantContext());
    }

    default WorkRecord selectByIdForUpdate(Long id) {
        return selectByIdForUpdateForTenant(id, AccountingTenantContextHolder.requireTenantContext());
    }

    default WorkRecord selectByContractIdAndMonthForUpdate(Long contractId, String workMonth) {
        return selectByContractIdAndMonthForUpdateForTenant(contractId, workMonth,
                AccountingTenantContextHolder.requireTenantContext());
    }

    @Select("""
        SELECT
            c.id AS contractId,
            c.contract_no AS contractNo,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            c.selling_price AS sellingPrice,
            c.cost_price AS costPrice,
            c.settlement_hours_min AS settlementHoursMin,
            c.settlement_hours_max AS settlementHoursMax,
            c.fraction_rule AS fractionRule,
            e.employment_type AS employmentType,
            w.id AS workRecordId,
            w.work_month AS workMonth,
            w.actual_hours AS actualHours,
            w.billing_amount AS billingAmount,
            w.payment_amount AS paymentAmount,
            w.status AS status,
            w.remarks AS remarks,
            w.reject_comment AS rejectComment,
            w.version AS version
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
          AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
            AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
           AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
           AND c.deleted_flag = 0
           AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
             AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
         ORDER BY c.id DESC
    """)
    List<WorkRecordGridDto> selectMonthlyGrid(@Param("workMonth") String workMonth,
                                              @Param("monthEnd") String monthEnd,
                                              @Param("tenantId") String tenantId);

    /** 旧テスト/拡張向け互換。実体は必須tenantへ委譲し、default tenantへはフォールバックしない。 */
    default List<WorkRecordGridDto> selectMonthlyGrid(String workMonth, String monthEnd) {
        return selectMonthlyGrid(workMonth, monthEnd, AccountingTenantContextHolder.requireTenantContext());
    }

    /**
     * 月次グリッドの SQL 段階フィルタ＋ページング（全社アクセス）。
     * keyword / status を WHERE へ下し、Java 側の全件 subList を禁止する。
     */
    @Select("""
        <script>
        SELECT
            c.id AS contractId,
            c.contract_no AS contractNo,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            c.selling_price AS sellingPrice,
            c.cost_price AS costPrice,
            c.settlement_hours_min AS settlementHoursMin,
            c.settlement_hours_max AS settlementHoursMax,
            c.fraction_rule AS fractionRule,
            e.employment_type AS employmentType,
            w.id AS workRecordId,
            w.work_month AS workMonth,
            w.actual_hours AS actualHours,
            w.billing_amount AS billingAmount,
            w.payment_amount AS paymentAmount,
            w.status AS status,
            w.remarks AS remarks,
            w.reject_comment AS rejectComment,
            w.version AS version
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
          AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
            AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
           AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
           AND c.deleted_flag = 0
           AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
             AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
           <if test="keyword != null and keyword != ''">
            AND (
              LOWER(COALESCE(e.full_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              OR LOWER(COALESCE(p.project_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              OR LOWER(COALESCE(c.contract_no, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
            )
          </if>
          <if test="status != null and status != ''">
            <choose>
              <when test="status == '未入力'">AND w.status IS NULL</when>
              <otherwise>AND w.status = #{status}</otherwise>
            </choose>
          </if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<WorkRecordGridDto> selectMonthlyGridPage(
            Page<WorkRecordGridDto> page,
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("tenantId") String tenantId);

    default Page<WorkRecordGridDto> selectMonthlyGridPage(Page<WorkRecordGridDto> page,
                                                           String workMonth, String monthEnd,
                                                           String keyword, String status) {
        return selectMonthlyGridPage(page, workMonth, monthEnd, keyword, status,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 組織scope/DataScopeをJOINの存在条件として適用した勤怠グリッド。画面後filterは禁止。 */
    @Select("""
        <script>
        SELECT
            c.id AS contractId, c.contract_no AS contractNo, e.full_name AS engineerName,
            p.project_name AS projectName, c.selling_price AS sellingPrice, c.cost_price AS costPrice,
            c.settlement_hours_min AS settlementHoursMin, c.settlement_hours_max AS settlementHoursMax,
            c.fraction_rule AS fractionRule, e.employment_type AS employmentType,
            w.id AS workRecordId, w.work_month AS workMonth, w.actual_hours AS actualHours,
            w.billing_amount AS billingAmount, w.payment_amount AS paymentAmount,
            w.status AS status, w.remarks AS remarks, w.reject_comment AS rejectComment, w.version AS version
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
          AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
            AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
           AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
           AND c.deleted_flag = 0
           AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
             AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
           <if test="dataScopeContractIds != null">
            <choose><when test="dataScopeContractIds.size() > 0">AND c.id IN <foreach collection="dataScopeContractIds" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>AND 1 = 0</otherwise></choose>
          </if>
          <if test="fullAccess == false">
            AND (
              <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">
                (
                  CASE WHEN w.accounting_dimension_frozen = 1 THEN w.organization_id ELSE e.organization_id END
                    IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  OR (
                    (w.accounting_dimension_frozen IS NULL OR w.accounting_dimension_frozen &lt;&gt; 1)
                    AND e.organization_id IS NULL
                    AND EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                      WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                        AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                          AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                        AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                   WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                     AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                       AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                    AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                    AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                    AND uo.user_id IN <foreach collection="allowedDirectUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
              </if>
              <if test="(allowedOrganizationIds == null or allowedOrganizationIds.size() == 0) and (allowedDirectUserIds == null or allowedDirectUserIds.size() == 0)">
                1 = 0
              </if>
            )
          </if>
        ORDER BY c.id DESC
        </script>
        """)
    List<WorkRecordGridDto> selectMonthlyGridScoped(
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("asOf") java.time.LocalDate asOf,
            @Param("fullAccess") boolean fullAccess,
            @Param("allowedOrganizationIds") List<Long> allowedOrganizationIds,
            @Param("allowedDirectUserIds") List<Long> allowedDirectUserIds,
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds,
            @Param("tenantId") String tenantId);

    default List<WorkRecordGridDto> selectMonthlyGridScoped(String workMonth, String monthEnd,
                                                             java.time.LocalDate asOf, boolean fullAccess,
                                                             List<Long> allowedOrganizationIds,
                                                             List<Long> allowedDirectUserIds,
                                                             List<Long> dataScopeContractIds) {
        return selectMonthlyGridScoped(workMonth, monthEnd, asOf, fullAccess,
                allowedOrganizationIds, allowedDirectUserIds, dataScopeContractIds,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 組織scope/DataScope付き月次グリッドの SQL 段階フィルタ＋ページング。 */
    @Select("""
        <script>
        SELECT
            c.id AS contractId, c.contract_no AS contractNo, e.full_name AS engineerName,
            p.project_name AS projectName, c.selling_price AS sellingPrice, c.cost_price AS costPrice,
            c.settlement_hours_min AS settlementHoursMin, c.settlement_hours_max AS settlementHoursMax,
            c.fraction_rule AS fractionRule, e.employment_type AS employmentType,
            w.id AS workRecordId, w.work_month AS workMonth, w.actual_hours AS actualHours,
            w.billing_amount AS billingAmount, w.payment_amount AS paymentAmount,
            w.status AS status, w.remarks AS remarks, w.reject_comment AS rejectComment, w.version AS version
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
          AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
            AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
           AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
           AND c.deleted_flag = 0
           AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
             AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
           <if test="dataScopeContractIds != null">
            <choose><when test="dataScopeContractIds.size() > 0">AND c.id IN <foreach collection="dataScopeContractIds" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>AND 1 = 0</otherwise></choose>
          </if>
          <if test="fullAccess == false">
            AND (
              <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">
                (
                  CASE WHEN w.accounting_dimension_frozen = 1 THEN w.organization_id ELSE e.organization_id END
                    IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  OR (
                    (w.accounting_dimension_frozen IS NULL OR w.accounting_dimension_frozen &lt;&gt; 1)
                    AND e.organization_id IS NULL
                    AND EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                      WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                        AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                          AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                        AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                   WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                     AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                       AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                    AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                    AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                    AND uo.user_id IN <foreach collection="allowedDirectUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
              </if>
              <if test="(allowedOrganizationIds == null or allowedOrganizationIds.size() == 0) and (allowedDirectUserIds == null or allowedDirectUserIds.size() == 0)">
                1 = 0
              </if>
            )
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              LOWER(COALESCE(e.full_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              OR LOWER(COALESCE(p.project_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              OR LOWER(COALESCE(c.contract_no, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
            )
          </if>
          <if test="status != null and status != ''">
            <choose>
              <when test="status == '未入力'">AND w.status IS NULL</when>
              <otherwise>AND w.status = #{status}</otherwise>
            </choose>
          </if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<WorkRecordGridDto> selectMonthlyGridScopedPage(
            Page<WorkRecordGridDto> page,
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("asOf") java.time.LocalDate asOf,
            @Param("fullAccess") boolean fullAccess,
            @Param("allowedOrganizationIds") List<Long> allowedOrganizationIds,
            @Param("allowedDirectUserIds") List<Long> allowedDirectUserIds,
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("tenantId") String tenantId);

    default Page<WorkRecordGridDto> selectMonthlyGridScopedPage(Page<WorkRecordGridDto> page,
                                                                 String workMonth, String monthEnd,
                                                                 java.time.LocalDate asOf, boolean fullAccess,
                                                                 List<Long> allowedOrganizationIds,
                                                                 List<Long> allowedDirectUserIds,
                                                                 List<Long> dataScopeContractIds,
                                                                 String keyword, String status) {
        return selectMonthlyGridScopedPage(page, workMonth, monthEnd, asOf, fullAccess,
                allowedOrganizationIds, allowedDirectUserIds, dataScopeContractIds,
                keyword, status, AccountingTenantContextHolder.requireTenantContext());
    }

    @Select("""
        <script>
        SELECT w.* FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN t_engineer e ON e.id = c.engineer_id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE w.id = #{id}
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
            AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
              AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
            AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          <if test="dataScopeContractIds != null">
            <choose><when test="dataScopeContractIds.size() > 0">AND c.id IN <foreach collection="dataScopeContractIds" item="contractId" open="(" separator="," close=")">#{contractId}</foreach></when><otherwise>AND 1 = 0</otherwise></choose>
          </if>
          <if test="fullAccess == false">
            AND (
              <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">
                (
                  CASE WHEN w.accounting_dimension_frozen = 1 THEN w.organization_id ELSE e.organization_id END
                    IN <foreach collection="allowedOrganizationIds" item="orgId" open="(" separator="," close=")">#{orgId}</foreach>
                  OR (
                    (w.accounting_dimension_frozen IS NULL OR w.accounting_dimension_frozen &lt;&gt; 1)
                    AND e.organization_id IS NULL
                    AND EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                      WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                        AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                          AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                        AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="orgId" open="(" separator="," close=")">#{orgId}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                   WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                     AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                       AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                    AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                    AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                    AND uo.user_id IN <foreach collection="allowedDirectUserIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>)
              </if>
              <if test="(allowedOrganizationIds == null or allowedOrganizationIds.size() == 0) and (allowedDirectUserIds == null or allowedDirectUserIds.size() == 0)">1 = 0</if>
            )
          </if>
        </script>
        """)
    WorkRecord selectByIdScoped(
            @Param("id") Long id,
            @Param("asOf") java.time.LocalDate asOf,
            @Param("fullAccess") boolean fullAccess,
            @Param("allowedOrganizationIds") List<Long> allowedOrganizationIds,
            @Param("allowedDirectUserIds") List<Long> allowedDirectUserIds,
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds,
            @Param("tenantId") String tenantId);

    default WorkRecord selectByIdScoped(Long id, java.time.LocalDate asOf, boolean fullAccess,
                                        List<Long> allowedOrganizationIds, List<Long> allowedDirectUserIds,
                                        List<Long> dataScopeContractIds) {
        return selectByIdScoped(id, asOf, fullAccess, allowedOrganizationIds, allowedDirectUserIds,
                dataScopeContractIds, AccountingTenantContextHolder.requireTenantContext());
    }

    /**
     * 特定要員のみに絞った勤怠グリッド（要員ポータル用）。WHERE 句は selectMonthlyGrid に
     * engineer 条件を足しただけ（本人スコープの越権防止のため engineerId 必須）。
     */
    @Select("""
        SELECT
            c.id AS contractId,
            c.contract_no AS contractNo,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            c.selling_price AS sellingPrice,
            c.cost_price AS costPrice,
            c.settlement_hours_min AS settlementHoursMin,
            c.settlement_hours_max AS settlementHoursMax,
            c.fraction_rule AS fractionRule,
            e.employment_type AS employmentType,
            w.id AS workRecordId,
            w.work_month AS workMonth,
            w.actual_hours AS actualHours,
            w.billing_amount AS billingAmount,
            w.payment_amount AS paymentAmount,
            w.status AS status,
            w.remarks AS remarks,
            w.reject_comment AS rejectComment,
            w.version AS version
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN t_project p ON c.project_id = p.id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
          AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
            AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0))
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.engineer_id = #{engineerId}
          AND c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
            AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
        ORDER BY c.id DESC
    """)
    List<WorkRecordGridDto> selectMonthlyGridForEngineer(@Param("engineerId") Long engineerId,
                                                         @Param("workMonth") String workMonth,
                                                         @Param("monthEnd") String monthEnd,
                                                         @Param("tenantId") String tenantId);

    default List<WorkRecordGridDto> selectMonthlyGridForEngineer(Long engineerId, String workMonth,
                                                                  String monthEnd) {
        return selectMonthlyGridForEngineer(engineerId, workMonth, monthEnd,
                AccountingTenantContextHolder.requireTenantContext());
    }

    @Select("""
        SELECT e.employment_type
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE c.id = #{contractId}
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
            AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
              AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
            AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
    """)
    String selectEmploymentTypeByContractIdForTenant(@Param("contractId") Long contractId,
                                                     @Param("tenantId") String tenantId);

    default String selectEmploymentTypeByContractId(Long contractId) {
        return selectEmploymentTypeByContractIdForTenant(contractId,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /**
     * 承認滞留一覧（全社アクセス）。対象月の提出済のみを SQL で絞り、updated_at 昇順（滞留日数降順）でページングする。
     */
    @Select("""
        SELECT
            w.id AS workRecordId,
            c.id AS contractId,
            c.contract_no AS contractNo,
            e.full_name AS engineerName,
            w.updated_at AS updatedAt
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        INNER JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND w.status = '提出済'
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
            AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
              AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
            AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
        ORDER BY w.updated_at ASC, w.id ASC
        """)
    Page<com.ses.dto.workrecord.PendingApprovalItemDto> selectPendingApprovalPage(
            Page<com.ses.dto.workrecord.PendingApprovalItemDto> page,
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("tenantId") String tenantId);

    default Page<com.ses.dto.workrecord.PendingApprovalItemDto> selectPendingApprovalPage(
            Page<com.ses.dto.workrecord.PendingApprovalItemDto> page, String workMonth, String monthEnd) {
        return selectPendingApprovalPage(page, workMonth, monthEnd,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 承認滞留一覧（組織scope/DataScope付き）。提出済のみを SQL で絞る。 */
    @Select("""
        <script>
        SELECT
            w.id AS workRecordId,
            c.id AS contractId,
            c.contract_no AS contractNo,
            e.full_name AS engineerName,
            w.updated_at AS updatedAt
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        INNER JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND w.status = '提出済'
          AND EXISTS (SELECT 1 FROM t_project p WHERE p.id = c.project_id
            AND p.customer_id = c.customer_id AND p.deleted_flag = 0
            AND (p.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu WHERE pu.id = p.created_by
              AND pu.tenant_id IS NOT NULL AND pu.tenant_id = #{tenantId} AND pu.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su WHERE su.id = c.sales_user_id
            AND su.tenant_id IS NOT NULL AND su.tenant_id = #{tenantId} AND su.deleted_flag = 0))
          <if test="dataScopeContractIds != null">
            <choose><when test="dataScopeContractIds.size() > 0">AND c.id IN <foreach collection="dataScopeContractIds" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>AND 1 = 0</otherwise></choose>
          </if>
          <if test="fullAccess == false">
            AND (
              <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">
                (
                  CASE WHEN w.accounting_dimension_frozen = 1 THEN w.organization_id ELSE e.organization_id END
                    IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  OR (
                    (w.accounting_dimension_frozen IS NULL OR w.accounting_dimension_frozen &lt;&gt; 1)
                    AND e.organization_id IS NULL
                    AND EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                      WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                        AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                          AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                        AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                   WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                     AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                       AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                    AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                    AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                    AND uo.user_id IN <foreach collection="allowedDirectUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
              </if>
              <if test="(allowedOrganizationIds == null or allowedOrganizationIds.size() == 0) and (allowedDirectUserIds == null or allowedDirectUserIds.size() == 0)">
                1 = 0
              </if>
            )
          </if>
        ORDER BY w.updated_at ASC, w.id ASC
        </script>
        """)
    Page<com.ses.dto.workrecord.PendingApprovalItemDto> selectPendingApprovalScopedPage(
            Page<com.ses.dto.workrecord.PendingApprovalItemDto> page,
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("asOf") java.time.LocalDate asOf,
            @Param("fullAccess") boolean fullAccess,
            @Param("allowedOrganizationIds") List<Long> allowedOrganizationIds,
            @Param("allowedDirectUserIds") List<Long> allowedDirectUserIds,
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds,
            @Param("tenantId") String tenantId);

    default Page<com.ses.dto.workrecord.PendingApprovalItemDto> selectPendingApprovalScopedPage(
            Page<com.ses.dto.workrecord.PendingApprovalItemDto> page, String workMonth, String monthEnd,
            java.time.LocalDate asOf, boolean fullAccess, List<Long> allowedOrganizationIds,
            List<Long> allowedDirectUserIds, List<Long> dataScopeContractIds) {
        return selectPendingApprovalScopedPage(page, workMonth, monthEnd, asOf, fullAccess,
                allowedOrganizationIds, allowedDirectUserIds, dataScopeContractIds,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 提出済の最古 updated_at（最長滞留日数の算出用・全社）。 */
    @Select("""
        SELECT MIN(w.updated_at)
        FROM t_contract c
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        INNER JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND w.status = '提出済'
          AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
            AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
          AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
            AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
            AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
              AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
            AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
        """)
    java.time.LocalDateTime selectOldestPendingUpdatedAt(
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("tenantId") String tenantId);

    default java.time.LocalDateTime selectOldestPendingUpdatedAt(String workMonth, String monthEnd) {
        return selectOldestPendingUpdatedAt(workMonth, monthEnd,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 提出済の最古 updated_at（最長滞留日数の算出用・scope付き）。 */
    @Select("""
        <script>
        SELECT MIN(w.updated_at)
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id AND e.tenant_id IS NOT NULL
          AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        INNER JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
          AND c.deleted_flag = 0
          AND w.status = '提出済'
          AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
            AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
            AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
              AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
          AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
            AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
          <if test="dataScopeContractIds != null">
            <choose><when test="dataScopeContractIds.size() > 0">AND c.id IN <foreach collection="dataScopeContractIds" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>AND 1 = 0</otherwise></choose>
          </if>
          <if test="fullAccess == false">
            AND (
              <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">
                (
                  CASE WHEN w.accounting_dimension_frozen = 1 THEN w.organization_id ELSE e.organization_id END
                    IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  OR (
                    (w.accounting_dimension_frozen IS NULL OR w.accounting_dimension_frozen &lt;&gt; 1)
                    AND e.organization_id IS NULL
                    AND EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                      WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                        AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                          AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                        AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                   WHERE l.engineer_id = c.engineer_id AND l.tenant_id IS NOT NULL AND l.tenant_id = #{tenantId} AND l.deleted_flag = 0
                     AND EXISTS (SELECT 1 FROM sys_user lu WHERE lu.id = l.sys_user_id
                       AND lu.tenant_id IS NOT NULL AND lu.tenant_id = #{tenantId} AND lu.deleted_flag = 0)
                    AND uo.tenant_id = #{tenantId} AND uo.deleted_flag = 0
                    AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                    AND uo.user_id IN <foreach collection="allowedDirectUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
              </if>
              <if test="(allowedOrganizationIds == null or allowedOrganizationIds.size() == 0) and (allowedDirectUserIds == null or allowedDirectUserIds.size() == 0)">
                1 = 0
              </if>
            )
          </if>
        </script>
        """)
    java.time.LocalDateTime selectOldestPendingUpdatedAtScoped(
            @Param("workMonth") String workMonth,
            @Param("monthEnd") String monthEnd,
            @Param("asOf") java.time.LocalDate asOf,
            @Param("fullAccess") boolean fullAccess,
            @Param("allowedOrganizationIds") List<Long> allowedOrganizationIds,
            @Param("allowedDirectUserIds") List<Long> allowedDirectUserIds,
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds,
            @Param("tenantId") String tenantId);

    default java.time.LocalDateTime selectOldestPendingUpdatedAtScoped(
            String workMonth, String monthEnd, java.time.LocalDate asOf, boolean fullAccess,
            List<Long> allowedOrganizationIds, List<Long> allowedDirectUserIds,
            List<Long> dataScopeContractIds) {
        return selectOldestPendingUpdatedAtScoped(workMonth, monthEnd, asOf, fullAccess,
                allowedOrganizationIds, allowedDirectUserIds, dataScopeContractIds,
                AccountingTenantContextHolder.requireTenantContext());
    }

    /** 対象月の指定状態だけを、契約・顧客ownershipで抽出する。空状態は0件とする。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
          WHERE w.work_month = #{workMonth}
          AND w.status IN
          <choose>
            <when test="statuses != null and statuses.size() > 0">
              <foreach collection="statuses" item="status" open="(" separator="," close=")">#{status}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        </script>
        """)
    List<WorkRecord> selectByWorkMonthAndStatusesForTenant(@Param("workMonth") String workMonth,
                                                            @Param("statuses") List<String> statuses,
                                                            @Param("tenantId") String tenantId);

    /** 未確定実績を契約・顧客ownershipで抽出する。 */
    @Select("""
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE w.work_month = #{workMonth} AND w.status <> '確定'
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        """)
    List<WorkRecord> selectUnconfirmedByWorkMonthForTenant(@Param("workMonth") String workMonth,
                                                            @Param("tenantId") String tenantId);

    /** 対象月の全実績を契約・顧客ownershipで抽出する。 */
    @Select("SELECT w.* FROM t_work_record w "
            + "INNER JOIN t_contract c ON c.id = w.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "INNER JOIN m_customer mc ON mc.id = c.customer_id "
            + "AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id "
            + "AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE w.work_month = #{workMonth}" + CONTRACT_OWNERSHIP + " ORDER BY w.id ASC")
    List<WorkRecord> selectByWorkMonthForTenant(@Param("workMonth") String workMonth,
                                                @Param("tenantId") String tenantId);

    /** 契約母集団が空の場合も全件化しない、月次実績のtenant-aware抽出。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE w.work_month = #{workMonth}
          AND c.id IN
          <choose>
            <when test="contractIds != null and contractIds.size() > 0">
              <foreach collection="contractIds" item="contractId" open="(" separator="," close=")">#{contractId}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        </script>
        """)
    List<WorkRecord> selectByContractIdsAndWorkMonthForTenant(@Param("contractIds") List<Long> contractIds,
                                                               @Param("workMonth") String workMonth,
                                                               @Param("tenantId") String tenantId);

    /** 複数月の確定実績を契約・顧客ownershipで一括取得する。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id IS NOT NULL
          AND mc.tenant_id = c.tenant_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
          WHERE w.status = '確定'
          AND w.work_month IN
          <choose>
            <when test="workMonths != null and workMonths.size() > 0">
              <foreach collection="workMonths" item="workMonth" open="(" separator="," close=")">#{workMonth}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        </script>
        """)
    List<WorkRecord> selectConfirmedByWorkMonthsForTenant(@Param("workMonths") List<String> workMonths,
                                                          @Param("tenantId") String tenantId);

    /** 確定実績の月次集計を契約母集団とtenantの両方で限定する。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id
          AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
          AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
          WHERE w.status = '確定'
          AND w.work_month IN
          <choose>
            <when test="workMonths != null and workMonths.size() > 0">
              <foreach collection="workMonths" item="workMonth" open="(" separator="," close=")">#{workMonth}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
          AND c.id IN
          <choose>
            <when test="contractIds != null and contractIds.size() > 0">
              <foreach collection="contractIds" item="contractId" open="(" separator="," close=")">#{contractId}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        </script>
        """)
    List<WorkRecord> selectConfirmedByWorkMonthsAndContractIdsForTenant(
            @Param("workMonths") List<String> workMonths,
            @Param("contractIds") List<Long> contractIds,
            @Param("tenantId") String tenantId);

    /** 実績ID集合を契約・顧客ownershipで解決する。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id
          AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
          AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
          WHERE w.id IN
        <choose>
          <when test="ids != null and ids.size() > 0">
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          </when>
          <otherwise>(NULL)</otherwise>
        </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id
        </script>
        """)
    List<WorkRecord> selectByIdsForTenant(@Param("ids") List<Long> ids,
                                          @Param("tenantId") String tenantId);

    /** 確定実績を月・実績ID・契約ownershipで限定する。 */
    @Select("""
        <script>
        SELECT w.*
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
          AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0
        INNER JOIN m_customer mc ON mc.id = c.customer_id
          AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
          AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
        WHERE w.work_month = #{workMonth} AND w.status = '確定'
          AND w.id IN
          <choose>
            <when test="ids != null and ids.size() > 0">
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        """ + CONTRACT_OWNERSHIP + """
        ORDER BY w.id ASC
        </script>
        """)
    List<WorkRecord> selectConfirmedByWorkMonthAndIdsForTenant(@Param("workMonth") String workMonth,
                                                               @Param("ids") List<Long> ids,
                                                               @Param("tenantId") String tenantId);

    /** version付き勤怠更新。契約・顧客ownershipをEXISTSで再検証する。 */
    @org.apache.ibatis.annotations.Update("""
        <script>
        UPDATE t_work_record w
        SET actual_hours = #{record.actualHours},
            billing_amount = #{record.billingAmount},
            payment_amount = #{record.paymentAmount},
            status = #{record.status},
            remarks = #{record.remarks},
            organization_id = #{record.organizationId},
            cost_center_id = #{record.costCenterId},
            accounting_dimension_frozen = #{record.accountingDimensionFrozen},
            reject_comment = #{record.rejectComment},
            updated_at = CURRENT_TIMESTAMP,
            version = version + 1
        WHERE w.id = #{record.id}
          AND w.version = #{expectedVersion}
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
        </script>
        """)
    int updateByIdForTenant(@Param("record") WorkRecord record,
                            @Param("expectedVersion") Integer expectedVersion,
                            @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record w
        SET status = '提出済', reject_comment = NULL,
            updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE w.id = #{id} AND w.version = #{expectedVersion}
          AND w.status IN ('入力中', '差戻し')
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
        """)
    int updateToSubmittedForTenant(@Param("id") Long id,
                                   @Param("expectedVersion") Integer expectedVersion,
                                   @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record w
        SET status = '確定', organization_id = #{organizationId}, cost_center_id = #{costCenterId},
            accounting_dimension_frozen = 1,
            updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE w.id = #{id} AND w.version = #{expectedVersion}
          AND w.status IN ('入力中', '提出済')
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
        """)
    int updateToConfirmedForTenant(@Param("id") Long id,
                                   @Param("expectedVersion") Integer expectedVersion,
                                   @Param("organizationId") Long organizationId,
                                   @Param("costCenterId") Long costCenterId,
                                   @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record w
        SET status = '差戻し', reject_comment = #{comment},
            updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE w.id = #{id} AND w.version = #{expectedVersion} AND w.status = '提出済'
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
        """)
    int updateToRejectedForTenant(@Param("id") Long id,
                                  @Param("expectedVersion") Integer expectedVersion,
                                  @Param("comment") String comment,
                                  @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record w
        SET status = '入力中', updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE w.id = #{id} AND w.version = #{expectedVersion} AND w.status = '確定'
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
        """)
    int updateToInputForTenant(@Param("id") Long id,
                               @Param("expectedVersion") Integer expectedVersion,
                               @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record w
        SET billing_amount = #{billingAmount}, payment_amount = #{paymentAmount},
            updated_at = NOW(), version = version + 1
        WHERE w.id = #{id} AND w.status != '確定' AND w.actual_hours = #{actualHours}
          AND w.version = #{version}
          AND EXISTS (
              SELECT 1 FROM t_contract c
              INNER JOIN m_customer mc ON mc.id = c.customer_id
                AND mc.tenant_id IS NOT NULL AND mc.tenant_id = c.tenant_id
                AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0
              WHERE c.id = w.contract_id
                AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId}
                AND c.deleted_flag = 0
                AND EXISTS (SELECT 1 FROM t_engineer e0 WHERE e0.id = c.engineer_id
                  AND e0.tenant_id IS NOT NULL AND e0.tenant_id = #{tenantId} AND e0.deleted_flag = 0)
                AND EXISTS (SELECT 1 FROM t_project p0 WHERE p0.id = c.project_id
                  AND p0.customer_id = c.customer_id AND p0.deleted_flag = 0
                  AND (p0.created_by IS NULL OR EXISTS (SELECT 1 FROM sys_user pu0 WHERE pu0.id = p0.created_by
                    AND pu0.tenant_id IS NOT NULL AND pu0.tenant_id = #{tenantId} AND pu0.deleted_flag = 0)))
                AND (c.sales_user_id IS NULL OR EXISTS (SELECT 1 FROM sys_user su0 WHERE su0.id = c.sales_user_id
                  AND su0.tenant_id IS NOT NULL AND su0.tenant_id = #{tenantId} AND su0.deleted_flag = 0))
           )
    """)
    int updateBillingAndPayment(@Param("id") Long id,
                                @Param("actualHours") java.math.BigDecimal actualHours,
                                @Param("billingAmount") java.math.BigDecimal billingAmount,
                                @Param("paymentAmount") java.math.BigDecimal paymentAmount,
                                @Param("version") Integer version,
                                @Param("tenantId") String tenantId);
}
