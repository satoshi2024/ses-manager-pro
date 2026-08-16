package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.BpPayment;
import com.ses.dto.invoice.BpPaymentListDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpPaymentMapper extends BaseMapper<BpPayment> {

    /** 承認最終適用の対象行をロックして取得する。request行→BP支払行の順で呼び出す。 */
    @Select("SELECT * FROM t_bp_payment WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    BpPayment selectByIdForUpdate(@Param("id") Long id);

    /**
     * BP portal用: 自社（bp_company_id）の発注/作業実績一覧（SQL境界。design §6.2）。
     * 自社分の金額のみ。他BPの階層・社内情報は返さない。
     */
    @Select("""
        <script>
        SELECT
            p.id AS id,
            w.work_month AS workMonth,
            w.actual_hours AS actualHours,
            p.amount AS amount,
            p.status AS status,
            p.paid_date AS paidDate,
            p.received_confirmed_at AS receivedConfirmedAt,
            w.status AS workRecordStatus,
            c.contract_no AS contractNo,
            e.full_name AS engineerName
        FROM t_bp_payment p
        LEFT JOIN t_work_record w ON w.id = p.work_record_id
        LEFT JOIN t_contract c ON c.id = w.contract_id AND c.deleted_flag = 0
        LEFT JOIN t_engineer e ON e.id = c.engineer_id AND e.deleted_flag = 0
        WHERE p.bp_company_id = #{bpCompanyId}
          AND p.deleted_flag = 0
          <if test="status != null and status != ''">AND p.status = #{status}</if>
        ORDER BY w.work_month DESC, p.id DESC
        </script>
        """)
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.portal.PortalBpPaymentDto> selectPortalPageDto(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.portal.PortalBpPaymentDto> page,
            @Param("bpCompanyId") Long bpCompanyId,
            @Param("status") String status);

    /**
     * 受領確認の一回性CAS（R3.2）。自社分・未払・未確認のみ設定できる。更新0件=既に確認済み。
     */
    @org.apache.ibatis.annotations.Update("UPDATE t_bp_payment SET received_confirmed_at = #{now}"
            + " WHERE id = #{id} AND bp_company_id = #{bpCompanyId}"
            + " AND received_confirmed_at IS NULL AND status = '未払' AND deleted_flag = 0")
    int confirmReceipt(@Param("id") Long id, @Param("bpCompanyId") Long bpCompanyId,
                       @Param("now") java.time.LocalDateTime now);

    @Select("""
        <script>
        SELECT
            b.id AS id,
            b.work_record_id AS workRecordId,
            w.work_month AS workMonth,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            b.amount AS amount,
            b.status AS status,
            b.paid_date AS paidDate,
            b.layer_order AS layerOrder,
            b.payee_company_name AS payeeCompanyName,
            b.parent_payment_id AS parentPaymentId
        FROM t_bp_payment b
        INNER JOIN t_work_record w ON b.work_record_id = w.id
        INNER JOIN t_contract c ON w.contract_id = c.id
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        <where>
            <if test='month != null and month != ""'>
                AND w.work_month = #{month}
            </if>
            <if test='status != null and status != ""'>
                AND b.status = #{status}
            </if>
            AND b.deleted_flag = 0
        </where>
        ORDER BY w.id DESC, b.layer_order ASC
        </script>
    """)
    List<BpPaymentListDto> selectListWithDetails(@Param("month") String month, @Param("status") String status);

    @Select("""
        <script>
        SELECT b.id AS id, b.work_record_id AS workRecordId, w.work_month AS workMonth,
               e.full_name AS engineerName, p.project_name AS projectName,
               b.amount AS amount, b.status AS status, b.paid_date AS paidDate,
               b.layer_order AS layerOrder, b.payee_company_name AS payeeCompanyName,
               b.parent_payment_id AS parentPaymentId
        FROM t_bp_payment b
        INNER JOIN t_work_record w ON b.work_record_id = w.id
        INNER JOIN t_contract c ON w.contract_id = c.id
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{asOf}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
        <where>
            <if test='month != null and month != ""'>AND w.work_month = #{month}</if>
            <if test='status != null and status != ""'>AND b.status = #{status}</if>
            AND b.deleted_flag = 0
            <if test="contractIds != null">
                <choose>
                    <when test="contractIds.size() > 0">AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach></when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </if>
            <if test="organizationIds != null or directUserIds != null">
            AND (
              <if test="organizationIds != null and organizationIds.size() > 0">
                CASE WHEN w.accounting_dimension_frozen = 1
                     THEN w.organization_id
                     ELSE COALESCE(e.organization_id, uo.organization_id)
                END IN <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="directUserIds != null and directUserIds.size() > 0">
                <if test="organizationIds != null and organizationIds.size() > 0">OR</if>
                l.sys_user_id IN <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
            )
            </if>
        </where>
        ORDER BY w.id DESC, b.layer_order ASC
        </script>
        """)
    List<BpPaymentListDto> selectListWithDetailsScoped(@Param("month") String month,
                                                        @Param("status") String status,
                                                        @Param("contractIds") List<Long> contractIds,
                                                        @Param("organizationIds") List<Long> organizationIds,
                                                        @Param("directUserIds") List<Long> directUserIds,
                                                        @Param("asOf") java.time.LocalDate asOf);

    @Select("SELECT * FROM t_bp_payment WHERE work_record_id = #{workRecordId} AND deleted_flag = 0 ORDER BY layer_order ASC")
    List<BpPayment> selectByWorkRecordIdOrderByLayer(@Param("workRecordId") Long workRecordId);

    @Select("<script>SELECT * FROM t_bp_payment WHERE work_record_id IN <foreach collection='workRecordIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND deleted_flag = 0 ORDER BY work_record_id, layer_order</script>")
    List<BpPayment> selectByWorkRecordIds(@Param("workRecordIds") List<Long> workRecordIds);

    /**
     * 契約に紐づく実績(work_record)配下のBP階層のうち最大階層番号を返す(該当なしは0)。
     * 多重下請け段数超過・二重派遣兆候の判定(labor-compliance-check / FR-10)に使用。
     */
    @Select("""
        SELECT COALESCE(MAX(b.layer_order), 0)
        FROM t_bp_payment b
        INNER JOIN t_work_record w ON b.work_record_id = w.id
        WHERE w.contract_id = #{contractId} AND b.deleted_flag = 0
    """)
    Integer selectMaxLayerOrderByContractId(@Param("contractId") Long contractId);

    /**
     * 全契約分の最大BP階層番号を一括取得する（管理者向けリスク一覧のN+1回避用）。
     */
    @Select("""
        SELECT w.contract_id AS contractId, MAX(b.layer_order) AS maxLayer
        FROM t_bp_payment b
        INNER JOIN t_work_record w ON b.work_record_id = w.id
        WHERE b.deleted_flag = 0
        GROUP BY w.contract_id
    """)
    List<com.ses.dto.compliance.ContractTierDto> selectMaxLayerOrderGroupedByContract();
}
