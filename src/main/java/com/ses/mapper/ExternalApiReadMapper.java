package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiReadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** A1専用read mapper。external responseのallow-listへ必要な列だけをSQLで投影する。 */
@Mapper
public interface ExternalApiReadMapper {

    @Select("""
        <script>
        SELECT e.id, e.status, e.available_date AS availableDate
        FROM t_engineer e
        WHERE e.deleted_flag = 0
          AND e.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="afterId != null">AND e.id &lt; #{afterId}</if>
        ORDER BY e.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectEngineers(@Param("ids") List<Long> ids,
                                             @Param("afterId") Long afterId,
                                             @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_engineer e
        WHERE e.deleted_flag = 0
          AND e.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    long countEngineers(@Param("ids") List<Long> ids);

    @Select("""
        <script>
        SELECT p.id, p.status, p.start_date AS startDate, p.end_date AS endDate, p.customer_id AS customerId
        FROM t_project p
        WHERE p.deleted_flag = 0
          AND p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            AND p.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
          <if test="afterId != null">AND p.id &lt; #{afterId}</if>
        ORDER BY p.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectProjects(@Param("ids") List<Long> ids,
                                            @Param("customerIds") List<Long> customerIds,
                                            @Param("afterId") Long afterId,
                                            @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_project p
        WHERE p.deleted_flag = 0
          AND p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            AND p.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
        </script>
        """)
    long countProjects(@Param("ids") List<Long> ids,
                       @Param("customerIds") List<Long> customerIds);

    @Select("""
        <script>
        SELECT c.id, c.project_id AS projectId, c.status, c.start_date AS startDate, c.end_date AS endDate,
               c.renewal_decision AS renewalStatus
        FROM t_contract c
        WHERE c.deleted_flag = 0
          AND c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="projectIds != null">
            AND c.project_id IN <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
          <if test="afterId != null">AND c.id &lt; #{afterId}</if>
        ORDER BY c.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectContracts(@Param("ids") List<Long> ids,
                                             @Param("projectIds") List<Long> projectIds,
                                             @Param("afterId") Long afterId,
                                             @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_contract c
        WHERE c.deleted_flag = 0
          AND c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="projectIds != null">
            AND c.project_id IN <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
        </script>
        """)
    long countContracts(@Param("ids") List<Long> ids,
                        @Param("projectIds") List<Long> projectIds);

    @Select("""
        <script>
        SELECT i.id, i.status, i.customer_id AS customerId, i.issued_date AS issueDate, i.due_date AS dueDate, i.paid_date AS paidDate,
               ii.contract_id AS contractId, ii.contract_count AS contractCount
        FROM t_invoice i
        LEFT JOIN (
            SELECT invoice_id, MIN(c.id) AS contract_id, COUNT(DISTINCT c.id) AS contract_count
            FROM t_invoice_item item
            JOIN t_work_record wr ON wr.id = item.work_record_id
            JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
            GROUP BY invoice_id
        ) ii ON ii.invoice_id = i.id
        WHERE i.deleted_flag = 0
          AND i.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            AND i.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
          <if test="contractIds != null">
            AND EXISTS (
              SELECT 1
              FROM t_invoice_item scoped_item
              JOIN t_work_record scoped_wr ON scoped_wr.id = scoped_item.work_record_id
              JOIN t_contract scoped_contract ON scoped_contract.id = scoped_wr.contract_id
                AND scoped_contract.deleted_flag = 0
              WHERE scoped_item.invoice_id = i.id
                AND scoped_contract.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            )
          </if>
          <if test="afterId != null">AND i.id &lt; #{afterId}</if>
        ORDER BY i.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectInvoices(@Param("ids") List<Long> ids,
                                            @Param("contractIds") List<Long> contractIds,
                                            @Param("customerIds") List<Long> customerIds,
                                            @Param("afterId") Long afterId,
                                            @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_invoice i
        LEFT JOIN (
            SELECT invoice_id, MIN(c.id) AS contract_id, COUNT(DISTINCT c.id) AS contract_count
            FROM t_invoice_item item
            JOIN t_work_record wr ON wr.id = item.work_record_id
            JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
            GROUP BY invoice_id
        ) ii ON ii.invoice_id = i.id
        WHERE i.deleted_flag = 0
          AND i.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            AND i.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
          <if test="contractIds != null">
            AND EXISTS (
              SELECT 1
              FROM t_invoice_item scoped_item
              JOIN t_work_record scoped_wr ON scoped_wr.id = scoped_item.work_record_id
              JOIN t_contract scoped_contract ON scoped_contract.id = scoped_wr.contract_id
                AND scoped_contract.deleted_flag = 0
              WHERE scoped_item.invoice_id = i.id
                AND scoped_contract.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            )
          </if>
        </script>
        """)
    long countInvoices(@Param("ids") List<Long> ids,
                       @Param("contractIds") List<Long> contractIds,
                       @Param("customerIds") List<Long> customerIds);
}
