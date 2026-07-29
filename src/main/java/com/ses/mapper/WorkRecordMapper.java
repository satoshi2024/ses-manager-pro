package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkRecordMapper extends BaseMapper<WorkRecord> {
    @Select("SELECT work_month FROM t_work_record WHERE id = #{id}")
    String selectWorkMonthById(@Param("id") Long id);

    @Select("SELECT * FROM t_work_record WHERE id = #{id} FOR UPDATE")
    WorkRecord selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_work_record WHERE contract_id = #{contractId} AND work_month = #{workMonth} FOR UPDATE")
    WorkRecord selectByContractIdAndMonthForUpdate(@Param("contractId") Long contractId, @Param("workMonth") String workMonth);

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
            w.reject_comment AS rejectComment
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.deleted_flag = 0
        ORDER BY c.id DESC
    """)
    List<WorkRecordGridDto> selectMonthlyGrid(@Param("workMonth") String workMonth, @Param("monthEnd") String monthEnd);

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
            w.status AS status, w.remarks AS remarks, w.reject_comment AS rejectComment
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date &lt;= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date &gt;= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.deleted_flag = 0
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
                      WHERE l.engineer_id = c.engineer_id AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                  WHERE l.engineer_id = c.engineer_id AND uo.deleted_flag = 0
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
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds);

    @Select("""
        <script>
        SELECT w.* FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id
        INNER JOIN t_engineer e ON e.id = c.engineer_id AND e.deleted_flag = 0
        WHERE w.id = #{id} AND c.deleted_flag = 0
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
                      WHERE l.engineer_id = c.engineer_id AND uo.deleted_flag = 0
                        AND uo.valid_from &lt;= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
                        AND uo.organization_id IN <foreach collection="allowedOrganizationIds" item="orgId" open="(" separator="," close=")">#{orgId}</foreach>)
                  )
                )
              </if>
              <if test="allowedDirectUserIds != null and allowedDirectUserIds.size() > 0">
                <if test="allowedOrganizationIds != null and allowedOrganizationIds.size() > 0">OR</if>
                EXISTS (SELECT 1 FROM t_engineer_account_link l JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
                  WHERE l.engineer_id = c.engineer_id AND uo.deleted_flag = 0
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
            @Param("dataScopeContractIds") List<Long> dataScopeContractIds);

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
            w.reject_comment AS rejectComment
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.engineer_id = #{engineerId}
          AND c.start_date <= #{monthEnd}
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.deleted_flag = 0
        ORDER BY c.id DESC
    """)
    List<WorkRecordGridDto> selectMonthlyGridForEngineer(@Param("engineerId") Long engineerId,
                                                         @Param("workMonth") String workMonth,
                                                         @Param("monthEnd") String monthEnd);

    @Select("""
        SELECT e.employment_type
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        WHERE c.id = #{contractId}
    """)
    String selectEmploymentTypeByContractId(@Param("contractId") Long contractId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_work_record
        SET billing_amount = #{billingAmount}, payment_amount = #{paymentAmount}, updated_at = NOW()
        WHERE id = #{id} AND status != '確定' AND actual_hours = #{actualHours}
    """)
    int updateBillingAndPayment(@Param("id") Long id,
                                @Param("actualHours") java.math.BigDecimal actualHours,
                                @Param("billingAmount") java.math.BigDecimal billingAmount,
                                @Param("paymentAmount") java.math.BigDecimal paymentAmount);
}
