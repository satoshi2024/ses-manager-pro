package com.ses.dto.integrationhub;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** admin UI専用のsafe projection。parsed snapshotはselectしない。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InboundEventAdminRow {
    private String reference;
    private String clientId;
    private String providerName;
    private String providerEventId;
    private Boolean signatureValid;
    private String status;
    private String resultCode;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private LocalDateTime retentionExpiresAt;
}
