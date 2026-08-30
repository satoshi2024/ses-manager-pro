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
import com.ses.test.MySQLContainer;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実MySQL 8コンテナ上でのライフサイクルDDL・Flywayマイグレーション・状態整合性テスト
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
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
                .username("admin_mysql_lc_empty")
                .realName("管理者MySQL空検索")
                .role("管理者")
                .build();

        assertTrue(caseService.listCases(null, "__存在しないステータス__", null, null, null, admin).isEmpty());
        assertTrue(caseService.listCases(null, null, Long.MAX_VALUE, null, null, admin).isEmpty());
    }

    @Test
    @DisplayName("MySQL-1: Flyway V109マイグレーション適用と実MySQL上でのライフサイクルフロー実行")
    void testLifecycleWorkflowOnRealMySql() {
        SysUser admin = SysUser.builder()
                .username("admin_mysql_lc")
                .password("pass")
                .realName("管理者MySQL")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(admin);

        // ROLE:HR 担当解決用（必須タスクは userId 解決に失敗すると createCase が落ちる）
        SysUser hr = SysUser.builder()
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
}
