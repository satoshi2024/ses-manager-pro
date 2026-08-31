package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiResourceMembership;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** replay時に一次resourceと現在の親relation・論理削除状態を再評価するmapper。 */
@Mapper
public interface IntegrationHubWebhookResourceScopeMapper {
    @Select("""
        <script>
        <choose>
          <when test="resourceType == 'engineer-availability'">
            SELECT e.id AS primaryResourceId, NULL AS customerId, NULL AS projectId, NULL AS contractId
            FROM t_engineer e
            WHERE e.id = #{primaryResourceId} AND e.deleted_flag = 0
          </when>
          <when test="resourceType == 'project'">
            SELECT p.id AS primaryResourceId, p.customer_id AS customerId, NULL AS projectId, NULL AS contractId
            FROM t_project p
            INNER JOIN m_customer customer ON customer.id = p.customer_id AND customer.deleted_flag = 0
            WHERE p.id = #{primaryResourceId} AND p.deleted_flag = 0
          </when>
          <when test="resourceType == 'contract-status'">
            SELECT c.id AS primaryResourceId, c.customer_id AS customerId, c.project_id AS projectId, NULL AS contractId
            FROM t_contract c
            INNER JOIN t_project p ON p.id = c.project_id AND p.deleted_flag = 0
            INNER JOIN m_customer customer ON customer.id = c.customer_id AND customer.deleted_flag = 0
            WHERE c.id = #{primaryResourceId} AND c.deleted_flag = 0
          </when>
          <when test="resourceType == 'invoice-status'">
            SELECT DISTINCT i.id AS primaryResourceId, i.customer_id AS customerId,
                            c.project_id AS projectId, c.id AS contractId
            FROM t_invoice i
            INNER JOIN m_customer customer ON customer.id = i.customer_id AND customer.deleted_flag = 0
            INNER JOIN t_invoice_item item ON item.invoice_id = i.id
            INNER JOIN t_work_record wr ON wr.id = item.work_record_id
            INNER JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
            INNER JOIN t_project p ON p.id = c.project_id AND p.deleted_flag = 0
            WHERE i.id = #{primaryResourceId} AND i.deleted_flag = 0
          </when>
          <otherwise>
            SELECT NULL AS primaryResourceId, NULL AS customerId, NULL AS projectId, NULL AS contractId
            WHERE 1 = 0
          </otherwise>
        </choose>
        </script>
        """)
    List<ExternalApiResourceMembership> selectCurrentMemberships(
            @Param("resourceType") String resourceType,
            @Param("primaryResourceId") Long primaryResourceId);
}
