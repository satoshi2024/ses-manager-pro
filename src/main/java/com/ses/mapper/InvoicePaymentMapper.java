package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.InvoicePayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoicePaymentMapper extends BaseMapper<InvoicePayment> {

    /** 月次照合 (入金母集団) の組織スコープ付き一覧 (R1-P1-06 / design §5.1)。 */
    @Select("""
        <script>
        SELECT ip.* FROM t_invoice_payment ip
        INNER JOIN t_invoice i ON i.id = ip.invoice_id AND i.deleted_flag = 0
        WHERE ip.paid_date &gt;= #{startDate} AND ip.paid_date &lt;= #{endDate}
          AND (
            <if test="orgIds.size() == 0">1 = 0</if>
            <if test="orgIds.size() > 0">
              EXISTS (
                SELECT 1 FROM m_cost_center cc
                WHERE cc.id = i.cost_center_id AND cc.organization_id IS NOT NULL
                  AND cc.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
              )
              OR (
                NOT EXISTS (
                  SELECT 1 FROM m_cost_center cc WHERE cc.id = i.cost_center_id AND cc.organization_id IS NOT NULL
                )
                AND EXISTS (
                  SELECT 1 FROM t_invoice_item ii0
                  JOIN t_work_record wr0 ON wr0.id = ii0.work_record_id
                  JOIN t_contract c0 ON c0.id = wr0.contract_id AND c0.deleted_flag = 0
                  JOIN t_user_organization uo0 ON uo0.user_id = c0.sales_user_id
                       AND uo0.primary_flag = 1 AND uo0.deleted_flag = 0
                       AND uo0.valid_from &lt;= COALESCE(i.issued_date,
                           CASE WHEN i.billing_month IS NULL OR i.billing_month = '' THEN CURRENT_DATE
                                ELSE CAST(CONCAT(TRIM(i.billing_month), '-01') AS DATE) + INTERVAL '1' MONTH - INTERVAL '1' DAY
                           END)
                       AND (uo0.valid_to IS NULL OR uo0.valid_to &gt;= COALESCE(i.issued_date,
                           CASE WHEN i.billing_month IS NULL OR i.billing_month = '' THEN CURRENT_DATE
                                ELSE CAST(CONCAT(TRIM(i.billing_month), '-01') AS DATE) + INTERVAL '1' MONTH - INTERVAL '1' DAY
                           END))
                  WHERE ii0.invoice_id = i.id
                    AND ii0.id = (SELECT MIN(ii1.id) FROM t_invoice_item ii1 WHERE ii1.invoice_id = i.id AND ii1.work_record_id IS NOT NULL)
                    AND uo0.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
                )
              )
            </if>
          )
        </script>
        """)
    List<InvoicePayment> selectForReconciliationScoped(@Param("startDate") java.time.LocalDate startDate,
                                                       @Param("endDate") java.time.LocalDate endDate,
                                                       @Param("orgIds") List<Long> orgIds);
}
