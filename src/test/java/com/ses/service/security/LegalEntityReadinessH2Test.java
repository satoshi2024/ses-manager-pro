package com.ses.service.security;

import com.ses.config.LegalEntityReadinessHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 起動後に発生したNULL法人行も公開/AI境界でfail-closedになることをH2で固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegalEntityReadinessH2Test {
    private static final String COMPANY = "NF05_READINESS_H2";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LegalEntityReadinessService readinessService;

    @Test
    void 起動後に追加された未解決行はreadinessとhealthをdownにする() {
        // V2のseedは旧baseline互換のため法人NULL。テスト内で明示的に解決し、
        // 起動後に追加した1行だけを検査対象にする（トランザクションでrollback）。
        jdbcTemplate.update("UPDATE m_customer SET legal_entity_id = 1 WHERE legal_entity_id IS NULL AND deleted_flag = 0");
        jdbcTemplate.update("UPDATE t_engineer SET legal_entity_id = 1 WHERE legal_entity_id IS NULL AND deleted_flag = 0");
        jdbcTemplate.update("UPDATE t_project SET legal_entity_id = 1 WHERE legal_entity_id IS NULL AND deleted_flag = 0");
        jdbcTemplate.update("UPDATE t_contract SET legal_entity_id = 1 WHERE legal_entity_id IS NULL AND deleted_flag = 0");
        jdbcTemplate.update("UPDATE t_invoice SET legal_entity_id = 1 WHERE legal_entity_id IS NULL AND deleted_flag = 0");
        jdbcTemplate.update("DELETE FROM m_customer WHERE company_name = ?", COMPANY);
        jdbcTemplate.update("INSERT INTO m_customer (legal_entity_id, company_name, deleted_flag) VALUES (NULL, ?, 0)",
                COMPANY);

        assertThrows(com.ses.common.exception.BusinessException.class, readinessService::assertReady);
        assertEquals(Status.DOWN, new LegalEntityReadinessHealthIndicator(readinessService).health().getStatus());

        jdbcTemplate.update("UPDATE m_customer SET legal_entity_id = 1 WHERE company_name = ?", COMPANY);
        assertDoesNotThrow(readinessService::assertReady);
        assertEquals(Status.UP, new LegalEntityReadinessHealthIndicator(readinessService).health().getStatus());
    }
}
