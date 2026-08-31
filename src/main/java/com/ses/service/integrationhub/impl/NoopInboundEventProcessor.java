package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.InboundEvent;
import com.ses.service.integrationhub.InboundEventProcessor;
import org.springframework.stereotype.Component;

/** B2の承認範囲ではbusiness commandを発生させない安全なdefault processor。 */
@Component
public class NoopInboundEventProcessor implements InboundEventProcessor {
    @Override
    public void process(InboundEvent event) {
        if (event == null || event.getId() == null) {
            throw new IllegalArgumentException("inbound event is missing");
        }
        // 外部HTTP、既存業務state更新、任意provider payloadの展開はここでは行わない。
    }
}
