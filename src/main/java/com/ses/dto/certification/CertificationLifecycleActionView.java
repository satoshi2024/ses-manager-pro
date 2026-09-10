package com.ses.dto.certification;

import com.ses.entity.EngineerCertification;

import java.time.LocalDate;

/** 管理側の資格状態操作結果。暗号化番号・証憑storage情報は返さない。 */
public record CertificationLifecycleActionView(
        Long id,
        String recordState,
        Integer currentFlag,
        LocalDate acquiredOn,
        LocalDate expiresOn,
        Integer revision,
        Integer version,
        String certificateNumberMasked) {

    public static CertificationLifecycleActionView from(EngineerCertification record) {
        return new CertificationLifecycleActionView(record.getId(), record.getRecordState(), record.getCurrentFlag(),
                record.getAcquiredOn(), record.getExpiresOn(), record.getRevision(), record.getVersion(),
                record.getCertificateNumberMasked());
    }
}
