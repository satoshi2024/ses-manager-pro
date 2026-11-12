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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共有H2では、NOT_SUPPORTED等でcommitする他テストが別法人の組織を残す。
 * 管理者は「DB上の全法人」から一意の法人を解決するため、別法人が1件でも残ると
 * LEGAL_ENTITY_CONTEXT_REQUIRED で失敗し、実行順序に依存して落ちていた。
 * 専用ユーザーを法人1の組織だけに所属させ、ユーザー所属から法人を解決させる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeppolParticipantServiceTest {

    /** 他テストが残しうる別法人を模したfixture（テストtransactionでrollbackされる）。 */
    private static final long OTHER_LEGAL_ENTITY_ID = 91999L;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpTenantScope() {
        String suffix = Long.toString(System.nanoTime());
        String username = "peppol-participant-scope-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO sys_user
                    (tenant_id, username, password, real_name, role, status, deleted_flag)
                VALUES ('default', ?, 'N/A', 'Peppolテスト', 'HR', 1, 0)
                """, username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ? AND deleted_flag = 0", Long.class, username);
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L, userId);
        TenantTestSecurity.bindAs(userId, username, "default", "HR");

        // 別法人が存在しても、ユーザー所属の法人だけで解決されることを毎回検証する（順序非依存）。
        jdbcTemplate.update("""
                INSERT INTO m_organization_unit
                    (tenant_id, legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES (1, ?, ?, '別法人（Peppolテスト）', '法人', ?, '有効', 0)
                """, OTHER_LEGAL_ENTITY_ID, "PEPPOL-OTHER-" + suffix, LocalDate.of(2020, 1, 1));
    }

    @AfterEach
    void clearTenantScope() {
        TenantTestSecurity.clear();
    }

    @Test
    void testAssertVerified_ThrowsExceptionWhenNull() {
        BusinessException e = assertThrows(BusinessException.class, () -> {
            peppolParticipantService.assertVerified("CUSTOMER", 999L);
        });
        // 法人コンテキスト解決の失敗（LEGAL_ENTITY_CONTEXT_REQUIRED）で通過しないよう、理由まで確認する。
        assertTrue(e.getMessage().contains("未検証"), e.getMessage());
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

        BusinessException e = assertThrows(BusinessException.class, () -> {
            peppolParticipantService.assertVerified("CUSTOMER", 1L);
        });
        assertTrue(e.getMessage().contains("未検証"), e.getMessage());
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
