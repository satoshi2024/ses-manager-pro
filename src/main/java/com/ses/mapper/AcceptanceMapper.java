package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.acceptance.AcceptanceGridDto;
import com.ses.entity.Acceptance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 月次検収マッパー。
 * グリッドは契約・要員・顧客・案件・顧客担当者とJOINする。
 */
@Mapper
public interface AcceptanceMapper extends BaseMapper<Acceptance> {

    /** グリッド一覧。scopeは contractIds で絞る（空リストなら1=0で0件）。 */
    @Select("""
        <script>
        SELECT
            a.id                   AS id,
            a.contract_id          AS contractId,
            c.contract_no          AS contractNo,
            c.engineer_id          AS engineerId,
            e.full_name            AS engineerName,
            c.customer_id          AS customerId,
            cst.company_name       AS customerName,
            c.project_id           AS projectId,
            p.project_name         AS projectName,
            a.work_record_id       AS workRecordId,
            a.work_month           AS workMonth,
            a.status               AS status,
            a.submitted_at         AS submittedAt,
            a.customer_contact_id  AS customerContactId,
            cc.name                AS customerContactName,
            a.accepted_at          AS acceptedAt,
            a.reject_comment       AS rejectComment,
            a.hours_snapshot       AS hoursSnapshot,
            a.amount_snapshot      AS amountSnapshot,
            a.version              AS version,
            c.acceptance_required  AS acceptanceRequired
        FROM t_acceptance a
        INNER JOIN t_contract c ON c.id = a.contract_id AND c.deleted_flag = 0
        INNER JOIN t_engineer e ON e.id = c.engineer_id AND e.deleted_flag = 0
        LEFT JOIN m_customer cst ON cst.id = c.customer_id
        LEFT JOIN t_project p ON p.id = c.project_id
        LEFT JOIN t_customer_contact cc ON cc.id = a.customer_contact_id
        WHERE a.deleted_flag = 0
          <if test="workMonth != null and workMonth != ''">AND a.work_month = #{workMonth}</if>
          <if test="status != null and status != ''">AND a.status = #{status}</if>
          <if test="customerId != null">AND c.customer_id = #{customerId}</if>
          <if test="engineerId != null">AND c.engineer_id = #{engineerId}</if>
          <if test="contractIds != null">
            <choose>
              <when test="contractIds.size() == 0">AND 1 = 0</when>
              <otherwise>AND a.contract_id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach></otherwise>
            </choose>
          </if>
        ORDER BY a.work_month DESC, a.id DESC
        </script>
        """)
    Page<AcceptanceGridDto> selectGridPage(Page<AcceptanceGridDto> page,
            @Param("workMonth") String workMonth,
            @Param("status") String status,
            @Param("customerId") Long customerId,
            @Param("engineerId") Long engineerId,
            @Param("contractIds") List<Long> contractIds);

    @Select("SELECT * FROM t_acceptance WHERE contract_id = #{contractId} AND work_month = #{workMonth} AND deleted_flag = 0 FOR UPDATE")
    Acceptance selectByContractAndMonthForUpdate(@Param("contractId") Long contractId, @Param("workMonth") String workMonth);

    /** 月次締めchecklist用: 検収不要契約以外で、検収済でない実績の件数（scope適用）。 */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id AND c.deleted_flag = 0
        WHERE w.work_month = #{workMonth}
          AND w.status = '確定'
          AND c.acceptance_required = 1
          AND NOT EXISTS (
              SELECT 1 FROM t_acceptance a
              WHERE a.contract_id = c.id AND a.work_month = w.work_month
                AND a.status = '検収済' AND a.deleted_flag = 0
          )
          <if test="contractIds != null">
            <choose>
              <when test="contractIds.size() == 0">AND 1 = 0</when>
              <otherwise>
                AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </otherwise>
            </choose>
          </if>
        </script>
        """)
    long countUnacceptedForClosing(@Param("workMonth") String workMonth, @Param("contractIds") List<Long> contractIds);

    /** dashboard用: 未検収売上（検収要・確定済・未検収済の実績の請求金額合計）。scopeは contractIds。 */
    @Select("""
        <script>
        SELECT COALESCE(SUM(w.billing_amount), 0)
        FROM t_work_record w
        INNER JOIN t_contract c ON c.id = w.contract_id AND c.deleted_flag = 0
        WHERE w.status = '確定'
          AND c.acceptance_required = 1
          AND NOT EXISTS (
              SELECT 1 FROM t_acceptance a
              WHERE a.contract_id = c.id AND a.work_month = w.work_month
                AND a.status = '検収済' AND a.deleted_flag = 0
          )
          <if test="contractIds != null">
            <choose>
              <when test="contractIds.size() == 0">AND 1 = 0</when>
              <otherwise>
                AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </otherwise>
            </choose>
          </if>
        </script>
        """)
    BigDecimal sumUnacceptedSales(@Param("contractIds") List<Long> contractIds);

    /** dashboard用: 検収平均日数（提出日→検収日）。scopeは contractIds。 */
    @Select("""
        <script>
        SELECT COALESCE(AVG(DATEDIFF(a.accepted_at, a.submitted_at)), 0)
        FROM t_acceptance a
        INNER JOIN t_contract c ON c.id = a.contract_id AND c.deleted_flag = 0
        WHERE a.status = '検収済' AND a.deleted_flag = 0
          AND a.submitted_at IS NOT NULL AND a.accepted_at IS NOT NULL
          <if test="contractIds != null">
            <choose>
              <when test="contractIds.size() == 0">AND 1 = 0</when>
              <otherwise>
                AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </otherwise>
            </choose>
          </if>
        </script>
        """)
    java.math.BigDecimal avgAcceptanceDays(@Param("contractIds") List<Long> contractIds);
}
