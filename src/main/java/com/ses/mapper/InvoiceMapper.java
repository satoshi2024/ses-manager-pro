package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Invoice;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {

    @Select("""
        SELECT
            w.id AS workRecordId,
            w.billing_amount AS billingAmount,
            e.full_name AS engineerName,
            p.project_name AS projectName
        FROM t_work_record w
        INNER JOIN t_contract c ON w.contract_id = c.id
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        WHERE c.customer_id = #{customerId}
          AND w.work_month = #{billingMonth}
          AND w.status = '確定'
          AND w.id NOT IN (
              SELECT work_record_id FROM t_invoice_item
          )
    """)
    List<UnbilledWorkRecordDto> selectUnbilledWorkRecords(@Param("customerId") Long customerId, @Param("billingMonth") String billingMonth);
}
