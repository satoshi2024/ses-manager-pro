package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.WebhookSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** NF-05 webhook subscription mapper。 */
@Mapper
public interface WebhookSubscriptionMapper extends BaseMapper<WebhookSubscription> {
    @Select("SELECT s.* FROM m_webhook_subscription s JOIN m_api_client c ON c.client_id=s.client_id "
            + "WHERE c.tenant_id=#{tenant} AND c.status='ACTIVE' AND c.revoked_at IS NULL AND s.status='ACTIVE' "
            + "AND s.direction='OUTBOUND' AND s.event_type='resource.changed' ORDER BY s.id LIMIT 257 FOR UPDATE")
    List<WebhookSubscription> selectOutboundForTenantForUpdate(@Param("tenant") String tenant);

    @Select("SELECT * FROM m_webhook_subscription WHERE client_id = #{clientId} AND direction = 'OUTBOUND' "
            + "AND event_type = #{eventType} AND endpoint_url = #{endpoint} FOR UPDATE")
    WebhookSubscription selectOutboundForUpdate(@Param("clientId") String clientId,
                                               @Param("eventType") String eventType,
                                               @Param("endpoint") String endpoint);

    @Select("SELECT s.* FROM m_webhook_subscription s JOIN m_api_client c ON c.client_id = s.client_id "
            + "WHERE c.tenant_id = #{tenant} AND c.status = 'ACTIVE' AND c.revoked_at IS NULL "
            + "AND s.status = 'ACTIVE' AND s.direction = 'OUTBOUND' AND s.event_type = 'resource.changed' "
            + "ORDER BY s.id LIMIT 257")
    List<WebhookSubscription> selectOutboundForTenant(@Param("tenant") String tenant);
    @Select("SELECT * FROM m_webhook_subscription WHERE client_id = #{clientId} "
            + "AND status = 'ACTIVE' ORDER BY id")
    List<WebhookSubscription> selectActiveByClientId(@Param("clientId") String clientId);

    @Select("SELECT * FROM m_webhook_subscription WHERE id = #{id} AND status = 'ACTIVE' LIMIT 1")
    WebhookSubscription selectActiveById(@Param("id") Long id);

    @Select("SELECT * FROM m_webhook_subscription WHERE id = #{id} AND status = 'ACTIVE' LIMIT 1 FOR UPDATE")
    WebhookSubscription selectActiveByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM m_webhook_subscription WHERE client_id = #{clientId} "
            + "AND provider_name = #{providerName} AND direction = 'INBOUND' "
            + "AND event_type = #{eventType} AND status = 'ACTIVE' "
            + "ORDER BY id LIMIT 1")
    WebhookSubscription selectActiveInboundByClientProviderAndEvent(@Param("clientId") String clientId,
                                                                    @Param("providerName") String providerName,
                                                                    @Param("eventType") String eventType);

    @Select("SELECT * FROM m_webhook_subscription WHERE client_id = #{clientId} "
            + "AND provider_name = #{providerName} AND direction = 'INBOUND' "
            + "AND event_type = #{eventType} AND status = 'ACTIVE' "
            + "ORDER BY id LIMIT 1 FOR UPDATE")
    WebhookSubscription selectActiveInboundByClientProviderAndEventForUpdate(
            @Param("clientId") String clientId, @Param("providerName") String providerName,
            @Param("eventType") String eventType);
}
