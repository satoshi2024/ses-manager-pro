package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.entity.CertificationEvent;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class EngineerCertificationServiceImpl implements EngineerCertificationService {

    private final EngineerCertificationMapper engineerCertificationMapper;
    private final CertificationMapper certificationMapper;
    private final EngineerMapper engineerMapper;
    private final CertificationNumberCryptoService cryptoService;
    private final CertificationEventMapper eventMapper;
    private final CertificationEvidenceValidator evidenceValidator;
    private final java.time.Clock clock;

    /** F1互換の直接生成用。Springは下記の@Autowired constructorを使用する。 */
    public EngineerCertificationServiceImpl(EngineerCertificationMapper engineerCertificationMapper,
                                            CertificationMapper certificationMapper,
                                            EngineerMapper engineerMapper,
                                            CertificationNumberCryptoService cryptoService) {
        this(engineerCertificationMapper, certificationMapper, engineerMapper, cryptoService, null, null,
                java.time.Clock.system(java.time.ZoneId.of("Asia/Tokyo")));
    }

    @Autowired
    public EngineerCertificationServiceImpl(EngineerCertificationMapper engineerCertificationMapper,
                                            CertificationMapper certificationMapper,
                                            EngineerMapper engineerMapper,
                                            CertificationNumberCryptoService cryptoService,
                                            CertificationEventMapper eventMapper,
                                            CertificationEvidenceValidator evidenceValidator,
                                            java.time.Clock clock) {
        this.engineerCertificationMapper = engineerCertificationMapper;
        this.certificationMapper = certificationMapper;
        this.engineerMapper = engineerMapper;
        this.cryptoService = cryptoService;
        this.eventMapper = eventMapper;
        this.evidenceValidator = evidenceValidator;
        this.clock = clock;
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
        expiresOn = resolveExpiry(certification, acquiredOn, expiresOn);
        if (expiresOn != null && expiresOn.isBefore(acquiredOn)) {
            throw BusinessException.of(400, "certification.record.expiryBeforeAcquired");
        }

        String tenantId = StringUtils.hasText(certification.getTenantId()) ? certification.getTenantId() : "default";

        if (engineerCertificationMapper.countNonTerminalAcquisition(tenantId, engineerId, certificationId,
                acquiredOn, null) > 0) {
            throw BusinessException.of(409, "certification.record.duplicate");
        }

        EngineerCertification record = new EngineerCertification();
        record.setTenantId(tenantId);
        record.setEngineerId(engineerId);
        record.setCertificationId(certificationId);
        record.setContinuityGroupId(Math.abs(System.nanoTime()));
        record.setAcquiredOn(acquiredOn);
        record.setExpiresOn(expiresOn);
        record.setExpiryRuleVersion(certification.getRuleVersion());
        record.setRecordState(CertificationRecordStates.DRAFT);
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

        appendEvent(record, "SUBMIT", actorUserId, null, null, null, null);
        return toViewDto(record, certification.getDisplayName(), canViewFullNumber, certificateNumberPlaintext);
    }

    @Override
    public EngineerCertification getEntity(Long id) {
        return engineerCertificationMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertification verify(Long recordId, Integer expectedVersion, Long actorUserId,
                                        Long evidenceDocumentId, Long evidenceDocumentVersionId,
                                        String evidenceHash) {
        EngineerCertification record = locked(recordId, expectedVersion);
        if (!(CertificationRecordStates.DRAFT.equals(record.getRecordState())
                || CertificationRecordStates.SUBMITTED.equals(record.getRecordState())
                || CertificationRecordStates.VERIFIED.equals(record.getRecordState()))) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        requireEvidenceForVerify(evidenceDocumentId, evidenceDocumentVersionId, evidenceHash);
        if (evidenceValidator == null) {
            throw BusinessException.of(400, "certification.evidence.versionRequired");
        }
        evidenceValidator.validate(recordId, evidenceDocumentId, evidenceDocumentVersionId, evidenceHash);
        Integer revision = nextRevision(record);
        update(record, CertificationRecordStates.ACTIVE, 1, record.getContinuityGroupId(),
                record.getAcquiredOn(), record.getExpiresOn(), record.getExpiryRuleVersion(), revision, actorUserId);
        appendEvent(record, "VERIFY", actorUserId, null, evidenceDocumentId, evidenceDocumentVersionId, evidenceHash);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertification reject(Long recordId, Integer expectedVersion, Long actorUserId, String reason) {
        requireReason(reason);
        EngineerCertification record = locked(recordId, expectedVersion);
        if (CertificationRecordStates.isTerminal(record.getRecordState())
                || CertificationRecordStates.ACTIVE.equals(record.getRecordState())) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        update(record, CertificationRecordStates.REJECTED, 0, null, record.getAcquiredOn(), record.getExpiresOn(),
                record.getExpiryRuleVersion(), nextRevision(record), actorUserId);
        appendEvent(record, "REJECT", actorUserId, reason, null, null, null);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertification correct(Long recordId, Integer expectedVersion, LocalDate acquiredOn,
                                         LocalDate expiresOn, Long actorUserId, String reason) {
        requireReason(reason);
        if (acquiredOn == null) {
            throw BusinessException.of(400, "certification.record.acquiredOnRequired");
        }
        if (expiresOn != null && expiresOn.isBefore(acquiredOn)) {
            throw BusinessException.of(400, "certification.record.expiryBeforeAcquired");
        }
        EngineerCertification record = locked(recordId, expectedVersion);
        if (!CertificationRecordStates.isCorrectable(record.getRecordState())) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        if (engineerCertificationMapper.countNonTerminalAcquisition(record.getTenantId(), record.getEngineerId(),
                record.getCertificationId(), acquiredOn, recordId) > 0) {
            throw BusinessException.of(409, "certification.record.duplicate");
        }
        String state = record.getRecordState();
        update(record, state, record.getCurrentFlag(), record.getCurrentHolderKey(), acquiredOn, expiresOn,
                record.getExpiryRuleVersion(), nextRevision(record), actorUserId);
        appendEvent(record, "CORRECT", actorUserId, reason, null, null, null);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertification cancel(Long recordId, Integer expectedVersion, Long actorUserId, String reason) {
        requireReason(reason);
        EngineerCertification record = locked(recordId, expectedVersion);
        if (CertificationRecordStates.isTerminal(record.getRecordState())) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        update(record, CertificationRecordStates.CANCELLED, 0, null, record.getAcquiredOn(), record.getExpiresOn(),
                record.getExpiryRuleVersion(), nextRevision(record), actorUserId);
        appendEvent(record, "CANCEL", actorUserId, reason, null, null, null);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerCertification renew(Long recordId, Integer expectedVersion, LocalDate acquiredOn,
                                       LocalDate expiresOn, Long actorUserId, String reason) {
        requireReason(reason);
        if (acquiredOn == null || (expiresOn != null && expiresOn.isBefore(acquiredOn))) {
            throw BusinessException.of(400, "certification.record.invalidDates");
        }
        EngineerCertification old = locked(recordId, expectedVersion);
        if (!CertificationRecordStates.ACTIVE.equals(old.getRecordState())
                || !Integer.valueOf(1).equals(old.getCurrentFlag())) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        if (engineerCertificationMapper.countNonTerminalAcquisition(old.getTenantId(), old.getEngineerId(),
                old.getCertificationId(), acquiredOn, recordId) > 0) {
            throw BusinessException.of(409, "certification.record.duplicate");
        }
        int nextRevision = nextRevision(old);
        update(old, CertificationRecordStates.SUPERSEDED, 0, null, old.getAcquiredOn(), old.getExpiresOn(),
                old.getExpiryRuleVersion(), nextRevision, actorUserId);
        appendEvent(old, "RENEW", actorUserId, reason, null, null, null);

        EngineerCertification renewed = new EngineerCertification();
        renewed.setTenantId(old.getTenantId());
        renewed.setEngineerId(old.getEngineerId());
        renewed.setCertificationId(old.getCertificationId());
        renewed.setContinuityGroupId(old.getContinuityGroupId());
        renewed.setAcquiredOn(acquiredOn);
        renewed.setExpiresOn(expiresOn);
        renewed.setExpiryRuleVersion(old.getExpiryRuleVersion());
        renewed.setRecordState(CertificationRecordStates.ACTIVE);
        renewed.setCurrentFlag(1);
        renewed.setCurrentHolderKey(old.getContinuityGroupId());
        renewed.setRevision(1);
        renewed.setVersion(0);
        renewed.setCreatedBy(actorUserId);
        renewed.setUpdatedBy(actorUserId);
        engineerCertificationMapper.insert(renewed);
        appendEvent(renewed, "RENEW", actorUserId, reason, null, null, null);
        return renewed;
    }

    private EngineerCertification locked(Long recordId, Integer expectedVersion) {
        if (recordId == null) {
            throw BusinessException.of(404, "certification.record.notFound");
        }
        EngineerCertification record = engineerCertificationMapper.selectByIdForUpdate(recordId);
        if (record == null) {
            throw BusinessException.of(404, "certification.record.notFound");
        }
        if (expectedVersion != null && !expectedVersion.equals(record.getVersion())) {
            throw BusinessException.of(409, "certification.record.optimisticLock");
        }
        return record;
    }

    private void update(EngineerCertification record, String state, Integer currentFlag, Long holder,
                        LocalDate acquiredOn, LocalDate expiresOn, Integer expiryRuleVersion,
                        Integer revision, Long actorUserId) {
        Integer version = record.getVersion() == null ? 0 : record.getVersion();
        if (engineerCertificationMapper.updateLifecycleCas(record.getId(), version, state, currentFlag, holder,
                acquiredOn, expiresOn, expiryRuleVersion, revision, actorUserId) == 0) {
            throw BusinessException.of(409, "certification.record.optimisticLock");
        }
        record.setRecordState(state);
        record.setCurrentFlag(currentFlag);
        record.setCurrentHolderKey(holder);
        record.setAcquiredOn(acquiredOn);
        record.setExpiresOn(expiresOn);
        record.setExpiryRuleVersion(expiryRuleVersion);
        record.setRevision(revision);
        record.setUpdatedBy(actorUserId);
        record.setVersion(version + 1);
    }

    private void appendEvent(EngineerCertification record, String eventType, Long actorUserId, String reason,
                             Long evidenceDocumentId, Long evidenceDocumentVersionId, String evidenceHash) {
        if (eventMapper == null || record.getId() == null) {
            return;
        }
        CertificationEvent event = new CertificationEvent();
        event.setTenantId(record.getTenantId());
        event.setCertificationRecordId(record.getId());
        event.setEventType(eventType);
        event.setReason(reason);
        event.setActorUserId(actorUserId);
        event.setOccurredAt(LocalDateTime.now(clock));
        event.setEffectiveRecordState(record.getRecordState());
        event.setEffectiveAcquiredOn(record.getAcquiredOn());
        event.setEffectiveExpiresOn(record.getExpiresOn());
        event.setEvidenceDocumentId(evidenceDocumentId);
        event.setEvidenceDocumentVersionId(evidenceDocumentVersionId);
        event.setEvidenceDocumentHash(evidenceHash);
        event.setIdempotencyKey(record.getTenantId() + ":" + record.getId() + ":" + record.getRevision()
                + ":" + eventType);
        event.setCreatedAt(LocalDateTime.now(clock));
        try {
            eventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (eventMapper.selectByIdempotencyKey(record.getTenantId(), event.getIdempotencyKey()) == null) {
                throw duplicate;
            }
        }
    }

    private Integer nextRevision(EngineerCertification record) {
        return (record.getRevision() == null ? 0 : record.getRevision()) + 1;
    }

    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "certification.record.reasonRequired");
        }
    }

    private void requireEvidenceForVerify(Long evidenceDocumentId, Long evidenceDocumentVersionId, String evidenceHash) {
        if (evidenceDocumentId == null || evidenceDocumentVersionId == null
                || !StringUtils.hasText(evidenceHash)) {
            throw BusinessException.of(400, "certification.evidence.versionRequired");
        }
    }

    private LocalDate resolveExpiry(Certification certification, LocalDate acquiredOn, LocalDate requested) {
        if (requested != null || certification == null || !"FIXED_MONTHS".equals(certification.getExpiryType())
                || certification.getExpiryMonths() == null) {
            return requested;
        }
        // 月数の最終日を有効期限とする（expires_on当日は有効）。
        return acquiredOn.plusMonths(certification.getExpiryMonths()).minusDays(1);
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
                .version(record.getVersion())
                .certificateNumberMasked(canViewFullNumber && StringUtils.hasText(plaintextIfAllowed)
                        ? plaintextIfAllowed : masked)
                .canViewFullNumber(canViewFullNumber)
                .build();
    }
}
