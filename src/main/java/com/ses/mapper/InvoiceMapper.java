package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Invoice;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {

    @Select("SELECT MAX(invoice_no) FROM t_invoice WHERE invoice_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxInvoiceNoIncludingDeleted(@Param("prefix") String prefix);

    /**
     * 請求書×入金合計の残高付き一覧。残高 = total - Σ(amount+fee)。
     * 入金済（＝残高0の完済）と取消(deleted)を除外し、未回収残高がある請求書のみ返す。
     * エイジングの区分振り分けは Java 側で行う（境界テストを書きやすくするため）。
     */
    @Select("""
        SELECT
            i.id            AS invoiceId,
            i.invoice_no    AS invoiceNo,
            i.customer_id   AS customerId,
            c.company_name  AS customerName,
            i.billing_month AS billingMonth,
            i.status        AS status,
            i.total         AS total,
            COALESCE(p.paid_total, 0)                 AS paidTotal,
            i.total - COALESCE(p.paid_total, 0)       AS balance,
            i.due_date      AS dueDate
        FROM t_invoice i
        LEFT JOIN m_customer c ON i.customer_id = c.id
        LEFT JOIN (
            SELECT invoice_id, SUM(amount + fee) AS paid_total
            FROM t_invoice_payment
            GROUP BY invoice_id
        ) p ON p.invoice_id = i.id
        WHERE i.deleted_flag = 0
          AND i.status <> '入金済'
          AND i.total - COALESCE(p.paid_total, 0) > 0
        ORDER BY i.customer_id, i.due_date
    """)
    List<InvoiceBalanceDto> selectOutstandingBalances();

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
          AND c.deleted_flag = 0
          AND w.work_month = #{billingMonth}
          AND w.status = '確定'
          AND w.id NOT IN (
              SELECT it.work_record_id FROM t_invoice_item it
              JOIN t_invoice i ON it.invoice_id = i.id AND i.deleted_flag = 0
          )
    """)
    List<UnbilledWorkRecordDto> selectUnbilledWorkRecords(@Param("customerId") Long customerId, @Param("billingMonth") String billingMonth);

    /**
     * 全顧客版の確定済み未請求実績（月次締めチェックリスト用）。
     * WHERE 句は {@link #selectUnbilledWorkRecords} から顧客条件を除いただけ。
     * 除外サブクエリ（有効請求書明細）と c.deleted_flag=0 は同クエリと一字一句同期させること
     * （残高・未請求定義の二重定義を防ぐため。片方を変えたら必ず両方直す）。
     */
    @Select("""
        SELECT
            w.id AS workRecordId,
            w.billing_amount AS billingAmount,
            e.full_name AS engineerName,
            p.project_name AS projectName,
            c.customer_id AS customerId
        FROM t_work_record w
        INNER JOIN t_contract c ON w.contract_id = c.id
        INNER JOIN t_engineer e ON c.engineer_id = e.id
        INNER JOIN t_project p ON c.project_id = p.id
        WHERE c.deleted_flag = 0
          AND w.work_month = #{billingMonth}
          AND w.status = '確定'
          AND w.id NOT IN (
              SELECT it.work_record_id FROM t_invoice_item it
              JOIN t_invoice i ON it.invoice_id = i.id AND i.deleted_flag = 0
          )
    """)
    List<UnbilledWorkRecordDto> selectUnbilledWorkRecordsAll(@Param("billingMonth") String billingMonth);
}
