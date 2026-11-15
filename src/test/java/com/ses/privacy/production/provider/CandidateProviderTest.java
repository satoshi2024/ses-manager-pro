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

/** F2 候補者 provider（t_candidate / t_candidate_activity）を H2 で確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CandidateProviderTest {

    @Autowired
    private CandidateProvider provider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @Test
    void 候補者と選考履歴をallow_listの列で返し変更者と他の候補者の履歴は返さない() {
        long self = fx.candidate(BASE_ID + 101, TENANT, "候補者A", false);
        long other = fx.candidate(BASE_ID + 102, TENANT, "候補者B", false);
        long activity = fx.candidateActivity(BASE_ID + 103, self, "理由A");
        fx.candidateActivity(BASE_ID + 104, other, "理由B");

        List<PrivacyRecord> records = provider.findBySubject(TENANT, candidate(self));

        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_candidate")).containsExactly(self);
        assertThat(PrivacyProviderH2Fixture.rowIds(records, "t_candidate_activity")).containsExactly(activity);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.dataElementIds()).containsExactly(CandidateProvider.PROFILE);
            assertThat(record.storedNames()).isEmpty();
            assertThat(record.fields()).doesNotContainKeys("changed_by", "created_by", "version");
        });
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, CandidateProvider.CANDIDATE, CandidateProvider.ACTIVITY);
    }

    @Test
    void 他tenantとtenant不明の候補者と論理削除の扱いと他種別のsubject() {
        long otherTenant = fx.candidate(BASE_ID + 111, OTHER_TENANT, "他社候補者", false);
        fx.candidateActivity(BASE_ID + 112, otherTenant, "他社の理由");
        long unknownTenant = fx.candidate(BASE_ID + 113, null, "tenant不明", false);
        long deleted = fx.candidate(BASE_ID + 114, TENANT, "削除済み候補者", true);

        assertThat(provider.findBySubject(TENANT, candidate(otherTenant))).isEmpty();
        assertThat(provider.findBySubject(TENANT, candidate(unknownTenant))).isEmpty();
        assertThat(provider.findBySubject(OTHER_TENANT, candidate(otherTenant))).hasSize(2);
        assertThat(provider.findBySubject(TENANT, candidate(deleted)))
                .singleElement().satisfies(record -> assertThat(record.logicallyDeleted()).isTrue());
        // 候補者IDと同じ値の要員を指定しても候補者の行は返さない（種別をまたいで名寄せしない）
        assertThat(provider.findBySubject(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, deleted))).isEmpty();
    }

    @Test
    void 照会は書込みを行わない() {
        long self = fx.candidate(BASE_ID + 121, TENANT, "候補者W", false);
        fx.candidateActivity(BASE_ID + 122, self, "理由W");
        List<?> candidates = fx.snapshot("t_candidate", BASE_ID);
        List<?> activities = fx.snapshot("t_candidate_activity", BASE_ID);

        provider.findBySubject(TENANT, candidate(self));

        assertThat(fx.snapshot("t_candidate", BASE_ID)).isEqualTo(candidates);
        assertThat(fx.snapshot("t_candidate_activity", BASE_ID)).isEqualTo(activities);
    }

    private static PrivacySubjectRef candidate(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, id);
    }
}
