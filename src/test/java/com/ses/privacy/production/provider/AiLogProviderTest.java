package com.ses.privacy.production.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/** F2 AI provider（t_ai_recommendation_run の redacted summary、§3.4.1 D6 の母集団）を H2 で確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiLogProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 501;

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
    void 要員を候補に持つrunだけを返しhashと費用と実行者は読まない() {
        long run = fx.aiRun(BASE_ID + 511, TENANT, "{\"masked\":true}", false);
        fx.aiItem(BASE_ID + 512, TENANT, run, 1, "ENGINEER", ENGINEER_ID);
        long deletedRun = fx.aiRun(BASE_ID + 513, TENANT, "{\"masked\":2}", true);
        fx.aiItem(BASE_ID + 514, TENANT, deletedRun, 1, "ENGINEER", ENGINEER_ID);
        // 案件だけを候補に持つ run は要員へ帰属しない
        long projectRun = fx.aiRun(BASE_ID + 515, TENANT, "{\"masked\":3}", false);
        fx.aiItem(BASE_ID + 516, TENANT, projectRun, 1, "PROJECT", ENGINEER_ID);

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
    }

    @Test
    void 他tenantのrunと他種別のsubjectは返さない() {
        long otherRun = fx.aiRun(BASE_ID + 521, OTHER_TENANT, "{}", false);
        fx.aiItem(BASE_ID + 522, OTHER_TENANT, otherRun, 1, "ENGINEER", ENGINEER_ID);

        assertThat(provider.findBySubject(TENANT, engineer(ENGINEER_ID))).isEmpty();
        assertThat(provider.findBySubject(OTHER_TENANT, engineer(ENGINEER_ID))).hasSize(1);
        assertThat(provider.findBySubject(OTHER_TENANT, PrivacySubjectRef.of(PrivacySubjectType.SYS_USER, 1L))).isEmpty();
    }

    @Test
    void 照会は書込みを行わない() {
        long run = fx.aiRun(BASE_ID + 531, TENANT, "{}", false);
        fx.aiItem(BASE_ID + 532, TENANT, run, 1, "ENGINEER", ENGINEER_ID);
        List<?> runs = fx.snapshot("t_ai_recommendation_run", BASE_ID);

        provider.findBySubject(TENANT, engineer(ENGINEER_ID));

        assertThat(fx.snapshot("t_ai_recommendation_run", BASE_ID)).isEqualTo(runs);
    }

    private static PrivacySubjectRef engineer(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, id);
    }
}
