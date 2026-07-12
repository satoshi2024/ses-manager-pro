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
            w.remarks AS remarks
        FROM t_contract c
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        LEFT JOIN t_work_record w ON c.id = w.contract_id AND w.work_month = #{workMonth}
        WHERE c.start_date <= CONCAT(#{workMonth}, '-31')
          AND (c.end_date IS NULL OR c.end_date >= CONCAT(#{workMonth}, '-01'))
          AND c.status IN ('稼動中', '終了')
          AND c.deleted_flag = 0
        ORDER BY c.id DESC
    """)
    List<WorkRecordGridDto> selectMonthlyGrid(@Param("workMonth") String workMonth);
}
