package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.InboundEvent;

import java.time.LocalDateTime;

/** NF-05 inbound replay/claim persistence service。raw bytesは受け取らずhashのみ保存する。 */
public interface InboundEventService {
    Receipt recordReceived(String clientId, String providerName, String providerEventId, String rawBodyHash,
                           LocalDateTime signedTimestamp, ExternalDtoSnapshot parsedFieldsSnapshot,
                           boolean signatureValid, LocalDateTime receivedAt);

    InboundEvent claim(Long id, String leaseToken, LocalDateTime now, LocalDateTime leaseExpiresAt);

    boolean complete(Long id, Integer version, String leaseToken, String status, String resultCode,
                     LocalDateTime terminalAt);

    int recoverExpiredLeases(LocalDateTime now);

    record Receipt(InboundEvent event, boolean duplicate, boolean conflict, boolean inProgress) {
        public Receipt(InboundEvent event, boolean duplicate, boolean conflict) {
            this(event, duplicate, conflict, false);
        }
    }
}
