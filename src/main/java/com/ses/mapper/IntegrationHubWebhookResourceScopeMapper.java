package com.ses.mapper;

import com.ses.dto.integrationhub.ExternalApiResourceMembership;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** replay時に一次resourceと現在の親relation・論理削除状態を再評価するmapper。 */
@Mapper
public interface IntegrationHubWebhookResourceScopeMapper {
    /** enqueue後の移管も検出する。同一transaction内でも一次cacheを破棄し、全参照先の現在ownershipを取得する。 */
    @org.apache.ibatis.annotations.Options(useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>
        <choose>
          <when test="resourceType == 'engineer-availability'">
            SELECT COUNT(*) = 1 FROM t_engineer e
            WHERE e.id = #{id} AND e.deleted_flag = 0 AND e.tenant_id = #{tenantId}
              AND e.legal_entity_id = #{legalEntityId}
          </when>
          <when test="resourceType == 'project'">
            SELECT COUNT(*) = 1 FROM t_project p
            JOIN m_customer customer ON customer.id = p.customer_id AND customer.deleted_flag = 0
              AND customer.tenant_id = #{tenantId} AND customer.legal_entity_id = #{legalEntityId}
            WHERE p.id = #{id} AND p.deleted_flag = 0 AND p.legal_entity_id = #{legalEntityId}
          </when>
          <when test="resourceType == 'contract-status'">
            SELECT COUNT(*) = 1 FROM t_contract c
            JOIN m_customer customer ON customer.id = c.customer_id AND customer.deleted_flag = 0
              AND customer.tenant_id = #{tenantId} AND customer.legal_entity_id = #{legalEntityId}
            JOIN t_project p ON p.id = c.project_id AND p.customer_id = c.customer_id AND p.deleted_flag = 0
              AND p.legal_entity_id = #{legalEntityId}
            JOIN t_engineer e ON e.id = c.engineer_id AND e.deleted_flag = 0
              AND e.tenant_id = #{tenantId} AND e.legal_entity_id = #{legalEntityId}
            WHERE c.id = #{id} AND c.deleted_flag = 0 AND c.legal_entity_id = #{legalEntityId}
          </when>
          <when test="resourceType == 'invoice-status'">
            SELECT COUNT(*) = 1 FROM t_invoice i
            JOIN m_customer customer ON customer.id = i.customer_id AND customer.deleted_flag = 0
              AND customer.tenant_id = #{tenantId} AND customer.legal_entity_id = #{legalEntityId}
            WHERE i.id = #{id} AND i.deleted_flag = 0 AND i.legal_entity_id = #{legalEntityId}
              AND NOT EXISTS (
                SELECT 1 FROM t_invoice_item item
                LEFT JOIN t_work_record wr ON wr.id = item.work_record_id
                LEFT JOIN t_contract c ON c.id = wr.contract_id
                LEFT JOIN t_project p ON p.id = c.project_id
                LEFT JOIN t_engineer e ON e.id = c.engineer_id
                WHERE item.invoice_id = i.id AND (
                    wr.id IS NULL OR c.id IS NULL OR c.deleted_flag != 0
                    OR c.customer_id != i.customer_id OR c.legal_entity_id IS NULL OR c.legal_entity_id != #{legalEntityId}
                    OR p.id IS NULL OR p.deleted_flag != 0 OR p.customer_id != i.customer_id
                    OR p.legal_entity_id IS NULL OR p.legal_entity_id != #{legalEntityId}
                    OR e.id IS NULL OR e.deleted_flag != 0 OR e.tenant_id IS NULL OR e.tenant_id != #{tenantId}
                    OR e.legal_entity_id IS NULL OR e.legal_entity_id != #{legalEntityId}))
          </when>
          <otherwise>SELECT FALSE</otherwise>
        </choose>
        </script>
        """)
    boolean isCurrentOwnership(@Param("resourceType") String resourceType, @Param("id") Long id,
                               @Param("tenantId") String tenantId, @Param("legalEntityId") Long legalEntityId);

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
                                              AND p.customer_id = c.customer_id
            INNER JOIN m_customer customer ON customer.id = c.customer_id AND customer.deleted_flag = 0
            WHERE c.id = #{primaryResourceId} AND c.deleted_flag = 0
          </when>
          <when test="resourceType == 'invoice-status'">
            SELECT membership.primaryResourceId, membership.customerId, membership.projectId, membership.contractId
            FROM (
              SELECT DISTINCT i.id AS primaryResourceId, i.customer_id AS customerId,
                              c.project_id AS projectId, c.id AS contractId
              FROM t_invoice i
              INNER JOIN m_customer customer ON customer.id = i.customer_id AND customer.deleted_flag = 0
              INNER JOIN t_invoice_item item ON item.invoice_id = i.id
              INNER JOIN t_work_record wr ON wr.id = item.work_record_id
              INNER JOIN t_contract c ON c.id = wr.contract_id AND c.deleted_flag = 0
                                                     AND c.customer_id = i.customer_id
              INNER JOIN t_project p ON p.id = c.project_id AND p.deleted_flag = 0
                                                AND p.customer_id = c.customer_id
              WHERE i.id = #{primaryResourceId} AND i.deleted_flag = 0
            ) membership
          </when>
          <otherwise>
            SELECT NULL AS primaryResourceId, NULL AS customerId, NULL AS projectId, NULL AS contractId
            WHERE 1 = 0
          </otherwise>
        </choose>
        FOR UPDATE
        </script>
        """)
    List<ExternalApiResourceMembership> selectCurrentMemberships(
            @Param("resourceType") String resourceType,
            @Param("primaryResourceId") Long primaryResourceId);
}
