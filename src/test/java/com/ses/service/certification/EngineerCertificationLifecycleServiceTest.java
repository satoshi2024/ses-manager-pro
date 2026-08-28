package com.ses.service.certification;

import com.ses.entity.Certification;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineerCertificationLifecycleServiceTest {

    @Mock private EngineerCertificationMapper recordMapper;
    @Mock private CertificationMapper certificationMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private CertificationNumberCryptoService cryptoService;
    @Mock private CertificationEventMapper eventMapper;
    @Mock private CertificationEvidenceValidator evidenceValidator;

    private EngineerCertificationService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @BeforeEach
    void setUp() {
        service = new EngineerCertificationServiceImpl(recordMapper, certificationMapper, engineerMapper,
                cryptoService, eventMapper, evidenceValidator, clock);
    }

    @Test
    void verify_cancel_correct_renewは状態とeventを更新し訂正はCORRECTEDにならない() {
        EngineerCertification record = activeRecord();
        record.setRecordState(CertificationRecordStates.SUBMITTED);
        record.setCurrentFlag(0);
        record.setCurrentHolderKey(null);
        when(recordMapper.selectByIdForUpdate(1L)).thenReturn(record);
        when(recordMapper.updateLifecycleCas(anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(1);
        when(recordMapper.countNonTerminalAcquisition(any(), anyLong(), anyLong(), any(), any())).thenReturn(0L);

        EngineerCertification verified = service.verify(1L, 0, 7L, null, null, null);
        assertEquals(CertificationRecordStates.ACTIVE, verified.getRecordState());
        assertEquals(1, verified.getCurrentFlag());
        assertEquals(1, verified.getVersion());

        EngineerCertification corrected = service.correct(1L, 1, LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 12, 31), 7L, "日付の訂正");
        assertEquals(CertificationRecordStates.ACTIVE, corrected.getRecordState());
        verify(eventMapper, org.mockito.Mockito.atLeast(2)).insertEvent(any());
    }

    @Test
    void cancelは理由必須でcurrentを解除する() {
        EngineerCertification record = activeRecord();
        when(recordMapper.selectByIdForUpdate(1L)).thenReturn(record);
        when(recordMapper.updateLifecycleCas(anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(1);

        EngineerCertification cancelled = service.cancel(1L, 0, 7L, "本人申請取消");
        assertEquals(CertificationRecordStates.CANCELLED, cancelled.getRecordState());
        assertEquals(0, cancelled.getCurrentFlag());
        assertEquals(null, cancelled.getCurrentHolderKey());
    }

    @Test
    void version不一致は更新せず409() {
        EngineerCertification record = activeRecord();
        record.setVersion(3);
        when(recordMapper.selectByIdForUpdate(1L)).thenReturn(record);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.cancel(1L, 2, 7L, "取消"));
        verify(recordMapper, never()).updateLifecycleCas(anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void renewは旧recordをsupersededにして同一continuityの新currentを作る() {
        EngineerCertification old = activeRecord();
        when(recordMapper.selectByIdForUpdate(1L)).thenReturn(old);
        when(recordMapper.updateLifecycleCas(anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(1);
        when(recordMapper.countNonTerminalAcquisition(any(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        doReturn(1).when(recordMapper).insert(any(EngineerCertification.class));

        EngineerCertification renewed = service.renew(1L, 0, LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31), 7L, "更新");
        assertEquals(CertificationRecordStates.ACTIVE, renewed.getRecordState());
        assertEquals(old.getContinuityGroupId(), renewed.getContinuityGroupId());
        assertEquals(1, renewed.getCurrentFlag());
        assertEquals(old.getContinuityGroupId(), renewed.getCurrentHolderKey());
        assertEquals(CertificationRecordStates.SUPERSEDED, old.getRecordState());
    }

    @Test
    void 重複取得はcancel済み以外を拒否する() {
        Certification master = new Certification();
        master.setId(2L);
        master.setTenantId("default");
        master.setActiveFlag(1);
        master.setRuleVersion(1);
        master.setDisplayName("資格");
        when(engineerMapper.selectById(20L)).thenReturn(new com.ses.entity.Engineer());
        when(certificationMapper.selectById(2L)).thenReturn(master);
        when(recordMapper.countNonTerminalAcquisition(eq("default"), eq(20L), eq(2L), any(), eq(null)))
                .thenReturn(1L);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.submitApplication(20L, 2L, LocalDate.of(2026, 1, 1), null,
                        null, 7L, false));
        verify(recordMapper, never()).insert(any(EngineerCertification.class));
    }

    private EngineerCertification activeRecord() {
        EngineerCertification record = new EngineerCertification();
        record.setId(1L);
        record.setTenantId("default");
        record.setEngineerId(20L);
        record.setCertificationId(2L);
        record.setContinuityGroupId(100L);
        record.setAcquiredOn(LocalDate.of(2026, 1, 1));
        record.setExpiresOn(LocalDate.of(2026, 12, 31));
        record.setExpiryRuleVersion(1);
        record.setRecordState(CertificationRecordStates.ACTIVE);
        record.setCurrentFlag(1);
        record.setCurrentHolderKey(100L);
        record.setRevision(1);
        record.setVersion(0);
        return record;
    }
}
