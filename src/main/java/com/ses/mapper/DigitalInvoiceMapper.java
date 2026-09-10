package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DigitalInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DigitalInvoiceMapper extends BaseMapper<DigitalInvoice> {

    @Select("SELECT * FROM t_digital_invoice WHERE provider_message_id = #{providerMessageId} LIMIT 1 FOR UPDATE")
    DigitalInvoice selectByProviderMessageIdForUpdate(@Param("providerMessageId") String providerMessageId);

    @Select("SELECT * FROM t_digital_invoice WHERE message_id = #{messageId} "
            + "AND direction = 'RECEIVE' LIMIT 1 FOR UPDATE")
    DigitalInvoice selectInboundByMessageIdForUpdate(@Param("messageId") String messageId);
}
