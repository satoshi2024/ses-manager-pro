package com.ses.privacy.production.provider;

import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.impl.AiRecommendationRecorderImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 AI provider（t_ai_recommendation_run の redacted summary、§3.4.1 D6 の母集団）を H2 で確認する。
 *
 * <p>summary の本人は「source 要員、無ければ推薦結果の1件目の要員」（{@code AiRecommendationRecorderImpl}）。
 * 要員→案件の run は parameter_hash を記録側と同じ規則で再計算して照合するため、記録側の private な計算
 * （{@code canonicalParameters}・{@code sha256}）を reflection で呼び、provider の計算と一致することを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiLogProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 501;
    private static final long OTHER_ENGINEER_ID = BASE_ID + 502;
    private static final long PROJECT_A = BASE_ID + 591;
    private static final long PROJECT_B = BASE_ID + 592;

    private static final String RECORDER_TENANT = "tenant-recorder";

    @Autowired
    private AiLogProvider provider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.ses.service.ai.AiRecommendationRecorder recorder;
    @Autowired
    private com.ses.mapper.EngineerMapper engineerMapper;
    @Autowired
    private com.ses.mapper.ProjectMapper projectMapper;
    @Autowired
    private com.ses.service.security.DataScopeService dataScopeService;
    @Autowired
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    /**
     * 記録側（{@code AiRecommendationRecorder#recordMatch}、AiApiController と同じ引数）で実際に記録した run を provider が
     * 本人へ帰属させる（記録の DTO・item の書き方・source 案件の扱いが変わったときの不一致を検出する。NF07-F2 delta review P3-4）。
     * 設定は {@code AiRecommendationRecorderHashTest} と同じ（tenant-recorder、法人 77、管理者）。
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "1", roles = "管理者")
    void 記録側で実際に記録したrunを要員から案件と案件から要員の両方で本人へ帰属させる() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        try {
            com.ses.test.TenantTestSecurity.bindAs(1L, "1", RECORDER_TENANT, "管理者");
            long stamp = System.nanoTime();
            Long source = recorderEngineer("AI-attr-src-" + stamp);
            Long second = recorderEngineer("AI-attr-2nd-" + stamp);
            Long customerId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM m_customer", Long.class);
            jdbc.update("INSERT INTO m_customer (id, company_name, tenant_id, legal_entity_id, deleted_flag) "
                    + "VALUES (?, ?, ?, 77, 0)", customerId, "AI-attr-cust-" + stamp, RECORDER_TENANT);
            Long projectA = recorderProject(customerId, "AI-attr-pa-" + stamp);
            Long projectB = recorderProject(customerId, "AI-attr-pb-" + stamp);
            com.ses.service.ai.copilot.CopilotExecutionContext context = recorderContext();

            // 要員→案件（source 案件なし。summary は source 要員）
            MatchResultDto toProjectA = project(projectA);
            recorder.recordMatch("MATCHING", null, List.of(toProjectA, project(projectB)), source, null, context);
            // 案件→要員（summary は1件目の要員。2件目は第三者）
            MatchResultDto toSource = engineerResult(source);
            recorder.recordMatch("MATCHING", null, List.of(toSource, engineerResult(second)), null, projectA, context);

            assertThat(toProjectA.getRunId()).isNotNull();
            assertThat(toSource.getRunId()).isNotNull();
            assertThat(provider.findBySubject(RECORDER_TENANT, engineer(source))).extracting(PrivacyRecord::rowId)
                    .containsExactlyInAnyOrder(toProjectA.getRunId(), toSource.getRunId());
            assertThat(provider.findBySubject(RECORDER_TENANT, engineer(second))).isEmpty();
        } finally {
            com.ses.service.accounting.AccountingTenantContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private Long recorderEngineer(String name) {
        com.ses.entity.Engineer engineer = new com.ses.entity.Engineer();
        engineer.setTenantId(RECORDER_TENANT);
        engineer.setFullName(name);
        engineer.setLegalEntityId(77L);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("Bench");
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private Long recorderProject(Long customerId, String name) {
        com.ses.entity.Project project = new com.ses.entity.Project();
        project.setProjectName(name);
        project.setLegalEntityId(77L);
        project.setCustomerId(customerId);
        project.setStatus("募集中");
        project.setWorkLocation("東京都千代田区丸の内1-1-1");
        projectMapper.insert(project);
        return project.getId();
    }

    private com.ses.service.ai.copilot.CopilotExecutionContext recorderContext() {
        com.ses.service.ai.copilot.CopilotExecutionContext context = new com.ses.service.ai.copilot.CopilotExecutionContext(
                RECORDER_TENANT, 77L, java.time.Instant.parse("2026-09-09T00:00:00Z"), java.time.ZoneId.of("UTC"));
        context.bindSnapshot(new com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory(dataScopeService,
                organizationScopeService).create(RECORDER_TENANT, 77L, java.time.LocalDate.of(2026, 9, 9)));
        return new com.ses.service.ai.LegacyAiExecutionContextBinder().bind(context);
    }

    private static MatchResultDto engineerResult(long engineerId) {
        MatchResultDto dto = new MatchResultDto();
        dto.setEngineerId(engineerId);
        dto.setScore(80);
        dto.setReason("ok");
        return dto;
    }

    @Test
    void 案件から要員の照合は1件目の要員だけを本人とし2件目以降の要員は第三者として返さない() {
        long run = fx.aiRun(BASE_ID + 511, TENANT, "{\"masked\":true}", false);
        fx.aiItem(BASE_ID + 512, TENANT, run, 1, "ENGINEER", ENGINEER_ID);
        fx.aiItem(BASE_ID + 517, TENANT, run, 2, "ENGINEER", OTHER_ENGINEER_ID);
        long deletedRun = fx.aiRun(BASE_ID + 513, TENANT, "{\"masked\":2}", true);
        fx.aiItem(BASE_ID + 514, TENANT, deletedRun, 1, "ENGINEER", ENGINEER_ID);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_ai_recommendation_run"))
                .containsExactlyInAnyOrder(run, deletedRun);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.dataElementIds()).containsExactly(AiLogProvider.REDACTED_SUMMARY);
            assertThat(r.storedNames()).isEmpty();
            assertThat(r.fields()).doesNotContainKeys("input_hash", "parameter_hash", "scope_hash", "cost_jpy",
                    "token_input", "token_output", "actor_user_id", "dispatch_nonce_hash", "dispatch_claim_id");
        });
        assertThat(records).filteredOn(r -> r.rowId() == deletedRun).singleElement()
                .satisfies(r -> assertThat(r.logicallyDeleted()).isTrue());
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, AiLogProvider.RUN);
        // 2件目の要員には1件目の要員の summary を返さない
        assertThat(provider.findBySubject(TENANT, engineer(OTHER_ENGINEER_ID))).isEmpty();
    }

    @Test
    void 要員から案件の照合は記録側と同じparameter_hashでsource要員の本人とし他の要員には返さない() throws Exception {
        String selfHash = recorderHash("MATCHING", ENGINEER_ID, List.of(project(PROJECT_A), project(PROJECT_B)));
        String otherHash = recorderHash("MATCHING", OTHER_ENGINEER_ID, List.of(project(PROJECT_A)));
        long self = fx.aiRun(BASE_ID + 521, TENANT, "MATCHING", "{\"masked\":\"self\"}", selfHash, false);
        fx.aiItem(BASE_ID + 522, TENANT, self, 1, "PROJECT", PROJECT_A);
        fx.aiItem(BASE_ID + 523, TENANT, self, 2, "PROJECT", PROJECT_B);
        // 同じ推薦結果でも source 要員が違えば hash が違い、本人にしない
        long other = fx.aiRun(BASE_ID + 524, TENANT, "MATCHING", "{\"masked\":\"other\"}", otherHash, false);
        fx.aiItem(BASE_ID + 525, TENANT, other, 1, "PROJECT", PROJECT_A);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID)))
                .extracting(PrivacyRecord::rowId).containsExactly(self);
        assertThat(provider.findBySubject(TENANT, engineer(OTHER_ENGINEER_ID)))
                .extracting(PrivacyRecord::rowId).containsExactly(other);

        // provider の正規形は記録側の正規形と一致する（記録規則の変更を検出する）
        List<Map<String, Object>> items = List.of(item(1, "PROJECT", PROJECT_A), item(2, "PROJECT", PROJECT_B));
        assertThat(AiLogProvider.sha256(AiLogProvider.canonicalParameters("MATCHING", ENGINEER_ID, items)))
                .isEqualTo(selfHash);
    }

    @Test
    void MATCHING以外のuse_caseと他tenantのrunと他種別のsubjectは返さない() throws Exception {
        String hash = recorderHash("OTHER_USE_CASE", ENGINEER_ID, List.of(project(PROJECT_A)));
        long otherUseCase = fx.aiRun(BASE_ID + 531, TENANT, "OTHER_USE_CASE", "{}", hash, false);
        fx.aiItem(BASE_ID + 532, TENANT, otherUseCase, 1, "PROJECT", PROJECT_A);
        long otherRun = fx.aiRun(BASE_ID + 533, OTHER_TENANT, "{}", false);
        fx.aiItem(BASE_ID + 534, OTHER_TENANT, otherRun, 1, "ENGINEER", ENGINEER_ID);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID))).isEmpty();
        assertThat(provider.findBySubject(OTHER_TENANT, engineer(ENGINEER_ID))).hasSize(1);
        assertThat(provider.findBySubject(OTHER_TENANT, PrivacySubjectRef.of(PrivacySubjectType.SYS_USER, 1L))).isEmpty();
    }

    @Test
    void 照会は書込みを行わない() throws Exception {
        long run = fx.aiRun(BASE_ID + 541, TENANT, "{}", false);
        fx.aiItem(BASE_ID + 542, TENANT, run, 1, "ENGINEER", ENGINEER_ID);
        long source = fx.aiRun(BASE_ID + 543, TENANT, "MATCHING", "{}",
                recorderHash("MATCHING", ENGINEER_ID, List.of(project(PROJECT_A))), false);
        fx.aiItem(BASE_ID + 544, TENANT, source, 1, "PROJECT", PROJECT_A);
        List<?> runs = fx.snapshot("t_ai_recommendation_run", BASE_ID);
        List<?> items = fx.snapshot("t_ai_recommendation_item", BASE_ID);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID))).hasSize(2);

        assertThat(fx.snapshot("t_ai_recommendation_run", BASE_ID)).isEqualTo(runs);
        assertThat(fx.snapshot("t_ai_recommendation_item", BASE_ID)).isEqualTo(items);
    }

    /** 記録側（AiRecommendationRecorderImpl）の private な計算で parameter_hash を作る。 */
    private static String recorderHash(String useCase, long sourceEngineerId, List<MatchResultDto> results)
            throws Exception {
        Method canonical = AiRecommendationRecorderImpl.class.getDeclaredMethod("canonicalParameters",
                String.class, Long.class, Long.class, List.class);
        canonical.setAccessible(true);
        Method sha256 = AiRecommendationRecorderImpl.class.getDeclaredMethod("sha256", String.class);
        sha256.setAccessible(true);
        String value = (String) canonical.invoke(null, useCase, sourceEngineerId, null, new ArrayList<>(results));
        return (String) sha256.invoke(null, value);
    }

    private static MatchResultDto project(long projectId) {
        MatchResultDto dto = new MatchResultDto();
        dto.setProjectId(projectId);
        dto.setScore(80);
        dto.setReason("ok");
        return dto;
    }

    private static Map<String, Object> item(long rank, String type, long targetId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rank_no", rank);
        item.put("target_type", type);
        item.put("target_id", targetId);
        return item;
    }

    private static PrivacySubjectRef engineer(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, id);
    }
}
