package com.ses.dto.integrationhub;

/** replay操作結果のadmin UI allow-list DTO。 */
public record InboundEventReplayResponse(String replayReference, int generation, String status, String resultCode) {
}
