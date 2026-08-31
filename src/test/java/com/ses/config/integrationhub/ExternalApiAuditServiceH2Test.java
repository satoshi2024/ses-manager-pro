package com.ses.config.integrationhub;

import com.ses.service.integrationhub.ExternalApiAuditRecord;
import com.ses.service.integrationhub.ExternalApiAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F2専用auditのH2/init経路と保存allow-listを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiAuditServiceH2Test {
    @Autowired
    private ExternalApiAuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void auditPersistsExactlyOneBoundedRow() {
        auditService.recordRequired(new ExternalApiAuditRecord(
                "UNAUTHENTICATED", "client-a", "client-a", 2, "key-2",
                "corr-123456789012", "GET", "/external-api/v1/projects",
                "AUTHENTICATED", "ALLOWED", "INTERSECTION_ALLOWED", "READ_ALLOWED", "ALLOWED",
                200, "SUCCESS", true));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_external_api_audit WHERE correlation_id = ?",
                Integer.class, "corr-123456789012");
        assertEquals(1, count);
        Boolean rawColumn = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'T_EXTERNAL_API_AUDIT' "
                        + "AND COLUMN_NAME IN ('RAW_TARGET', 'RAW_BODY', 'SOURCE_IP', 'SECRET_PLAIN')",
                Boolean.class);
        assertTrue(Boolean.FALSE.equals(rawColumn));
    }
}
