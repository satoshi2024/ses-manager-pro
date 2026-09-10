package com.ses.service.certification;

import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.EngineerCertification;

import java.time.LocalDate;

public interface EngineerCertificationService {

    EngineerCertificationViewDto submitApplication(Long engineerId, Long certificationId, LocalDate acquiredOn,
                                                   LocalDate expiresOn, String certificateNumberPlaintext,
                                                   Long actorUserId, boolean canViewFullNumber);

    /** 却下・取消済みrecordの再申請を、元recordのversion CASと追記eventで一度だけ受け付ける。 */
    EngineerCertificationViewDto resubmit(Long recordId, Integer expectedVersion, Long actorUserId,
                                           String certificateNumberPlaintext, boolean canViewFullNumber);

    EngineerCertification getEntity(Long id);

    EngineerCertification verify(Long recordId, Integer expectedVersion, Long actorUserId,
                                 Long evidenceDocumentId, Long evidenceDocumentVersionId, String evidenceHash);

    EngineerCertification reject(Long recordId, Integer expectedVersion, Long actorUserId, String reason);

    EngineerCertification correct(Long recordId, Integer expectedVersion, LocalDate acquiredOn, LocalDate expiresOn,
                                  Long actorUserId, String reason);

    EngineerCertification cancel(Long recordId, Integer expectedVersion, Long actorUserId, String reason);

    EngineerCertification renew(Long recordId, Integer expectedVersion, LocalDate acquiredOn, LocalDate expiresOn,
                                Long actorUserId, String reason);
}
