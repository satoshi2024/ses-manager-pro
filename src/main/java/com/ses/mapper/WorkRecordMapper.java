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
