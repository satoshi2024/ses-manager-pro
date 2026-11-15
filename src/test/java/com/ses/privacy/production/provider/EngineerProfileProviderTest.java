package com.ses.privacy.production.provider;

import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.BASE_ID;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.OTHER_TENANT;
import static com.ses.privacy.production.provider.PrivacyProviderH2Fixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F2 要員 provider（t_engineer）を H2 で確認する: 本人・tenant・allow-list・論理削除・保存名・書込み0。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineerProfileProviderTest {

    @Autowired
    private EngineerProfileProvider provider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataScopeService dataScopeService;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 本人の行だけをallow_listの列で返し論理削除済みの行と保存名も含める() {
        long self = fx.engineer(BASE_ID + 1, TENANT, "要員A", "photo-a.png", false);
        fx.engineer(BASE_ID + 2, TENANT, "要員B", "photo-b.png", false);
        long deleted = fx.engineer(BASE_ID + 3, TENANT, "要員C", "photo-c.png", true);
        long noPhoto = fx.engineer(BASE_ID + 4, TENANT, "要員D", null, false);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, engineer(self));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.rowId()).isEqualTo(self);
            assertThat(record.providerId()).isEqualTo(EngineerProfileProvider.PROVIDER_ID);
            assertThat(record.logicallyDeleted()).isFalse();
            assertThat(record.dataElementIds())
                    .containsExactly(EngineerProfileProvider.PROFILE, EngineerProfileProvider.PHOTO);
            assertThat(record.storedNames()).containsExactly("photo-a.png");
            assertThat(record.fields().get("full_name")).isEqualTo("要員A");
            assertThat(record.fields()).doesNotContainKeys("expected_unit_price", "created_by", "legal_entity_id",
                    "version", "engineer_scope_epoch");
        });
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, EngineerProfileProvider.ENGINEER);

        assertThat(provider.findBySubject(TENANT, engineer(deleted)))
                .singleElement().satisfies(record -> assertThat(record.logicallyDeleted()).isTrue());
        assertThat(provider.resolveStoredNames(TENANT, engineer(deleted))).containsExactly("photo-c.png");
        assertThat(provider.findBySubject(TENANT, engineer(noPhoto)))
                .singleElement().satisfies(record -> {
                    assertThat(record.dataElementIds()).containsExactly(EngineerProfileProvider.PROFILE);
                    assertThat(record.storedNames()).isEmpty();
                });
    }

    @Test
    void 他tenantとtenant不明の行と他種別のsubjectは返さずtenant指定は必須() {
        long other = fx.engineer(BASE_ID + 11, OTHER_TENANT, "他社要員", "photo-x.png", false);
        long unknownTenant = fx.engineer(BASE_ID + 12, null, "tenant不明", "photo-y.png", false);

        assertThat(provider.findBySubject(TENANT, engineer(other))).isEmpty();
        assertThat(provider.findBySubject(TENANT, engineer(unknownTenant))).isEmpty();
        assertThat(provider.resolveStoredNames(TENANT, engineer(other))).isEmpty();
        assertThat(provider.findBySubject(OTHER_TENANT, engineer(other))).hasSize(1);
        assertThat(provider.findBySubject(OTHER_TENANT, PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, other)))
                .isEmpty();
        assertThatThrownBy(() -> provider.findBySubject(" ", engineer(other)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 管理者にはDataScopeServiceが絞り込まず母集団はtenantに属する要員と一致する() {
        PrivacyProviderH2Fixture.authenticateAdmin(TENANT);
        long a = fx.engineer(BASE_ID + 21, TENANT, "要員1", null, false);
        long b = fx.engineer(BASE_ID + 22, TENANT, "要員2", null, true);
        long other = fx.engineer(BASE_ID + 23, OTHER_TENANT, "要員3", null, false);
        long unknownTenant = fx.engineer(BASE_ID + 24, null, "要員4", null, false);

        // privacy-admin は管理者 role だけに割り当てられ、管理者の DataScopeService は母集団を絞らない
        assertThat(dataScopeService.isScoped()).isFalse();
        Set<Long> visible = Stream.of(a, b, other, unknownTenant)
                .filter(id -> !provider.findBySubject(TENANT, engineer(id)).isEmpty())
                .collect(Collectors.toSet());
        // tenant を特定できない行は不可視（V169 の resolver と同じ）、論理削除済みは実体が残るため含める
        assertThat(visible).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void 照会は書込みを行わない() {
        long id = fx.engineer(BASE_ID + 31, TENANT, "要員W", "photo-w.png", false);
        List<?> before = fx.snapshot("t_engineer", BASE_ID);

        provider.findBySubject(TENANT, engineer(id));
        provider.resolveStoredNames(TENANT, engineer(id));

        assertThat(fx.snapshot("t_engineer", BASE_ID)).isEqualTo(before);
    }

    private static PrivacySubjectRef engineer(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, id);
    }
}
