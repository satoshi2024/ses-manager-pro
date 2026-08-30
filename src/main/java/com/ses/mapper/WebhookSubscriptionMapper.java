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
    @Select("SELECT * FROM m_webhook_subscription WHERE client_id = #{clientId} "
            + "AND status = 'ACTIVE' ORDER BY id")
    List<WebhookSubscription> selectActiveByClientId(@Param("clientId") String clientId);

    @Select("SELECT * FROM m_webhook_subscription WHERE id = #{id} AND status = 'ACTIVE' LIMIT 1")
    WebhookSubscription selectActiveById(@Param("id") Long id);

    @Select("SELECT * FROM m_webhook_subscription WHERE id = #{id} AND status = 'ACTIVE' LIMIT 1 FOR UPDATE")
    WebhookSubscription selectActiveByIdForUpdate(@Param("id") Long id);
}
