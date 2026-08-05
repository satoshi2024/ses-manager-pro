package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.order.SalesOrderListDto;
import com.ses.entity.SalesOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 注文マッパー。
 * 一覧は顧客名・明細数とJOINしたDTOを返す（注文一覧と同じscope母集団で絞り込む）。
 */
@Mapper
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {

    @Select("SELECT MAX(order_no) FROM t_sales_order WHERE order_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxOrderNo(@Param("prefix") String prefix);

    /** 承認最終適用・状態遷移の対象行をロックして取得する。 */
    @Select("SELECT * FROM t_sales_order WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    SalesOrder selectByIdForUpdate(@Param("id") Long id);

    /** 注文一覧（scope条件は呼出側で customerIds を渡す。空リストなら1=0で0件）。 */
    @Select("""
        <script>
        SELECT
            o.id                  AS id,
            o.order_no            AS orderNo,
            o.customer_po_no      AS customerPoNo,
            o.customer_id         AS customerId,
            c.company_name        AS customerName,
            o.order_date          AS orderDate,
            o.start_date          AS startDate,
            o.end_date            AS endDate,
            o.status              AS status,
            o.total_amount_snapshot AS totalAmountSnapshot,
            o.quotation_id        AS quotationId,
            (SELECT COUNT(*) FROM t_sales_order_line l
              WHERE l.order_id = o.id AND l.deleted_flag = 0) AS engineerCount
        FROM t_sales_order o
        LEFT JOIN m_customer c ON c.id = o.customer_id
        WHERE o.deleted_flag = 0
          <if test="status != null and status != ''">AND o.status = #{status}</if>
          <if test="keyword != null and keyword != ''">
            AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
              OR o.customer_po_no LIKE CONCAT('%', #{keyword}, '%'))
          </if>
          <if test="dateFrom != null">AND o.order_date &gt;= #{dateFrom}</if>
          <if test="dateTo != null">AND o.order_date &lt;= #{dateTo}</if>
          <if test="customerIds != null">
            <choose>
              <when test="customerIds.size() == 0">AND 1 = 0</when>
              <otherwise>
                AND o.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </otherwise>
            </choose>
          </if>
        ORDER BY o.order_date DESC, o.id DESC
        </script>
        """)
    Page<SalesOrderListDto> selectPageWithNames(Page<SalesOrderListDto> page,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("customerIds") List<Long> customerIds);

    /** 注文をscope母集団（顧客ID集合）で絞ったID一覧。document ACL・ダッシュボード用。 */
    @Select("""
        <script>
        SELECT id FROM t_sales_order WHERE deleted_flag = 0
        <if test="customerIds != null">
          <choose>
            <when test="customerIds.size() == 0">AND 1 = 0</when>
            <otherwise>AND customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach></otherwise>
          </choose>
        </if>
        </script>
        """)
    List<Long> selectOrderIdsByCustomerScope(@Param("customerIds") List<Long> customerIds);
}
