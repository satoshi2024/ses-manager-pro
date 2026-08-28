package com.ses.dto.certification;

import java.time.LocalDate;

/**
 * 資格期限通知の判定結果。通知文言やrecord revisionを含めず、
 * semantic keyに必要な事実だけを保持する。
 */
public record CertificationExpiryCandidate(
        Long recordId,
        Long engineerId,
        LocalDate expiresOn,
        int thresholdDays,
        Long recipientUserId,
        String semanticKey) {

    public CertificationExpiryCandidate {
        if (recordId == null || expiresOn == null || recipientUserId == null) {
            throw new IllegalArgumentException("期限通知候補のrecord、期限、recipientは必須です");
        }
        if (thresholdDays != 90 && thresholdDays != 60 && thresholdDays != 30) {
            throw new IllegalArgumentException("期限通知の閾値は90/60/30だけです");
        }
    }
}
