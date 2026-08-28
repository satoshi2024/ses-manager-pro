package com.ses.dto.certification;

import com.ses.entity.EngineerCertification;

import java.time.LocalDate;

/** 管理側の資格状態操作結果。暗号化番号・証憑storage情報は返さない。 */
public record CertificationLifecycleActionView(
        Long id,
        Long engineerId,
        Long certificationId,
        LocalDate acquiredOn,
        LocalDate expiresOn,
        String recordState,
        Integer currentFlag,
        Integer version) {

    public static CertificationLifecycleActionView from(EngineerCertification record) {
        return new CertificationLifecycleActionView(record.getId(), record.getEngineerId(), record.getCertificationId(),
                record.getAcquiredOn(), record.getExpiresOn(), record.getRecordState(), record.getCurrentFlag(),
                record.getVersion());
    }
}
