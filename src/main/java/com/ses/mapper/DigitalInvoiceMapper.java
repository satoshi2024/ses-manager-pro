package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DigitalInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DigitalInvoiceMapper extends BaseMapper<DigitalInvoice> {

    @Select("SELECT * FROM t_digital_invoice WHERE provider_message_id = #{providerMessageId} "
            + "AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} LIMIT 1 FOR UPDATE")
    DigitalInvoice selectByProviderMessageIdForUpdate(
            @Param("providerMessageId") String providerMessageId,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") Long legalEntityId);

    @Select("SELECT * FROM t_digital_invoice WHERE message_id = #{messageId} "
            + "AND direction = 'RECEIVE' AND tenant_id = #{tenantId} "
            + "AND legal_entity_id = #{legalEntityId} LIMIT 1 FOR UPDATE")
    DigitalInvoice selectInboundByMessageIdForUpdate(
            @Param("messageId") String messageId,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") Long legalEntityId);
}
