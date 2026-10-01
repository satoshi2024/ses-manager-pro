package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.PeppolParticipant;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeppolParticipantServiceTest {

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpTenantScope() {
        TenantTestSecurity.bindAs("default", "管理者");
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
    }

    @AfterEach
    void clearTenantScope() {
        TenantTestSecurity.clear();
    }

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
