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

    @Autowired
    private AiLogProvider provider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
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
