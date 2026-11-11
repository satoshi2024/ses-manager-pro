package com.ses.migration;

import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleTaskService;
import com.ses.service.lifecycle.LifecycleTemplateService;
import com.ses.test.TenantTestSecurity;
import com.ses.test.MySQLContainer;
import com.ses.test.EnableDefaultTenantTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ses.service.accounting.AccountingTenantContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実MySQL 8コンテナ上でのライフサイクルDDL・Flywayマイグレーション・状態整合性テスト
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@EnableDefaultTenantTestContext
class LifecycleMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_lifecycle_mysql")
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
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private LifecycleTaskService taskService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Test
    void 実MySQLで該当案件がない一覧検索は空配列を返す() {
        SysUser admin = SysUser.builder()
                .tenantId("default")
                .username("admin_mysql_lc_empty")
                .password("pass")
                .realName("管理者MySQL空検索")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(admin);

        assertTrue(caseService.listCases(null, "__存在しないステータス__", null, null, null, admin).isEmpty());
        assertTrue(caseService.listCases(null, null, Long.MAX_VALUE, null, null, admin).isEmpty());
    }

    @Test
    @DisplayName("MySQL-1: Flyway V109マイグレーション適用と実MySQL上でのライフサイクルフロー実行")
    void testLifecycleWorkflowOnRealMySql() {
        SysUser admin = SysUser.builder()
                .tenantId("default")
                .username("admin_mysql_lc")
                .password("pass")
                .realName("管理者MySQL")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(admin);

        // ROLE:HR 担当解決用（必須タスクは userId 解決に失敗すると createCase が落ちる）
        SysUser hr = SysUser.builder()
                .tenantId("default")
                .username("hr_mysql_lc")
                .password("pass")
                .realName("人事MySQL")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hr);

        OrganizationUnit org = OrganizationUnit.builder()
                .code("ORG-MYSQL-LC")
                .name("開発本部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);

        Engineer eng = Engineer.builder()
                .tenantId("default")
                .fullName("MySQL要員")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        eng.setOrganizationId(org.getId());
        engineerMapper.insert(eng);

        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("MySQL入社フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("MYSQL_TASK_01")
                                .taskName("PC手配・セットアップ")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build()
                ))
                .build(), admin.getId());

        LifecycleCaseDto caseDto = caseService.createCase(admin.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(eng.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("MySQL入社案件")
                .build());

        assertNotNull(caseDto.getId());
        assertEquals("ACTIVE", caseDto.getStatus());

        LifecycleTask task = taskMapper.selectByCaseId(caseDto.getId()).get(0);
        taskService.completeTask(task.getId(), admin.getId(), CompleteLifecycleTaskCommand.builder()
                .completionComment("MySQLコンテナ上で正常完了")
                .build());

        caseService.completeCase(caseDto.getId(), admin.getId());

        LifecycleCase finalCase = caseMapper.selectById(caseDto.getId());
        assertEquals("COMPLETED", finalCase.getStatus());
    }

    @Test
    @DisplayName("MySQL-P2: tenant・月の初期化をまたぐ同時案件起票で番号が重複しない")
    void concurrentCaseCreationInitializesTenantMonthlySequenceAtomically() throws Exception {
        String tenantA = "lc-sequence-a-" + System.nanoTime();
        String tenantB = "lc-sequence-b-" + System.nanoTime();
        SysUser adminA = createAdmin(tenantA, "a");
        SysUser adminB = createAdmin(tenantB, "b");
        Engineer engineerA1 = createEngineer(tenantA, "a1");
        Engineer engineerA2 = createEngineer(tenantA, "a2");
        Engineer engineerB1 = createEngineer(tenantB, "b1");
        Engineer engineerB2 = createEngineer(tenantB, "b2");
        LocalDate anchorDate = LocalDate.of(2088, 5, 15);

        TenantTestSecurity.bindAs(adminA.getId(), adminA.getUsername(), tenantA, "管理者");
        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN").name("並行採番-" + tenantA).validFrom(anchorDate.minusDays(1))
                .tasks(List.of(LifecycleTemplateTaskDto.builder().taskCode("SEQ_" + tenantA.substring(tenantA.length() - 8))
                        .taskName("並行採番確認").assigneeRule("APPLICANT").isMandatory(1).isBlocking(0).build()))
                .build(), adminA.getId());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LifecycleCaseDto>> futures = new ArrayList<>();
            futures.add(pool.submit(createCaseAtBarrier(adminA, engineerA1, template.getId(), tenantA, anchorDate, ready, start)));
            futures.add(pool.submit(createCaseAtBarrier(adminA, engineerA2, template.getId(), tenantA, anchorDate, ready, start)));
            futures.add(pool.submit(createCaseAtBarrier(adminB, engineerB1, template.getId(), tenantB, anchorDate, ready, start)));
            futures.add(pool.submit(createCaseAtBarrier(adminB, engineerB2, template.getId(), tenantB, anchorDate, ready, start)));

            assertTrue(ready.await(20, TimeUnit.SECONDS), "全workerがDB採番直前で待機すること");
            start.countDown();
            List<LifecycleCaseDto> cases = new ArrayList<>();
            for (Future<LifecycleCaseDto> future : futures) {
                cases.add(future.get(60, TimeUnit.SECONDS));
            }

            assertEquals(4, cases.stream().map(LifecycleCaseDto::getCaseNo).distinct().count());
            assertNotEquals(cases.get(0).getCaseNo(), cases.get(1).getCaseNo());
            assertNotEquals(cases.get(2).getCaseNo(), cases.get(3).getCaseNo());
            assertTrue(cases.get(0).getCaseNo().startsWith(monthlyTenantPrefix(anchorDate, tenantA)));
            assertTrue(cases.get(1).getCaseNo().startsWith(monthlyTenantPrefix(anchorDate, tenantA)));
            assertTrue(cases.get(2).getCaseNo().startsWith(monthlyTenantPrefix(anchorDate, tenantB)));
            assertTrue(cases.get(3).getCaseNo().startsWith(monthlyTenantPrefix(anchorDate, tenantB)));
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            SecurityContextHolder.clearContext();
            TestSecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    private java.util.concurrent.Callable<LifecycleCaseDto> createCaseAtBarrier(
            SysUser admin, Engineer engineer, Long templateId, String tenant, LocalDate anchorDate,
            CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("採番開始barrierが解放されませんでした");
            }
            TenantTestSecurity.bindAs(admin.getId(), admin.getUsername(), tenant, "管理者");
            try {
                return caseService.createCase(admin.getId(), CreateLifecycleCaseCommand.builder()
                        .engineerId(engineer.getId()).lifecycleType("JOIN").templateId(templateId)
                        .anchorDate(anchorDate).build());
            } finally {
                SecurityContextHolder.clearContext();
                TestSecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        };
    }

    private SysUser createAdmin(String tenant, String suffix) {
        SysUser admin = SysUser.builder().tenantId(tenant).username("lc_seq_admin_" + suffix + "_" + System.nanoTime())
                .password("pass").realName("採番管理者").role("管理者").status(1).build();
        sysUserMapper.insert(admin);
        return admin;
    }

    private Engineer createEngineer(String tenant, String suffix) {
        Engineer engineer = Engineer.builder().tenantId(tenant).fullName("採番要員" + suffix)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer;
    }

    private String monthlyTenantPrefix(LocalDate anchorDate, String tenant) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String key = java.util.HexFormat.of().formatHex(digest, 0, 12);
        return "LC-" + anchorDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "-" + key + "-";
    }
}
