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
            b.paid_date AS paidDate
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
        </where>
        ORDER BY b.id DESC
        </script>
    """)
    List<BpPaymentListDto> selectListWithDetails(@Param("month") String month, @Param("status") String status);
}
