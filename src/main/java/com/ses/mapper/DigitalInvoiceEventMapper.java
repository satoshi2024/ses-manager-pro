package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DigitalInvoiceEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DigitalInvoiceEventMapper extends BaseMapper<DigitalInvoiceEvent> {

    @Select("SELECT * FROM t_digital_invoice_event WHERE digital_invoice_id = #{digitalInvoiceId} "
            + "AND provider_event_id = #{providerEventId} AND tenant_id = #{tenantId} "
            + "AND legal_entity_id = #{legalEntityId} LIMIT 1 FOR UPDATE")
    DigitalInvoiceEvent selectByInvoiceIdAndProviderEventIdForUpdate(
            @Param("digitalInvoiceId") Long digitalInvoiceId,
            @Param("providerEventId") String providerEventId,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") Long legalEntityId);

    @Select("SELECT * FROM t_digital_invoice_event WHERE digital_invoice_id = #{digitalInvoiceId} "
            + "AND event_type = 'RECEIVED' AND tenant_id = #{tenantId} "
            + "AND legal_entity_id = #{legalEntityId} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    DigitalInvoiceEvent selectLatestInboundForUpdate(
            @Param("digitalInvoiceId") Long digitalInvoiceId,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") Long legalEntityId);
}
