package com.ses.service.certification;

import com.ses.entity.EngineerCertification;
import com.ses.mapper.EngineerCertificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationExpiryServiceTest {

    @Mock
    private EngineerCertificationMapper mapper;

    @Test
    void 90_60_30当日だけ候補になり前後日はならない() {
        CertificationExpiryService service = new CertificationExpiryServiceImpl(mapper);
        LocalDate today = LocalDate.of(2026, 8, 28);
        EngineerCertification record = active(10L, today.plusDays(90));

        assertEquals(90, service.evaluate(record, today, 99L).thresholdDays());
        assertNull(service.evaluate(record, today.minusDays(1), 99L));
        assertNull(service.evaluate(record, today.plusDays(1), 99L));

        record.setExpiresOn(today.plusDays(60));
        assertEquals(60, service.evaluate(record, today, 99L).thresholdDays());
        record.setExpiresOn(today.plusDays(30));
        assertEquals(30, service.evaluate(record, today, 99L).thresholdDays());
    }

    @Test
    void expires_on当日は有効で翌日からexpired() {
        CertificationExpiryService service = new CertificationExpiryServiceImpl(mapper);
        LocalDate expiry = LocalDate.of(2026, 11, 26);
        EngineerCertification record = active(10L, expiry);

        assertEquals("ACTIVE", CertificationRecordStates.effectiveState(record, expiry));
        assertEquals("EXPIRED", CertificationRecordStates.effectiveState(record, expiry.plusDays(1)));
    }

    @Test
    void semanticKeyはrevisionを含まず期限変更時だけ変わる() {
        CertificationExpiryService service = new CertificationExpiryServiceImpl(mapper);
        LocalDate today = LocalDate.of(2026, 8, 28);
        EngineerCertification record = active(10L, today.plusDays(90));
        record.setRevision(1);
        String first = service.evaluate(record, today, 99L).semanticKey();
        record.setRevision(8);
        String correctedWithoutDateChange = service.evaluate(record, today, 99L).semanticKey();
        assertEquals(first, correctedWithoutDateChange);

        record.setExpiresOn(today.plusDays(60));
        assertNotNull(service.evaluate(record, today, 99L));
        assertEquals("CERT_EXPIRY:10:2026-10-27:60:99",
                service.evaluate(record, today, 99L).semanticKey());
    }

    @Test
    void recipient未解決と非activeは候補を作らない() {
        CertificationExpiryService service = new CertificationExpiryServiceImpl(mapper);
        LocalDate today = LocalDate.of(2026, 8, 28);
        EngineerCertification record = active(10L, today.plusDays(90));
        assertNull(service.evaluate(record, today, null));
        record.setRecordState(CertificationRecordStates.CANCELLED);
        assertNull(service.evaluate(record, today, 99L));
    }

    @Test
    void findCandidatesはactive_currentだけを読む() {
        CertificationExpiryService service = new CertificationExpiryServiceImpl(mapper);
        LocalDate today = LocalDate.of(2026, 8, 28);
        when(mapper.selectList(any())).thenReturn(List.of(active(10L, today.plusDays(90))));
        assertEquals(1, service.findCandidates(today, 99L).size());
    }

    private EngineerCertification active(Long id, LocalDate expiry) {
        EngineerCertification record = new EngineerCertification();
        record.setId(id);
        record.setEngineerId(20L);
        record.setExpiresOn(expiry);
        record.setRecordState(CertificationRecordStates.ACTIVE);
        record.setCurrentFlag(1);
        return record;
    }
}
