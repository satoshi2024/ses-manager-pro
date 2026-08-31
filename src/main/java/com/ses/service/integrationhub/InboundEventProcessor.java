package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.InboundEvent;

/**
 * inbound eventのlocal processing boundary。
 * B2では未承認のbusiness commandを追加せず、外部HTTPも呼ばない。
 */
public interface InboundEventProcessor {
    void process(InboundEvent event);
}
