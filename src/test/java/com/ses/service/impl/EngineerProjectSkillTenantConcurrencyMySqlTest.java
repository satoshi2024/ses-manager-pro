package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.skill.SkillReplaceRequest;
import com.ses.service.EngineerSkillService;
import com.ses.service.ProjectSkillService;
import com.ses.service.accounting.AccountingTenantContextHolder;
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

/** 実MySQLでskill replacementのtenant ownership・reason・CASを検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class EngineerProjectSkillTenantConcurrencyMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_skill_tenant")
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
    private EngineerSkillService engineerSkillService;

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void MySQLのskill置換はtenantを固定し同じversionの同時更新を一件だけ成功させる() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long engineerId = insertEngineer("skill-engineer-" + suffix, "tenant-a");
        long skillId = insertSkill("skill-" + suffix);

        SkillReplaceRequest first = request(0, "MySQL HR replacement", skillId);
        SkillReplaceRequest second = request(0, "MySQL stale replacement", skillId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (SkillReplaceRequest request : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        AccountingTenantContextHolder.runWithTenant("tenant-a",
                                () -> engineerSkillService.replaceSkills(engineerId, request));
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        assertThat(ex.getCode()).isEqualTo(409);
                        conflicts.incrementAndGet();
                    } finally {
                        AccountingTenantContextHolder.clear();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM t_engineer WHERE id = ? AND tenant_id = ?", Integer.class,
                engineerId, "tenant-a");
        assertThat(version).isEqualTo(1);
        List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reason FROM t_engineer_skill_event WHERE tenant_id = ? AND engineer_id = ? "
                        + "AND event_type = 'OPEN'", String.class, "tenant-a", engineerId);
        assertThat(reasons).hasSize(1)
                .allMatch(reason -> reason.equals("MySQL HR replacement")
                        || reason.equals("MySQL stale replacement"));
    }

    @Test
    void MySQLのprojectSkillもcustomerのtenantOwnership内でreasonを監査する() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long customerId = insertCustomer("skill-customer-" + suffix, "tenant-a");
        long projectId = insertProject("skill-project-" + suffix, customerId);
        long skillId = insertSkill("project-skill-" + suffix);

        AccountingTenantContextHolder.setTenantId("tenant-a");
        projectSkillService.replaceSkills(projectId, request(0, "MySQL project requirement", skillId));

        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM t_project_skill_event WHERE tenant_id = ? AND project_id = ? "
                        + "AND event_type = 'OPEN'", String.class, "tenant-a", projectId);
        assertThat(reason).isEqualTo("MySQL project requirement");

        AccountingTenantContextHolder.setTenantId("tenant-b");
        assertThat(projectSkillService.listDetail(projectId)).isEmpty();
    }

    private SkillReplaceRequest request(int expectedVersion, String reason, long skillId) {
        SkillReplaceRequest request = new SkillReplaceRequest();
        request.setExpectedVersion(expectedVersion);
        request.setReason(reason);
        SkillReplaceRequest.SkillItem item = new SkillReplaceRequest.SkillItem();
        item.setSkillId(skillId);
        item.setProficiency("上級");
        item.setRequiredLevel("上級");
        request.setSkills(List.of(item));
        return request;
    }

    private long insertEngineer(String name, String tenantId) {
        jdbcTemplate.update("INSERT INTO t_engineer (tenant_id, full_name, status) VALUES (?, ?, 'Bench')",
                tenantId, name);
        return jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
    }

    private long insertSkill(String name) {
        jdbcTemplate.update("INSERT INTO m_skill_tag (skill_name) VALUES (?)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM m_skill_tag WHERE skill_name = ?", Long.class, name);
    }

    private long insertCustomer(String name, String tenantId) {
        jdbcTemplate.update("INSERT INTO m_customer (tenant_id, company_name) VALUES (?, ?)", tenantId, name);
        return jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
    }

    private long insertProject(String name, long customerId) {
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                name, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }
}
