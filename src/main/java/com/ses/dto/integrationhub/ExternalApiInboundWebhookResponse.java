package com.ses.dto.integrationhub;

/** inbound webhookの外部response allow-list。内部entity/id、raw bodyは含めない。 */
public record ExternalApiInboundWebhookResponse(
        String status,
        boolean duplicate,
        boolean conflict,
        String resultCode) {
}
