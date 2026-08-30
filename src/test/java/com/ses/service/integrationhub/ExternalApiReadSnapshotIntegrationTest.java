package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.dto.integrationhub.ExternalApiListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A1 cursorが初回の可視membershipとallow-list DTO値を固定することを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiReadSnapshotIntegrationTest {
    private static final long CUSTOMER_A = 9100101L;
    private static final long CUSTOMER_B = 9100102L;
    private static final long PROJECT_A = 9100001L;
    private static final long PROJECT_B = 9100002L;
    private static final long PROJECT_INSERTED_AFTER_FIRST_PAGE = 9100003L;

    private static final ExternalApiPrincipal PRINCIPAL = new ExternalApiPrincipal(
            "snapshot-client", 9100099L, "tenant-snapshot", 91L,
            "{\"projectIds\":[\"9100001\",\"9100002\"],\"customerIds\":[\"9100101\"]}",
            1, "snapshot-key", "STANDARD");
    private static final ExternalApiEffectiveScope SCOPE = new ExternalApiEffectiveScope(
            "tenant-snapshot", 91L, Map.of(
                    "tenantIds", Set.of("tenant-snapshot"),
                    "legalEntityIds", Set.of("91"),
                    "projectIds", Set.of("9100001", "9100002"),
                    "customerIds", Set.of("9100101")));

    @Autowired
    private ExternalApiReadService service;

    @Autowired
    private ExternalApiPublicIdCodec publicIdCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cursorKeepsInitialMembershipAndPublicValuesAcrossInsertUpdateDeleteAndReparent() {
        insertFixture();

        ExternalApiListResponse<ExternalApiProject> first = service.listProjects(PRINCIPAL, SCOPE, 1, null);

        assertEquals(1, first.items().size());
        assertEquals(publicIdCodec.encode(PRINCIPAL, "project", PROJECT_B), first.items().get(0).publicProjectId());
        assertNotNull(first.nextCursor());

        jdbcTemplate.update("UPDATE t_project SET status = ?, customer_id = ?, deleted_flag = 1 WHERE id = ?",
                "クローズ", CUSTOMER_B, PROJECT_A);
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_INSERTED_AFTER_FIRST_PAGE, "inserted-after-first-page", CUSTOMER_A, "募集中",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 31));

        ExternalApiListResponse<ExternalApiProject> second = service.listProjects(
                PRINCIPAL, SCOPE, 1, first.nextCursor());

        assertEquals(1, second.items().size());
        ExternalApiProject originalA = second.items().get(0);
        assertEquals(publicIdCodec.encode(PRINCIPAL, "project", PROJECT_A), originalA.publicProjectId());
        assertEquals("募集中", originalA.status());
        assertEquals(publicIdCodec.encode(PRINCIPAL, "customer", CUSTOMER_A), originalA.publicCustomerId());
        assertEquals(null, second.nextCursor());
        assertEquals(false, second.hasMore());
    }

    private void insertFixture() {
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name) VALUES (?, ?), (?, ?)",
                CUSTOMER_A, "snapshot-customer-a", CUSTOMER_B, "snapshot-customer-b");
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_A, "snapshot-project-a", CUSTOMER_A, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                PROJECT_B, "snapshot-project-b", CUSTOMER_A, "選考中",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
    }
}
