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

/** F2 取込 provider（t_resume_ingestion / t_bp_availability_ingestion）を H2 で確認する（§3.4.1 D4・D5 の母集団）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestionTextProviderTest {

    private static final long ENGINEER_ID = BASE_ID + 401;
    private static final long CANDIDATE_ID = BASE_ID + 402;

    @Autowired
    private IngestionTextProvider provider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @Test
    void 履歴取込は変換先の要員と候補者のどちらからも導出し論理削除済みと原本の保存名を含める() {
        long converted = fx.resume(BASE_ID + 411, TENANT, "r-1.pdf", ENGINEER_ID, CANDIDATE_ID, false);
        long candidateOnly = fx.resume(BASE_ID + 412, TENANT, "r-2.pdf", null, CANDIDATE_ID, true);
        long purged = fx.resume(BASE_ID + 413, TENANT, null, ENGINEER_ID, null, false);
        fx.resume(BASE_ID + 414, TENANT, "r-4.pdf", null, null, false);

        List<PrivacyRecord> byEngineer = provider.findBySubject(TENANT, of(PrivacySubjectType.ENGINEER, ENGINEER_ID));
        List<PrivacyRecord> byCandidate = provider.findBySubject(TENANT, of(PrivacySubjectType.CANDIDATE, CANDIDATE_ID));

        assertThat(PrivacyProviderH2Fixture.rowIds(byEngineer, "t_resume_ingestion")).containsExactlyInAnyOrder(converted, purged);
        assertThat(PrivacyProviderH2Fixture.rowIds(byCandidate, "t_resume_ingestion"))
                .containsExactlyInAnyOrder(converted, candidateOnly);
        assertThat(provider.resolveStoredNames(TENANT, of(PrivacySubjectType.CANDIDATE, CANDIDATE_ID)))
                .containsExactlyInAnyOrder("r-1.pdf", "r-2.pdf");
        assertThat(byEngineer).filteredOn(r -> r.rowId() == purged).singleElement().satisfies(r -> {
            assertThat(r.dataElementIds()).containsExactly(IngestionTextProvider.RESUME_TEXT);
            assertThat(r.storedNames()).isEmpty();
        });
        assertThat(byCandidate).filteredOn(r -> r.rowId() == candidateOnly).singleElement().satisfies(r -> {
            assertThat(r.logicallyDeleted()).isTrue();
            assertThat(r.dataElementIds())
                    .containsExactly(IngestionTextProvider.RESUME_TEXT, IngestionTextProvider.RESUME_ORIGINAL);
            assertThat(r.fields().get("extracted_text")).isEqualTo("resume text");
            assertThat(r.fields()).doesNotContainKeys("error_message", "ai_provider", "ai_model", "created_by");
        });
        PrivacyProviderH2Fixture.assertOnlyAllowListed(byEngineer, IngestionTextProvider.RESUME);
        PrivacyProviderH2Fixture.assertOnlyAllowListed(byCandidate, IngestionTextProvider.RESUME);
    }

    @Test
    void BP空き取込は変換先のtenantで絞り貼付け取込は実体fileを持たない() {
        long availability = fx.bpAvailability(BASE_ID + 421, TENANT);
        long otherAvailability = fx.bpAvailability(BASE_ID + 422, OTHER_TENANT);
        long file = fx.bpIngestion(BASE_ID + 423, "bp-1.eml", "eml", availability, false);
        long pasted = fx.bpIngestion(BASE_ID + 424, "paste-marker", "PASTE", availability, true);
        fx.bpIngestion(BASE_ID + 425, "bp-x.eml", "eml", otherAvailability, false);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, of(PrivacySubjectType.BP_AVAILABILITY, availability));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_bp_availability_ingestion"))
                .containsExactlyInAnyOrder(file, pasted);
        assertThat(provider.resolveStoredNames(TENANT, of(PrivacySubjectType.BP_AVAILABILITY, availability)))
                .containsExactly("bp-1.eml");
        assertThat(records).filteredOn(r -> r.rowId() == pasted).singleElement().satisfies(r -> {
            assertThat(r.logicallyDeleted()).isTrue();
            assertThat(r.dataElementIds()).containsExactly(IngestionTextProvider.BP_TEXT);
        });
        assertThat(provider.findBySubject(TENANT, of(PrivacySubjectType.BP_AVAILABILITY, otherAvailability))).isEmpty();
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, IngestionTextProvider.BP);
    }

    @Test
    void 他tenantの履歴取込とtenant不明の行は返さない() {
        fx.resume(BASE_ID + 431, OTHER_TENANT, "o.pdf", ENGINEER_ID, null, false);
        fx.resume(BASE_ID + 432, null, "n.pdf", ENGINEER_ID, null, false);

        assertThat(provider.findBySubject(TENANT, of(PrivacySubjectType.ENGINEER, ENGINEER_ID))).isEmpty();
        assertThat(provider.resolveStoredNames(OTHER_TENANT, of(PrivacySubjectType.ENGINEER, ENGINEER_ID)))
                .containsExactly("o.pdf");
        assertThat(provider.findBySubject(TENANT, of(PrivacySubjectType.SYS_USER, ENGINEER_ID))).isEmpty();
    }

    @Test
    void 照会は書込みを行わない() {
        fx.resume(BASE_ID + 441, TENANT, "w.pdf", ENGINEER_ID, null, false);
        long availability = fx.bpAvailability(BASE_ID + 442, TENANT);
        fx.bpIngestion(BASE_ID + 443, "w.eml", "eml", availability, false);
        List<?> resumes = fx.snapshot("t_resume_ingestion", BASE_ID);
        List<?> bp = fx.snapshot("t_bp_availability_ingestion", BASE_ID);

        provider.findBySubject(TENANT, of(PrivacySubjectType.ENGINEER, ENGINEER_ID));
        provider.findBySubject(TENANT, of(PrivacySubjectType.BP_AVAILABILITY, availability));

        assertThat(fx.snapshot("t_resume_ingestion", BASE_ID)).isEqualTo(resumes);
        assertThat(fx.snapshot("t_bp_availability_ingestion", BASE_ID)).isEqualTo(bp);
    }

    private static PrivacySubjectRef of(PrivacySubjectType type, long id) {
        return PrivacySubjectRef.of(type, id);
    }
}
