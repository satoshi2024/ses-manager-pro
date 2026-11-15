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

/** F2 アカウント provider（sys_user）を H2 で確認する。パスワード hash・ロック情報は結果に出ない（P3-8）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SysUserProviderTest {

    private static final String SECRET_PASSWORD = "nf07-f2-secret-password-value";

    @Autowired
    private SysUserProvider provider;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyProviderH2Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new PrivacyProviderH2Fixture(jdbc);
    }

    @Test
    void アカウントをallow_listの列で返しパスワードとロック情報は列にも値にも出ない() {
        long self = fx.sysUser(BASE_ID + 201, TENANT, "nf07-f2-user-a", SECRET_PASSWORD);
        fx.sysUser(BASE_ID + 202, TENANT, "nf07-f2-user-b", SECRET_PASSWORD);

        List<PrivacyRecord> records = provider.findBySubject(TENANT, user(self));

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.rowId()).isEqualTo(self);
            assertThat(record.fields().get("username")).isEqualTo("nf07-f2-user-a");
            assertThat(record.fields()).doesNotContainKeys("password", "failed_count", "locked_until",
                    "password_change_required", "actor_authz_epoch");
            assertThat(record.fields().values()).doesNotContain(SECRET_PASSWORD);
            assertThat(record.dataElementIds()).containsExactly(SysUserProvider.ACCOUNT);
            assertThat(record.storedNames()).isEmpty();
        });
        PrivacyProviderH2Fixture.assertOnlyAllowListed(records, SysUserProvider.SYS_USER);
        assertThat(SysUserProvider.SYS_USER.columns()).doesNotContain("password", "failed_count", "locked_until",
                "password_change_required", "actor_authz_epoch");
    }

    @Test
    void 他tenantのアカウントと他種別のsubjectは返さない() {
        long other = fx.sysUser(BASE_ID + 211, OTHER_TENANT, "nf07-f2-user-x", SECRET_PASSWORD);

        assertThat(provider.findBySubject(TENANT, user(other))).isEmpty();
        assertThat(provider.findBySubject(OTHER_TENANT, user(other))).hasSize(1);
        assertThat(provider.findBySubject(OTHER_TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, other)))
                .isEmpty();
    }

    @Test
    void 照会は書込みを行わない() {
        long self = fx.sysUser(BASE_ID + 221, TENANT, "nf07-f2-user-w", SECRET_PASSWORD);
        List<?> before = fx.snapshot("sys_user", BASE_ID);

        provider.findBySubject(TENANT, user(self));

        assertThat(fx.snapshot("sys_user", BASE_ID)).isEqualTo(before);
    }

    private static PrivacySubjectRef user(long id) {
        return PrivacySubjectRef.of(PrivacySubjectType.SYS_USER, id);
    }
}
