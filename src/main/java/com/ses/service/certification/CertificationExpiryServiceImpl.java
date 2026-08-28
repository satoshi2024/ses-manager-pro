package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.certification.CertificationExpiryCandidate;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 期限日をLocalDateで扱う判定service。
 * expires_on当日は有効であり、通知は残日数がちょうど90/60/30日のみ発行する。
 */
@Service
public class CertificationExpiryServiceImpl implements CertificationExpiryService {

    private final EngineerCertificationMapper engineerCertificationMapper;
    private final Clock clock;

    public CertificationExpiryServiceImpl(EngineerCertificationMapper engineerCertificationMapper) {
        this(engineerCertificationMapper, Clock.system(java.time.ZoneId.of("Asia/Tokyo")));
    }

    @Autowired
    public CertificationExpiryServiceImpl(EngineerCertificationMapper engineerCertificationMapper, Clock clock) {
        this.engineerCertificationMapper = engineerCertificationMapper;
        this.clock = clock;
    }

    @Override
    public List<CertificationExpiryCandidate> findCandidates(Long recipientUserId) {
        return findCandidates(LocalDate.now(clock), recipientUserId);
    }

    @Override
    public List<CertificationExpiryCandidate> findCandidates(LocalDate asOf, Long recipientUserId) {
        if (asOf == null || recipientUserId == null) {
            return List.of();
        }
        List<EngineerCertification> records = engineerCertificationMapper.selectList(
                new LambdaQueryWrapper<EngineerCertification>()
                        .eq(EngineerCertification::getRecordState, CertificationRecordStates.ACTIVE)
                        .eq(EngineerCertification::getCurrentFlag, 1)
                        .isNotNull(EngineerCertification::getExpiresOn));
        return records.stream()
                .map(record -> evaluate(record, asOf, recipientUserId))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public CertificationExpiryCandidate evaluate(EngineerCertification record, LocalDate asOf,
                                                  Long recipientUserId) {
        if (record == null || asOf == null || recipientUserId == null
                || !CertificationRecordStates.ACTIVE.equals(record.getRecordState())
                || !Integer.valueOf(1).equals(record.getCurrentFlag())
                || record.getExpiresOn() == null
                || asOf.isAfter(record.getExpiresOn())) {
            return null;
        }
        long remaining = ChronoUnit.DAYS.between(asOf, record.getExpiresOn());
        if (remaining != 90 && remaining != 60 && remaining != 30) {
            return null;
        }
        int threshold = Math.toIntExact(remaining);
        String semanticKey = "CERT_EXPIRY:" + record.getId() + ":" + record.getExpiresOn()
                + ":" + threshold + ":" + recipientUserId;
        return new CertificationExpiryCandidate(record.getId(), record.getEngineerId(), record.getExpiresOn(),
                threshold, recipientUserId, semanticKey);
    }
}
