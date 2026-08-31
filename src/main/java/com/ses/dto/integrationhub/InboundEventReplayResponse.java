package com.ses.dto.integrationhub;

/** replay操作結果のadmin UI allow-list DTO。 */
public record InboundEventReplayResponse(Long requestId, int generation, String status, String resultCode) {
}
