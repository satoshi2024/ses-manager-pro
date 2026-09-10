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
        WHERE #{legalEntityId} IS NOT NULL
          AND e.deleted_flag = 0
          AND e.legal_entity_id = #{legalEntityId}
          AND e.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="afterId != null">AND e.id &lt; #{afterId}</if>
        ORDER BY e.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectEngineers(@Param("ids") List<Long> ids,
                                             @Param("afterId") Long afterId,
                                             @Param("limit") int limit,
                                             @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_engineer e
        WHERE #{legalEntityId} IS NOT NULL
          AND e.deleted_flag = 0
          AND e.legal_entity_id = #{legalEntityId}
          AND e.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    long countEngineers(@Param("ids") List<Long> ids, @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT p.id, p.status, p.start_date AS startDate, p.end_date AS endDate, p.customer_id AS customerId
        FROM t_project p
        JOIN m_customer customer ON customer.id = p.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND p.deleted_flag = 0
          AND p.legal_entity_id = #{legalEntityId}
          AND p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            <choose>
              <when test="customerIds.size() > 0">
                AND p.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="afterId != null">AND p.id &lt; #{afterId}</if>
        ORDER BY p.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectProjects(@Param("ids") List<Long> ids,
                                            @Param("customerIds") List<Long> customerIds,
                                            @Param("afterId") Long afterId,
                                            @Param("limit") int limit,
                                            @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_project p
        JOIN m_customer customer ON customer.id = p.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND p.deleted_flag = 0
          AND p.legal_entity_id = #{legalEntityId}
          AND p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            <choose>
              <when test="customerIds.size() > 0">
                AND p.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        </script>
        """)
    long countProjects(@Param("ids") List<Long> ids,
                       @Param("customerIds") List<Long> customerIds,
                       @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT c.id, c.project_id AS projectId, c.status, c.start_date AS startDate, c.end_date AS endDate,
               c.renewal_decision AS renewalStatus
        FROM t_contract c
        JOIN t_project project ON project.id = c.project_id
          AND project.deleted_flag = 0
          AND project.legal_entity_id = #{legalEntityId}
        JOIN t_engineer engineer ON engineer.id = c.engineer_id
          AND engineer.deleted_flag = 0
          AND engineer.legal_entity_id = #{legalEntityId}
        JOIN m_customer customer ON customer.id = c.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND c.deleted_flag = 0
          AND c.legal_entity_id = #{legalEntityId}
          AND c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="projectIds != null">
            <choose>
              <when test="projectIds.size() > 0">
                AND c.project_id IN <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="afterId != null">AND c.id &lt; #{afterId}</if>
        ORDER BY c.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ExternalApiReadRow> selectContracts(@Param("ids") List<Long> ids,
                                             @Param("projectIds") List<Long> projectIds,
                                             @Param("afterId") Long afterId,
                                             @Param("limit") int limit,
                                             @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_contract c
        JOIN t_project project ON project.id = c.project_id
          AND project.deleted_flag = 0
          AND project.legal_entity_id = #{legalEntityId}
        JOIN t_engineer engineer ON engineer.id = c.engineer_id
          AND engineer.deleted_flag = 0
          AND engineer.legal_entity_id = #{legalEntityId}
        JOIN m_customer customer ON customer.id = c.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND c.deleted_flag = 0
          AND c.legal_entity_id = #{legalEntityId}
          AND c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="projectIds != null">
            <choose>
              <when test="projectIds.size() > 0">
                AND c.project_id IN <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        </script>
        """)
    long countContracts(@Param("ids") List<Long> ids,
                        @Param("projectIds") List<Long> projectIds,
                        @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT i.id, i.status, i.customer_id AS customerId, i.issued_date AS issueDate, i.due_date AS dueDate, i.paid_date AS paidDate,
               CASE WHEN ii.contract_count = 1 THEN ii.contract_id ELSE NULL END AS contractId,
               ii.contract_count AS contractCount
        FROM t_invoice i
        LEFT JOIN (
            SELECT invoice_id, c.customer_id AS contract_customer_id,
                   MIN(c.id) AS contract_id, COUNT(DISTINCT c.id) AS contract_count
            FROM t_invoice_item item
            JOIN t_work_record wr ON wr.id = item.work_record_id
            JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
              AND c.legal_entity_id = #{legalEntityId}
            <if test="contractIds != null">
              <choose>
                <when test="contractIds.size() > 0">
                  AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
            </if>
            JOIN t_project project ON project.id = c.project_id
              AND project.deleted_flag = 0
              AND project.legal_entity_id = #{legalEntityId}
            JOIN t_engineer engineer ON engineer.id = c.engineer_id
              AND engineer.deleted_flag = 0
              AND engineer.legal_entity_id = #{legalEntityId}
            JOIN m_customer contract_customer ON contract_customer.id = c.customer_id
              AND contract_customer.deleted_flag = 0
              AND contract_customer.legal_entity_id = #{legalEntityId}
            GROUP BY invoice_id, c.customer_id
        ) ii ON ii.invoice_id = i.id AND ii.contract_customer_id = i.customer_id
        JOIN m_customer customer ON customer.id = i.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND i.deleted_flag = 0
          AND i.legal_entity_id = #{legalEntityId}
          AND NOT EXISTS (
            SELECT 1
            FROM t_invoice_item invalid_item
            LEFT JOIN t_work_record invalid_wr ON invalid_wr.id = invalid_item.work_record_id
            LEFT JOIN t_contract invalid_contract ON invalid_contract.id = invalid_wr.contract_id
            LEFT JOIN t_project invalid_project ON invalid_project.id = invalid_contract.project_id
            LEFT JOIN t_engineer invalid_engineer ON invalid_engineer.id = invalid_contract.engineer_id
            LEFT JOIN m_customer invalid_contract_customer ON invalid_contract_customer.id = invalid_contract.customer_id
            WHERE invalid_item.invoice_id = i.id
              AND (invalid_wr.id IS NULL
                   OR invalid_contract.id IS NULL OR invalid_contract.deleted_flag &lt;&gt; 0
                   OR invalid_contract.legal_entity_id IS NULL
                   OR invalid_contract.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_project.id IS NULL OR invalid_project.deleted_flag &lt;&gt; 0
                   OR invalid_project.legal_entity_id IS NULL
                   OR invalid_project.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_engineer.id IS NULL OR invalid_engineer.deleted_flag &lt;&gt; 0
                   OR invalid_engineer.legal_entity_id IS NULL
                   OR invalid_engineer.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_contract_customer.id IS NULL
                   OR invalid_contract_customer.deleted_flag &lt;&gt; 0
                   OR invalid_contract_customer.legal_entity_id IS NULL
                   OR invalid_contract_customer.legal_entity_id &lt;&gt; #{legalEntityId})
          )
          AND i.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            <choose>
              <when test="customerIds.size() > 0">
                AND i.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="contractIds != null">
            AND EXISTS (
              SELECT 1
              FROM t_invoice_item scoped_item
              JOIN t_work_record scoped_wr ON scoped_wr.id = scoped_item.work_record_id
              JOIN t_contract scoped_contract ON scoped_contract.id = scoped_wr.contract_id
                AND scoped_contract.deleted_flag = 0
                AND scoped_contract.legal_entity_id = #{legalEntityId}
                AND scoped_contract.customer_id = i.customer_id
              JOIN t_project scoped_project ON scoped_project.id = scoped_contract.project_id
                AND scoped_project.deleted_flag = 0
                AND scoped_project.legal_entity_id = #{legalEntityId}
              JOIN t_engineer scoped_engineer ON scoped_engineer.id = scoped_contract.engineer_id
                AND scoped_engineer.deleted_flag = 0
                AND scoped_engineer.legal_entity_id = #{legalEntityId}
              JOIN m_customer scoped_customer ON scoped_customer.id = scoped_contract.customer_id
                AND scoped_customer.deleted_flag = 0
                AND scoped_customer.legal_entity_id = #{legalEntityId}
              WHERE scoped_item.invoice_id = i.id
                <choose>
                  <when test="contractIds.size() > 0">
                    AND scoped_contract.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
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
                                            @Param("limit") int limit,
                                            @Param("legalEntityId") Long legalEntityId);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM t_invoice i
        LEFT JOIN (
            SELECT invoice_id, c.customer_id AS contract_customer_id,
                   MIN(c.id) AS contract_id, COUNT(DISTINCT c.id) AS contract_count
            FROM t_invoice_item item
            JOIN t_work_record wr ON wr.id = item.work_record_id
            JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
              AND c.legal_entity_id = #{legalEntityId}
            <if test="contractIds != null">
              <choose>
                <when test="contractIds.size() > 0">
                  AND c.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
            </if>
            JOIN t_project project ON project.id = c.project_id
              AND project.deleted_flag = 0
              AND project.legal_entity_id = #{legalEntityId}
            JOIN t_engineer engineer ON engineer.id = c.engineer_id
              AND engineer.deleted_flag = 0
              AND engineer.legal_entity_id = #{legalEntityId}
            JOIN m_customer contract_customer ON contract_customer.id = c.customer_id
              AND contract_customer.deleted_flag = 0
              AND contract_customer.legal_entity_id = #{legalEntityId}
            GROUP BY invoice_id, c.customer_id
        ) ii ON ii.invoice_id = i.id AND ii.contract_customer_id = i.customer_id
        JOIN m_customer customer ON customer.id = i.customer_id
          AND customer.deleted_flag = 0
          AND customer.legal_entity_id = #{legalEntityId}
        WHERE #{legalEntityId} IS NOT NULL
          AND i.deleted_flag = 0
          AND i.legal_entity_id = #{legalEntityId}
          AND NOT EXISTS (
            SELECT 1
            FROM t_invoice_item invalid_item
            LEFT JOIN t_work_record invalid_wr ON invalid_wr.id = invalid_item.work_record_id
            LEFT JOIN t_contract invalid_contract ON invalid_contract.id = invalid_wr.contract_id
            LEFT JOIN t_project invalid_project ON invalid_project.id = invalid_contract.project_id
            LEFT JOIN t_engineer invalid_engineer ON invalid_engineer.id = invalid_contract.engineer_id
            LEFT JOIN m_customer invalid_contract_customer ON invalid_contract_customer.id = invalid_contract.customer_id
            WHERE invalid_item.invoice_id = i.id
              AND (invalid_wr.id IS NULL
                   OR invalid_contract.id IS NULL OR invalid_contract.deleted_flag &lt;&gt; 0
                   OR invalid_contract.legal_entity_id IS NULL
                   OR invalid_contract.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_project.id IS NULL OR invalid_project.deleted_flag &lt;&gt; 0
                   OR invalid_project.legal_entity_id IS NULL
                   OR invalid_project.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_engineer.id IS NULL OR invalid_engineer.deleted_flag &lt;&gt; 0
                   OR invalid_engineer.legal_entity_id IS NULL
                   OR invalid_engineer.legal_entity_id &lt;&gt; #{legalEntityId}
                   OR invalid_contract_customer.id IS NULL
                   OR invalid_contract_customer.deleted_flag &lt;&gt; 0
                   OR invalid_contract_customer.legal_entity_id IS NULL
                   OR invalid_contract_customer.legal_entity_id &lt;&gt; #{legalEntityId})
          )
          AND i.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          <if test="customerIds != null">
            <choose>
              <when test="customerIds.size() > 0">
                AND i.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="contractIds != null">
            AND EXISTS (
              SELECT 1
              FROM t_invoice_item scoped_item
              JOIN t_work_record scoped_wr ON scoped_wr.id = scoped_item.work_record_id
              JOIN t_contract scoped_contract ON scoped_contract.id = scoped_wr.contract_id
                AND scoped_contract.deleted_flag = 0
                AND scoped_contract.legal_entity_id = #{legalEntityId}
                AND scoped_contract.customer_id = i.customer_id
              JOIN t_project scoped_project ON scoped_project.id = scoped_contract.project_id
                AND scoped_project.deleted_flag = 0
                AND scoped_project.legal_entity_id = #{legalEntityId}
              JOIN t_engineer scoped_engineer ON scoped_engineer.id = scoped_contract.engineer_id
                AND scoped_engineer.deleted_flag = 0
                AND scoped_engineer.legal_entity_id = #{legalEntityId}
              JOIN m_customer scoped_customer ON scoped_customer.id = scoped_contract.customer_id
                AND scoped_customer.deleted_flag = 0
                AND scoped_customer.legal_entity_id = #{legalEntityId}
              WHERE scoped_item.invoice_id = i.id
                <choose>
                  <when test="contractIds.size() > 0">
                    AND scoped_contract.id IN <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
            )
          </if>
        </script>
        """)
    long countInvoices(@Param("ids") List<Long> ids,
                       @Param("contractIds") List<Long> contractIds,
                       @Param("customerIds") List<Long> customerIds,
                       @Param("legalEntityId") Long legalEntityId);
}
