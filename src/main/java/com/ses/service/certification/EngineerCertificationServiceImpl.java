package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

@Service
public class EngineerCertificationServiceImpl implements EngineerCertificationService {

    private static final String STATE_DRAFT = "DRAFT";

    private final EngineerCertificationMapper engineerCertificationMapper;
    private final CertificationMapper certificationMapper;
    private final EngineerMapper engineerMapper;
    private final CertificationNumberCryptoService cryptoService;

    public EngineerCertificationServiceImpl(EngineerCertificationMapper engineerCertificationMapper,
                                            CertificationMapper certificationMapper,
                                            EngineerMapper engineerMapper,
                                            CertificationNumberCryptoService cryptoService) {
        this.engineerCertificationMapper = engineerCertificationMapper;
        this.certificationMapper = certificationMapper;
        this.engineerMapper = engineerMapper;
        this.cryptoService = cryptoService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertificationViewDto submitApplication(Long engineerId, Long certificationId, LocalDate acquiredOn,
                                                          LocalDate expiresOn, String certificateNumberPlaintext,
                                                          Long actorUserId, boolean canViewFullNumber) {
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.engineer.notFound");
        }
        Certification certification = certificationMapper.selectById(certificationId);
        if (certification == null || certification.getActiveFlag() == null || certification.getActiveFlag() != 1) {
            throw BusinessException.of(404, "certification.master.notFound");
        }
        if (acquiredOn == null) {
            throw BusinessException.of(400, "certification.record.acquiredOnRequired");
        }

        String tenantId = StringUtils.hasText(certification.getTenantId()) ? certification.getTenantId() : "default";

        EngineerCertification record = new EngineerCertification();
        record.setTenantId(tenantId);
        record.setEngineerId(engineerId);
        record.setCertificationId(certificationId);
        record.setContinuityGroupId(System.nanoTime());
        record.setAcquiredOn(acquiredOn);
        record.setExpiresOn(expiresOn);
        record.setExpiryRuleVersion(certification.getRuleVersion());
        record.setRecordState(STATE_DRAFT);
        record.setCurrentFlag(0);
        record.setCurrentHolderKey(null);
        record.setRevision(1);
        record.setCreatedBy(actorUserId);
        record.setUpdatedBy(actorUserId);
        engineerCertificationMapper.insert(record);

        if (StringUtils.hasText(certificateNumberPlaintext)) {
            CertificationNumberCryptoService.EncryptedCertificationNumber encrypted =
                    cryptoService.encrypt(tenantId, record.getId(), certificateNumberPlaintext);
            record.setCertificateNumberEncrypted(encrypted.encrypted());
            record.setCertificateNumberKeyVersion(encrypted.keyVersion());
            record.setCertificateNumberCipherFormat(encrypted.cipherFormat());
            record.setCertificateNumberMasked(encrypted.masked());
            engineerCertificationMapper.updateById(record);
        }

        return toViewDto(record, certification.getDisplayName(), canViewFullNumber, certificateNumberPlaintext);
    }

    @Override
    public EngineerCertification getEntity(Long id) {
        return engineerCertificationMapper.selectById(id);
    }

    private EngineerCertificationViewDto toViewDto(EngineerCertification record, String displayName,
                                                   boolean canViewFullNumber, String plaintextIfAllowed) {
        String masked = record.getCertificateNumberMasked();
        return EngineerCertificationViewDto.builder()
                .id(record.getId())
                .engineerId(record.getEngineerId())
                .certificationId(record.getCertificationId())
                .certificationDisplayName(displayName)
                .acquiredOn(record.getAcquiredOn())
                .expiresOn(record.getExpiresOn())
                .recordState(record.getRecordState())
                .currentFlag(record.getCurrentFlag())
                .certificateNumberMasked(canViewFullNumber && StringUtils.hasText(plaintextIfAllowed)
                        ? plaintextIfAllowed : masked)
                .canViewFullNumber(canViewFullNumber)
                .build();
    }
}
