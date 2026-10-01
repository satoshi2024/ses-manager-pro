package com.ses.service.security;

import com.ses.mapper.ResumeIngestionMapper;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLで原本取込のtenant SQLとownership repair CASを検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class OwnershipRepairResumeTenantBoundaryMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_resume_repair_tenant")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ResumeIngestionMapper resumeIngestionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTenant() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
    }

    @Test
    void MySQLの履歴取込jobはtenant付きmapperで相互参照できない() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String fileA = "resume-a-" + suffix + ".pdf";
        String fileB = "resume-b-" + suffix + ".pdf";
        long jobA = insertResume("tenant-a", fileA);
        long jobB = insertResume("tenant-b", fileB);

        assertThat(resumeIngestionMapper.selectByIdForTenant(jobA, "tenant-a").getTenantId())
                .isEqualTo("tenant-a");
        assertThat(resumeIngestionMapper.selectByIdForTenant(jobA, "tenant-b")).isNull();
        assertThat(resumeIngestionMapper.selectByStoredFileNameForTenant("tenant-a", fileB)).isNull();
        assertThat(resumeIngestionMapper.selectByStoredFileNameForTenant("tenant-b", fileA)).isNull();
        assertThat(resumeIngestionMapper.casStatusForTenant(jobB, "tenant-a", "取込待ち", "抽出中", 0))
                .isZero();
    }

    @Test
    void MySQLのrepair解決CASは同一行で一件だけ成功する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO nf02_nf03_ownership_repair_queue "
                        + "(entity_type, entity_id, reason, status, version) VALUES ('CUSTOMER', ?, 'TENANT_UNRESOLVED', 'CLAIMED', 1)",
                Math.abs(UUID.randomUUID().getMostSignificantBits()));
        long queueId = jdbcTemplate.queryForObject(
                "SELECT id FROM nf02_nf03_ownership_repair_queue WHERE reason = 'TENANT_UNRESOLVED' "
                        + "ORDER BY id DESC LIMIT 1", Long.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String token = "resolve-" + suffix + "-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                    int updated = jdbcTemplate.update("UPDATE nf02_nf03_ownership_repair_queue "
                                    + "SET status = 'RESOLVED', claim_token = ?, version = version + 1 "
                                    + "WHERE id = ? AND status = 'CLAIMED' AND version = 1",
                            token, queueId);
                    if (updated == 1) {
                        success.incrementAndGet();
                    } else {
                        conflict.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nf02_nf03_ownership_repair_queue WHERE id = ?", String.class, queueId))
                .isEqualTo("RESOLVED");
    }

    private long insertResume(String tenantId, String storedFileName) {
        jdbcTemplate.update("INSERT INTO t_resume_ingestion "
                        + "(tenant_id, original_file_name, stored_file_name, file_ext, status, version, deleted_flag) "
                        + "VALUES (?, ?, ?, 'pdf', '取込待ち', 0, 0)",
                tenantId, storedFileName, storedFileName);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_resume_ingestion WHERE stored_file_name = ?", Long.class, storedFileName);
    }
}
