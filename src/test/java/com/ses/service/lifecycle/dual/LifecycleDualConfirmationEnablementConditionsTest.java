package com.ses.service.lifecycle.dual;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.service.FeatureEnablementCloseWarningSource;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.service.lifecycle.impl.LifecycleTemplateServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-B01（feature-enablement-console design §8.2、requirements §6.2）: NF01 二者確認の開放の条件。
 *
 * <ul>
 *   <li>Privacy 署名の参照は、旧い起動時検査の判定（{@link LifecycleDualConfirmationReleaseGate#acceptable}）と同じ入力で同じ成否に
 *       なる（DB の源は確認済みの署名の参照、property の源は従来の property）。</li>
 *   <li>証跡種別の許可値の外の行（テンプレートのタスク定義・案件のタスク。FS 4.9.5 の (1)(2)）があれば不成立。H2 でも同じ SQL で
 *       大文字小文字・全角英字・NBSP・末尾の NUL の揺れを検出し、全ての許可値は検出しない。照会の失敗は評価できない類。</li>
 *   <li>閉鎖の警告は二者確認を含む有効なテンプレートの件数（FS 4.9.5 の (3)）。</li>
 * </ul>
 * 行は試験の transaction の中で入れ、rollback で消す（件数は試験の前の値からの増分で比べる）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleDualConfirmationEnablementConditionsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private LifecycleTemplateTaskMapper templateTaskMapper;
    @Autowired
    private LifecycleTaskMapper taskMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void Privacy署名の参照はReleaseGateのacceptableと同じ入力で同じ成否になる() {
        List<String> references = new ArrayList<>(Arrays.asList("", "   ", "TODO", "changeme", "Pending", "abc",
                " PRIV-2026-001", "PRIV-2026-001 ", "署名済み", "PRIV 2026 001", "TODO-1", "pending-privacy-1",
                "test-ref-1", "xxxxx1", "TBD2026", "PLACEHOLDER-1", "PRIV-TODO-2026", "review-abcd", "20261120",
                "tmp-2026", "CHANGE_ME-1", "sample-2026", "dev-signoff-1", "PRIV-REVIEW-2026-11-20", "PRIV-2026-001",
                "reviews/nf01-o8-privacy-signoff.md#v2", "NF01:O8:2026-11-20"));
        LifecycleDualConfirmationEnablementConditions database = conditions(true, null);
        for (String reference : references) {
            boolean expected = LifecycleDualConfirmationReleaseGate.acceptable(reference);
            FeatureConditionCheck fromDatabase = database.privacySignOff(snapshot(reference));
            assertThat(fromDatabase.passing()).as("DB の源 [%s]", reference).isEqualTo(expected);
            assertThat(fromDatabase.code()).as("code は固定の文字列で参照の値を含めない").isEqualTo(expected
                    ? "dualConfirmation.privacySignOff.valid" : "dualConfirmation.privacySignOff.invalid");

            FeatureConditionCheck fromProperty = conditions(false, reference).privacySignOff(snapshot(null));
            assertThat(fromProperty.passing()).as("property の源 [%s]", reference).isEqualTo(expected);
        }
        assertThat(database.privacySignOff(snapshot(null)).code()).isEqualTo("dualConfirmation.privacySignOff.missing");
        assertThat(conditions(false, null).privacySignOff(snapshot("PRIV-2026-001")).code())
                .as("property の源は DB の署名を見ない").isEqualTo("dualConfirmation.privacySignOff.missing");
        assertThat(conditions(true, "PRIV-2026-001").privacySignOff(snapshot(null)).code())
                .as("DB の源は property を見ない").isEqualTo("dualConfirmation.privacySignOff.missing");
    }

    @Test
    void 全ての許可値は検出せず照合順序で同一視される揺れはテンプレートとタスクの両方で検出する() {
        long baseTemplateTasks = templateTaskMapper.countEvidenceTypesOutsideAllowed();
        long baseTasks = taskMapper.countEvidenceTypesOutsideAllowed();
        long template = template("ACTIVE");
        long caseId = lifecycleCase(template);
        for (String allowed : LifecycleTemplateServiceImpl.EVIDENCE_TYPES) {
            templateTask(template, allowed);
            task(caseId, allowed);
        }
        LifecycleDualConfirmationEnablementConditions conditions = conditions(true, null);
        assertThat(templateTaskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTemplateTasks);
        assertThat(taskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTasks);

        // 小文字・全角英字・NBSP・ゼロ幅空白・末尾の NUL（BINARY(N) の 0x00 の埋めと同じ byte）
        List<String> variants = List.of("dual_confirmation", "ＮＯＮＥ", "NONE ", "DOCUMENT​_LINK", "NONE\u0000");
        for (String variant : variants) {
            templateTask(template, variant);
        }
        task(caseId, "Dual_Confirmation");
        assertThat(templateTaskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTemplateTasks + variants.size());
        assertThat(taskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTasks + 1);
        FeatureConditionCheck check = conditions.evidenceTypes();
        assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.UNSATISFIED);
        assertThat(check.code()).isEqualTo("dualConfirmation.evidenceTypes.outsideAllowed");
        assertThat(check.detailCodes()).contains("templateTasks[count=" + (baseTemplateTasks + variants.size()) + "]",
                "tasks[count=" + (baseTasks + 1) + "]");
        assertThat(String.join(",", check.detailCodes())).as("値を出さない").doesNotContain("dual_confirmation");

        // 論理削除した行は数えない
        jdbc.update("UPDATE m_lifecycle_template_task SET deleted_flag = 1 WHERE template_id = ?", template);
        jdbc.update("UPDATE t_lifecycle_task SET deleted_flag = 1 WHERE case_id = ?", caseId);
        assertThat(templateTaskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTemplateTasks);
        assertThat(taskMapper.countEvidenceTypesOutsideAllowed()).isEqualTo(baseTasks);
    }

    @Test
    void 揺れが無ければ成立し照会に失敗すれば評価できない類の不成立になる() {
        LifecycleTemplateTaskMapper templates = mock(LifecycleTemplateTaskMapper.class);
        LifecycleTaskMapper tasks = mock(LifecycleTaskMapper.class);
        when(templates.countEvidenceTypesOutsideAllowed()).thenReturn(0L);
        when(tasks.countEvidenceTypesOutsideAllowed()).thenReturn(0L);
        LifecycleDualConfirmationEnablementConditions ok = new LifecycleDualConfirmationEnablementConditions(templates,
                tasks, FeatureEnablementCatalog.standard(), provider(databaseSource()), new FeatureSwitchProperties());
        assertThat(ok.evidenceTypes().code()).isEqualTo("dualConfirmation.evidenceTypes.allowed");
        assertThat(ok.evidenceTypes().passing()).isTrue();

        when(tasks.countEvidenceTypesOutsideAllowed()).thenThrow(new IllegalStateException("db"));
        FeatureConditionCheck failed = ok.evidenceTypes();
        assertThat(failed.status()).isEqualTo(FeatureConditionCheck.Status.UNVERIFIABLE);
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(failed.code())).isTrue();

        // registry を通しても、照会の失敗は評価できない類（一度の失敗で自動閉鎖しない。review P2-1）で、成立した参照と並ぶ
        FeatureEvaluation evaluation = new FeatureConditionRegistry(FeatureEnablementCatalog.standard(), List.of(ok))
                .evaluate(Feature.NF01_DUAL_CONFIRMATION, new FeatureEvaluationContext("default", TODAY,
                        Clock.fixed(NOW, ZoneOffset.UTC), snapshot("PRIV-2026-001"),
                        FeatureEvaluationContext.Purpose.PERIODIC, Set.of(), true));
        assertThat(evaluation.closingUnverifiable()).extracting(FeatureConditionCheck::code)
                .containsExactly("dualConfirmation.evidenceTypes.unverifiable");
        assertThat(evaluation.closingUnsatisfied()).isEmpty();
        assertThat(ok.features()).containsExactly(Feature.NF01_DUAL_CONFIRMATION);
    }

    @Test
    void 閉鎖の警告は二者確認を含む有効なテンプレートだけを数える() {
        long base = templateTaskMapper.countActiveTemplatesWithDualConfirmation();
        long active = template("ACTIVE");
        templateTask(active, "DUAL_CONFIRMATION");
        templateTask(active, "NONE");
        long inactive = template("INACTIVE");
        templateTask(inactive, "DUAL_CONFIRMATION");
        long variant = template("ACTIVE");
        templateTask(variant, "dual_confirmation");
        long deleted = template("ACTIVE");
        templateTask(deleted, "DUAL_CONFIRMATION");
        jdbc.update("UPDATE m_lifecycle_template SET deleted_flag = 1 WHERE id = ?", deleted);

        List<FeatureEnablementCloseWarningSource.CloseWarning> warnings = conditions(true, null)
                .closeWarnings(Feature.NF01_DUAL_CONFIRMATION);
        assertThat(warnings).containsExactly(new FeatureEnablementCloseWarningSource.CloseWarning(
                LifecycleDualConfirmationEnablementConditions.WARNING_ACTIVE_DUAL_TEMPLATES, base + 1));
    }

    // ---------------------------------------------------------------- helper

    private LifecycleDualConfirmationEnablementConditions conditions(boolean database, String propertyReference) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf01DualConfirmation().setPrivacySignOffRef(propertyReference);
        FeatureEnablementStateSource source = database ? databaseSource() : propertySource();
        return new LifecycleDualConfirmationEnablementConditions(templateTaskMapper, taskMapper,
                FeatureEnablementCatalog.standard(), provider(source), properties);
    }

    private static FeatureEnablementSnapshot snapshot(String reference) {
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = reference == null ? Map.of()
                : Map.of(LifecycleDualConfirmationEnablementConditions.KIND_PRIVACY,
                new FeatureEnablementSnapshot.ConfirmedSignOff(1L, 0, LifecycleDualConfirmationEnablementConditions.KIND_PRIVACY,
                        reference, "PRIVACY_OWNER", TODAY.minusDays(1), TODAY.plusDays(30), null, "DECISION"));
        return new FeatureEnablementSnapshot(1L, NOW, NOW, true, Map.of(), Map.of(), signOffs, Map.of());
    }

    private static FeatureEnablementStateSource databaseSource() {
        return new StubSource(true);
    }

    private static FeatureEnablementStateSource propertySource() {
        return new StubSource(false);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<FeatureEnablementStateSource> provider(FeatureEnablementStateSource source) {
        ObjectProvider<FeatureEnablementStateSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return provider;
    }

    private long template(String status) {
        String type = "FEB01-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_lifecycle_template (template_type, name, version_no, status, valid_from) "
                + "VALUES (?, ?, 1, ?, ?)", type, "FE-B01 の試験", status, TODAY);
        return jdbc.queryForObject("SELECT id FROM m_lifecycle_template WHERE template_type = ?", Long.class, type);
    }

    private void templateTask(long templateId, String evidenceType) {
        jdbc.update("INSERT INTO m_lifecycle_template_task (template_id, task_code, task_name, assignee_rule, "
                + "evidence_type) VALUES (?, ?, ?, 'ROLE', ?)", templateId, "T" + SEQUENCE.incrementAndGet(), "タスク",
                evidenceType);
    }

    private long lifecycleCase(long templateId) {
        String caseNo = "LC-FEB01-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO t_lifecycle_case (tenant_id, case_no, lifecycle_type, engineer_id, template_id, "
                + "template_version, anchor_date, status, title, applicant_user_id, engineer_snapshot_json) "
                + "VALUES ('default', ?, 'JOIN', 1, ?, 1, ?, 'DRAFT', 'FE-B01', 1, '{}')", caseNo, templateId, TODAY);
        return jdbc.queryForObject("SELECT id FROM t_lifecycle_case WHERE case_no = ?", Long.class, caseNo);
    }

    private void task(long caseId, String evidenceType) {
        jdbc.update("INSERT INTO t_lifecycle_task (case_id, task_code, task_name, due_date, evidence_type) "
                + "VALUES (?, ?, 'タスク', ?, ?)", caseId, "T" + SEQUENCE.incrementAndGet(), TODAY, evidenceType);
    }

    /** 源の種類だけを返す試験用の源（開閉は使わない）。 */
    private record StubSource(boolean database) implements FeatureEnablementStateSource {

        @Override
        public boolean isOpen(Feature feature) {
            return false;
        }

        @Override
        public Optional<String> setting(String key) {
            return Optional.empty();
        }

        @Override
        public long generation() {
            return 0L;
        }
    }
}
