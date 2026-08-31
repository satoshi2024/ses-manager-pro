package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.dto.integrationhub.ExternalApiListResponse;
import com.ses.dto.integrationhub.ExternalApiProject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A1 snapshot purgeの有限batch、FK cascade、再実行および公開read非依存を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiReadSnapshotPurgeIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Autowired
    private ExternalApiReadSnapshotPurgeService purgeService;

    @Autowired
    private ExternalApiReadService readService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void purgeDeletesOnlyOneBoundedBatchAndCascadesItemsBeforeNextRun() {
        insertExpiredSnapshot("purge-expired-a");
        insertExpiredSnapshot("purge-expired-b");
        insertExpiredSnapshot("purge-expired-c");

        assertEquals(2, purgeService.purgeExpiredBatch(NOW, 2));
        assertEquals(1, countTestSnapshots());
        assertEquals(1, countTestItems());

        assertEquals(1, purgeService.purgeExpiredBatch(NOW, 2));
        assertEquals(0, countTestSnapshots());
        assertEquals(0, countTestItems());

        assertEquals(0, purgeService.purgeExpiredBatch(NOW, 2));
    }

    @Test
    void publicReadDoesNotPurgeExpiredRows() {
        insertExpiredSnapshot("purge-must-not-run-from-read");
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name) VALUES (?, ?)",
                9020001L, "purge-read-customer");
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, 9020002L, "purge-read-project", 9020001L, "募集中",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));
        int before = countSnapshots();

        ExternalApiPrincipal principal = new ExternalApiPrincipal(
                "purge-read-client", 9020003L, "tenant-purge-read", 902L,
                "{\"projectIds\":[\"9020002\"],\"customerIds\":[\"9020001\"]}",
                1, "purge-read-key", "STANDARD");
        ExternalApiEffectiveScope scope = new ExternalApiEffectiveScope("tenant-purge-read", 902L, Map.of(
                "tenantIds", Set.of("tenant-purge-read"), "legalEntityIds", Set.of("902"),
                "projectIds", Set.of("9020002"), "customerIds", Set.of("9020001")));

        ExternalApiListResponse<ExternalApiProject> response = readService.listProjects(principal, scope, 1, null);

        assertEquals(1, response.items().size());
        assertEquals(before, countSnapshots());
    }

    private void insertExpiredSnapshot(String snapshotId) {
        // 共有H2上の他fixtureより先にpurge batchへ入るよう最古のexpires_atを使う（CHK: expires_at > as_of）
        LocalDateTime asOf = LocalDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(Instant.EPOCH.plusSeconds(1), ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO t_api_read_snapshot
                    (snapshot_id, client_id, tenant_id, legal_entity_id, route_template, scope_digest,
                     as_of, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, "purge-client", "tenant-purge", 901L,
                "/external-api/v1/projects", "a".repeat(64), asOf, expiresAt, asOf);
        jdbcTemplate.update("""
                INSERT INTO t_api_read_snapshot_item (snapshot_id, resource_id, payload_json, created_at)
                VALUES (?, ?, ?, ?)
                """, snapshotId, Math.abs((long) snapshotId.hashCode()) + 1000L, "{}", asOf);
    }

    private int countTestSnapshots() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_read_snapshot WHERE snapshot_id LIKE 'purge-%'", Integer.class);
    }

    private int countTestItems() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_api_read_snapshot_item i
                JOIN t_api_read_snapshot s ON s.snapshot_id = i.snapshot_id
                WHERE s.snapshot_id LIKE 'purge-%'
                """, Integer.class);
    }

    private int countSnapshots() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_api_read_snapshot", Integer.class);
    }

    private int countItems() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_api_read_snapshot_item", Integer.class);
    }
}
