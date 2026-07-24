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

    @Select("SELECT * FROM t_bp_payment WHERE work_record_id = #{workRecordId} AND deleted_flag = 0 ORDER BY layer_order ASC")
    List<BpPayment> selectByWorkRecordIdOrderByLayer(@Param("workRecordId") Long workRecordId);

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
