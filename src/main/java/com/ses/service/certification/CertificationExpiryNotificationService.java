package com.ses.service.certification;

import com.ses.dto.certification.CertificationExpiryCandidate;
import com.ses.entity.EngineerCertification;
import com.ses.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.Clock;

/** 期限通知の発行境界。重複排除はNotificationServiceとDB uniqueへ委譲する。 */
@Service
public class CertificationExpiryNotificationService {

    private final CertificationExpiryService certificationExpiryService;
    private final NotificationService notificationService;
    private final Clock clock;

    public CertificationExpiryNotificationService(CertificationExpiryService certificationExpiryService,
                                                  NotificationService notificationService) {
        this(certificationExpiryService, notificationService,
                Clock.system(java.time.ZoneId.of("Asia/Tokyo")));
    }

    @Autowired
    public CertificationExpiryNotificationService(CertificationExpiryService certificationExpiryService,
                                                  NotificationService notificationService, Clock clock) {
        this.certificationExpiryService = certificationExpiryService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public boolean publishIfDue(EngineerCertification record, Long recipientUserId) {
        return publishIfDue(record, LocalDate.now(clock), recipientUserId);
    }

    public boolean publishIfDue(EngineerCertification record, LocalDate asOf, Long recipientUserId) {
        CertificationExpiryCandidate candidate = certificationExpiryService.evaluate(record, asOf, recipientUserId);
        if (candidate == null) {
            return false;
        }
        notificationService.publishToUser(recipientUserId, "CERTIFICATION_EXPIRY", "資格の有効期限が近づいています",
                "資格record " + candidate.recordId() + " の有効期限まで残り "
                        + candidate.thresholdDays() + " 日です。",
                "/engineers/" + candidate.engineerId() + "/certifications/" + candidate.recordId(),
                candidate.semanticKey(), "engineer");
        return true;
    }
}
