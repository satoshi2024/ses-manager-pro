package com.ses.service.lifecycle.dual;

import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.service.lifecycle.impl.LifecycleTemplateServiceImpl;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.apache.ibatis.annotations.Select;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-B01（feature-enablement-console design §8.2、FS 4.9.5）: NF01 二者確認の証跡種別の検査を、本番と同じ migration の列
 * （{@code utf8mb4_unicode_ci}）の実 MySQL で確かめる。照合順序は大文字小文字・全角英字・末尾の空白等を同一視するため、通常の比較では
 * 揺れた表記を見落とす。mapper の SQL（{@code @Select} の文をそのまま使う）はバイト列で比べ、全ての揺れを検出し、許可値は検出しない。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class LifecycleDualConfirmationEvidenceTypeMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_feb01_evidence_type")
            .withUsername("root")
            .withPassword("ses");

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** 照合順序で許可値と同一視され得る揺れ（小文字・全角英字・末尾の空白・NBSP・ゼロ幅空白・末尾の NUL）。 */
    private static final List<String> VARIANTS = List.of("dual_confirmation", "Dual_Confirmation",
            "ＤＵＡＬ＿ＣＯＮＦＩＲＭＡＴＩＯＮ", "NONE ", "NONE ", "DOCUMENT​_LINK", "NONE\u0000");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword()));
    }

    @Test
    void 照合順序で同一視される揺れをバイト列の比較で検出し許可値は検出しない() throws Exception {
        String templateSql = select(LifecycleTemplateTaskMapper.class, "countEvidenceTypesOutsideAllowed");
        String taskSql = select(LifecycleTaskMapper.class, "countEvidenceTypesOutsideAllowed");
        long baseTemplateTasks = count(templateSql);
        long baseTasks = count(taskSql);
        // 前提: 列の照合順序は大文字小文字を同一視する（通常の比較では揺れを見落とす）
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'm_lifecycle_template_task' AND COLUMN_NAME = 'evidence_type'", String.class))
                .isEqualTo("utf8mb4_unicode_ci");

        long template = template("ACTIVE");
        long caseId = lifecycleCase(template);
        for (String allowed : LifecycleTemplateServiceImpl.EVIDENCE_TYPES) {
            templateTask(template, allowed);
            task(caseId, allowed);
        }
        assertThat(count(templateSql)).as("全ての許可値は検出しない").isEqualTo(baseTemplateTasks);
        assertThat(count(taskSql)).isEqualTo(baseTasks);

        long variantTemplate = template("INACTIVE");
        for (String variant : VARIANTS) {
            templateTask(variantTemplate, variant);
            task(caseId, variant);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_lifecycle_template_task WHERE template_id = ? "
                        + "AND evidence_type IN ('NONE', 'DUAL_CONFIRMATION')", Long.class, variantTemplate))
                .as("通常の比較（照合順序）は揺れの一部を許可値と同一視して見落とす").isPositive();
        assertThat(count(templateSql)).as("バイト列の比較は全ての揺れを検出する")
                .isEqualTo(baseTemplateTasks + VARIANTS.size());
        assertThat(count(taskSql)).isEqualTo(baseTasks + VARIANTS.size());
    }

    @Test
    void 閉鎖の警告は表記が完全に一致する二者確認を含む有効なテンプレートだけを数える() throws Exception {
        String sql = select(LifecycleTemplateTaskMapper.class, "countActiveTemplatesWithDualConfirmation");
        long base = count(sql);
        long exact = template("ACTIVE");
        templateTask(exact, "DUAL_CONFIRMATION");
        long lower = template("ACTIVE");
        templateTask(lower, "dual_confirmation");
        long inactive = template("INACTIVE");
        templateTask(inactive, "DUAL_CONFIRMATION");

        assertThat(count(sql)).isEqualTo(base + 1);
    }

    private static String select(Class<?> mapper, String method) throws NoSuchMethodException {
        return String.join(" ", mapper.getMethod(method).getAnnotation(Select.class).value());
    }

    private static long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static long template(String status) {
        String type = "FEB01-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_lifecycle_template (template_type, name, version_no, status, valid_from) "
                + "VALUES (?, 'FE-B01 の試験', 1, ?, ?)", type, status, TODAY);
        return jdbc.queryForObject("SELECT id FROM m_lifecycle_template WHERE template_type = ?", Long.class, type);
    }

    private static void templateTask(long templateId, String evidenceType) {
        jdbc.update("INSERT INTO m_lifecycle_template_task (template_id, task_code, task_name, assignee_rule, evidence_type) "
                + "VALUES (?, ?, 'タスク', 'ROLE', ?)", templateId, "T" + SEQUENCE.incrementAndGet(), evidenceType);
    }

    private static long lifecycleCase(long templateId) {
        String caseNo = "LC-FEB01-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO t_lifecycle_case (tenant_id, case_no, lifecycle_type, engineer_id, template_id, "
                + "template_version, anchor_date, status, title, applicant_user_id, engineer_snapshot_json) "
                + "VALUES ('default', ?, 'JOIN', 1, ?, 1, ?, 'DRAFT', 'FE-B01', 1, '{}')", caseNo, templateId, TODAY);
        return jdbc.queryForObject("SELECT id FROM t_lifecycle_case WHERE case_no = ?", Long.class, caseNo);
    }

    private static void task(long caseId, String evidenceType) {
        jdbc.update("INSERT INTO t_lifecycle_task (case_id, task_code, task_name, due_date, evidence_type) "
                + "VALUES (?, ?, 'タスク', ?, ?)", caseId, "T" + SEQUENCE.incrementAndGet(), TODAY, evidenceType);
    }
}
