package com.ses.service.certification;

import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.EngineerCertification;

import java.time.LocalDate;

public interface EngineerCertificationService {

    EngineerCertificationViewDto submitApplication(Long engineerId, Long certificationId, LocalDate acquiredOn,
                                                   LocalDate expiresOn, String certificateNumberPlaintext,
                                                   Long actorUserId, boolean canViewFullNumber);

    EngineerCertification getEntity(Long id);
}
