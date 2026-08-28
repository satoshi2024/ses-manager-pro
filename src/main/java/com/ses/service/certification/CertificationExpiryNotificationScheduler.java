package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.NotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 資格期限通知の定期dispatch。候補判定はClock、母集団はlifecycle resolver、重複はDB uniqueへ委譲する。 */
@Service
public class CertificationExpiryNotificationScheduler {

    private final EngineerCertificationMapper certificationMapper;
    private final CertificationExpiryService expiryService;
    private final CertificationNotificationPopulationResolver populationResolver;
    private final CertificationExpiryNotificationService notificationService;
    private final NotificationService genericNotificationService;
    private final Clock clock;

    public CertificationExpiryNotificationScheduler(EngineerCertificationMapper certificationMapper,
                                                    CertificationExpiryService expiryService,
                                                    CertificationNotificationPopulationResolver populationResolver,
                                                    CertificationExpiryNotificationService notificationService,
                                                    NotificationService genericNotificationService,
                                                    Clock clock) {
        this.certificationMapper = certificationMapper;
        this.expiryService = expiryService;
        this.populationResolver = populationResolver;
        this.notificationService = notificationService;
        this.genericNotificationService = genericNotificationService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "certificationExpiryNotificationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT20M")
    @Transactional(rollbackFor = Exception.class)
    public int dispatchToday() {
        return dispatch(LocalDate.now(clock));
    }

    @Transactional(rollbackFor = Exception.class)
    public int dispatch(LocalDate asOf) {
        if (asOf == null) {
            return 0;
        }
        List<EngineerCertification> records = certificationMapper.selectList(new LambdaQueryWrapper<EngineerCertification>()
                .eq(EngineerCertification::getRecordState, CertificationRecordStates.ACTIVE)
                .eq(EngineerCertification::getCurrentFlag, 1)
                .isNotNull(EngineerCertification::getExpiresOn));
        int attempted = 0;
        for (EngineerCertification record : records) {
            CertificationNotificationPopulationResolver.Population population =
                    populationResolver.resolve(record.getEngineerId(), asOf);
            for (Long recipientId : population.recipientUserIds()) {
                if (population.reinstatement()) {
                    String key = "CERT_REINSTATEMENT:" + record.getId() + ":" + asOf + ":" + recipientId;
                    genericNotificationService.publishToUser(recipientId, "CERTIFICATION_REINSTATEMENT",
                            "復職後の資格期限確認", "復職した要員の資格期限を確認してください。",
                            "/engineers/" + record.getEngineerId() + "/certifications/" + record.getId(), key,
                            "engineer");
                    attempted++;
                } else if (notificationService.publishIfDue(record, asOf, recipientId)) {
                    attempted++;
                }
            }
        }
        return attempted;
    }
}
