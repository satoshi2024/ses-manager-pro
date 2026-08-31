package com.ses.dto.integrationhub;

/** A1の短期read snapshotから取得する、内部DB IDとallow-list DTO JSONの組。 */
public record ExternalApiSnapshotItem(Long resourceId, String payloadJson) {
}
