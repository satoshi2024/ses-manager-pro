package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.hold.HoldSubjectResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 A1H-2: 破壊的経路ごとの本人導出（{@link HoldSubjectResolver}、production-bridge-phased-plan.md §3.4.1）が F2 provider の
 * 宣言そのものを使うことを、実 provider と H2 の testdb で確かめる（D1〜D3 の文書規則、D4 の和集合、D6 の AiLog）。
 * 規則を resolver で書き直していないので、provider の規則が変われば経路の判定も同じに変わる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HoldSubjectResolverPathTest {

    private static final long ENGINEER_ID = BASE_ID + 601;
    private static final long CANDIDATE_ID = BASE_ID + 602;
    private static final long OTHER_ENGINEER_ID = BASE_ID + 603;

    @Autowired
    private HoldSubjectResolver resolver;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
        fx.engineer(ENGINEER_ID, TENANT, "保全 太郎", null, false);
        fx.engineer(OTHER_ENGINEER_ID, TENANT, "第三者 花子", null, false);
        fx.candidate(CANDIDATE_ID, TENANT, "候補 次郎", false);
    }

    @Test
    void D1からD3の文書は要員のlinkと履歴取込の候補者の両方から導出し帰属しない文書は返さない() {
        long contract = fx.document(BASE_ID + 611, TENANT, "CONTRACT", false);
        fx.link(BASE_ID + 611, TENANT, contract, "ENGINEER", ENGINEER_ID, true);
        long resume = fx.resume(BASE_ID + 621, TENANT, null, null, CANDIDATE_ID, false);
        long copy = fx.document(BASE_ID + 612, TENANT, "RESUME_INGESTION", false);
        fx.link(BASE_ID + 612, TENANT, copy, "RESUME_INGESTION", resume, false);
        long imported = fx.document(BASE_ID + 613, TENANT, "IMPORT", false);
        fx.link(BASE_ID + 613, TENANT, imported, "IMPORT_JOB", 1L, false);

        Map<Long, Set<String>> subjects = resolver.documentSubjects(TENANT,
                Set.of("ENGINEER:" + ENGINEER_ID, "CANDIDATE:" + CANDIDATE_ID));

        assertThat(subjects.get(contract)).as("論理削除済みの link も含める").containsExactly("ENGINEER:" + ENGINEER_ID);
        assertThat(subjects.get(copy)).containsExactly("CANDIDATE:" + CANDIDATE_ID);
        assertThat(subjects).doesNotContainKey(imported);
    }

    @Test
    void D4は変換済みの要員と候補者の和集合でどちらのholdでも当たり帰属しない行は返さない() {
        long converted = fx.resume(BASE_ID + 631, TENANT, "a1h2-631.pdf", ENGINEER_ID, CANDIDATE_ID, false);
        long candidateOnly = fx.resume(BASE_ID + 632, TENANT, "a1h2-632.pdf", null, CANDIDATE_ID, false);
        long unconverted = fx.resume(BASE_ID + 633, TENANT, "a1h2-633.pdf", null, null, false);

        assertThat(resolver.resumeIngestionSubjects(TENANT, Set.of("CANDIDATE:" + CANDIDATE_ID)).keySet())
                .containsExactlyInAnyOrder(converted, candidateOnly);
        assertThat(resolver.resumeIngestionSubjects(TENANT, Set.of("ENGINEER:" + ENGINEER_ID)).keySet())
                .as("候補者だけの hold でも変換済みの行は消さない（優先順位にしない）").containsExactly(converted);
        assertThat(resolver.resumeIngestionSubjects(TENANT, Set.of("ENGINEER:" + ENGINEER_ID, "CANDIDATE:" + CANDIDATE_ID)))
                .doesNotContainKey(unconverted);
    }

    @Test
    void D6は推薦結果の1件目の要員のrunだけを本人のrunとし2件目以降の要員は第三者として扱う() {
        long own = fx.aiRun(BASE_ID + 641, TENANT, "{\"summary\":\"masked\"}", false);
        fx.aiItem(BASE_ID + 641, TENANT, own, 1, "ENGINEER", ENGINEER_ID);
        long thirdParty = fx.aiRun(BASE_ID + 642, TENANT, "{\"summary\":\"masked\"}", false);
        fx.aiItem(BASE_ID + 642, TENANT, thirdParty, 1, "ENGINEER", OTHER_ENGINEER_ID);
        fx.aiItem(BASE_ID + 643, TENANT, thirdParty, 2, "ENGINEER", ENGINEER_ID);

        assertThat(resolver.aiRecommendationRunSubjects(TENANT, Set.of("ENGINEER:" + ENGINEER_ID)).keySet())
                .containsExactly(own);
    }

    @Test
    void SUBJECT_holdが無ければprovider_を呼ばず空で解釈できないsubject参照は例外にする() {
        assertThat(resolver.documentSubjects(TENANT, Set.of())).isEmpty();
        assertThatThrownBy(() -> resolver.documentSubjects(TENANT, Set.of("ENGINEER:abc")))
                .isInstanceOf(IllegalStateException.class);
    }
}
