package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final AccountingTenantInventoryProperties tenantInventory;
    private final AccountingTimezoneResolver timezoneResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public CertificationExpiryNotificationScheduler(EngineerCertificationMapper certificationMapper,
                                                    CertificationExpiryService expiryService,
                                                    CertificationNotificationPopulationResolver populationResolver,
                                                    CertificationExpiryNotificationService notificationService,
                                                    NotificationService genericNotificationService,
                                                    Clock clock,
                                                    AccountingTenantInventoryProperties tenantInventory,
                                                    AccountingTimezoneResolver timezoneResolver) {
        this.certificationMapper = certificationMapper;
        this.expiryService = expiryService;
        this.populationResolver = populationResolver;
        this.notificationService = notificationService;
        this.genericNotificationService = genericNotificationService;
        this.clock = clock;
        this.tenantInventory = tenantInventory;
        this.timezoneResolver = timezoneResolver;
    }

    @Scheduled(cron = "0 15 3 * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "certificationExpiryNotificationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT20M")
    public int dispatchToday() {
        return dispatch(null);
    }

    public int dispatch(LocalDate asOf) {
        int attempted = 0;
        for (String tenantId : tenantInventory.normalizedIds()) {
            ZoneId zone = timezoneResolver.resolve(tenantId);
            LocalDate tenantDate = asOf == null ? LocalDate.now(clock.withZone(zone)) : asOf;
            attempted += AccountingTenantContextHolder.runWithTenant(tenantId, zone,
                    () -> dispatchTenant(tenantId, tenantDate));
        }
        return attempted;
    }

    private int dispatchTenant(String tenantId, LocalDate asOf) {
        List<EngineerCertification> records = certificationMapper.selectList(new LambdaQueryWrapper<EngineerCertification>()
                .eq(EngineerCertification::getTenantId, tenantId)
                .eq(EngineerCertification::getRecordState, CertificationRecordStates.ACTIVE)
                .eq(EngineerCertification::getCurrentFlag, 1)
                .isNotNull(EngineerCertification::getExpiresOn));
        int attempted = 0;
        for (EngineerCertification record : records) {
            CertificationNotificationPopulationResolver.Population population =
                    populationResolver.resolve(tenantId, record.getEngineerId(), asOf);
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
