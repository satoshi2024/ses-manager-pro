package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.PeppolParticipant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeppolParticipantServiceTest {

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Test
    void testAssertVerified_ThrowsExceptionWhenNull() {
        assertThrows(BusinessException.class, () -> {
            peppolParticipantService.assertVerified("CUSTOMER", 999L);
        });
    }

    @Test
    void testAssertVerified_ThrowsExceptionWhenNotVerified() {
        PeppolParticipant participant = new PeppolParticipant();
        participant.setOwnerType("CUSTOMER");
        participant.setOwnerId(1L);
        participant.setSchemeId("jp.peppol");
        participant.setParticipantId("12345");
        participant.setProvider("fastaccounting");
        participant.setStatus("PENDING");
        peppolParticipantService.save(participant);

        assertThrows(BusinessException.class, () -> {
            peppolParticipantService.assertVerified("CUSTOMER", 1L);
        });
    }

    @Test
    void testAssertVerified_Success() {
        PeppolParticipant participant = new PeppolParticipant();
        participant.setOwnerType("CUSTOMER");
        participant.setOwnerId(2L);
        participant.setSchemeId("jp.peppol");
        participant.setParticipantId("123456");
        participant.setProvider("fastaccounting");
        participant.setStatus("VERIFIED");
        participant.setVerifiedAt(LocalDateTime.now());
        peppolParticipantService.save(participant);

        assertDoesNotThrow(() -> {
            peppolParticipantService.assertVerified("CUSTOMER", 2L);
        });
    }
}
