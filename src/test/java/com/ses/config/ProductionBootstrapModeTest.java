package com.ses.config;

import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本番初回の期限付きbootstrap mode（{@link ProductionBootstrapMode}）の起動条件と実行時の期限判定をDB無しで検証する。
 * 実SecurityFilterChainでのlogin・session制御は {@code ProductionBootstrapIntegrationTest} が見る。
 */
class ProductionBootstrapModeTest {

    /** 2026-11-13T18:00:00+09:00 */
    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    // ---------------------------------------------------------------- 起動条件（prod profile）

    @Test
    void prodで期限未設定なら起動を拒否する() {
        prodRunner()
                .withPropertyValues("app.security.bootstrap.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("[bootstrap]")
                            .hasStackTraceContaining("expires-at");
                });
    }

    @Test
    void prodで期限切れなら起動を拒否する() {
        prodRunner()
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T17:59:59+09:00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("既に過ぎています");
                });
    }

    @Test
    void prodで24時間を超える期限なら起動を拒否する() {
        prodRunner()
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-14T18:00:01+09:00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("24時間以内");
                });
    }

    @Test
    void prodでoffsetの無い期限は起動を拒否する() {
        prodRunner()
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("offset付きISO-8601");
                });
    }

    @Test
    void prodで24時間以内の期限なら起動し期限内だけ有効になる() {
        prodRunner()
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-14T18:00:00+09:00")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionBootstrapMode mode = context.getBean(ProductionBootstrapMode.class);
                    assertThat(mode.isRequested()).isTrue();
                    assertThat(mode.isActive()).isTrue();
                    assertThat(mode.getExpiresAt()).isEqualTo(Instant.parse("2026-11-14T09:00:00Z"));
                });
    }

    @Test
    void prodでも無効なら期限の検査をせず常に無効() {
        prodRunner()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionBootstrapMode mode = context.getBean(ProductionBootstrapMode.class);
                    assertThat(mode.isRequested()).isFalse();
                    assertThat(mode.isActive()).isFalse();
                });
    }

    @Test
    void prod以外ではenabledを設定しても起動を変えず常に無効() {
        for (String expiresAt : new String[]{null, "2020-01-01T00:00:00+09:00", "not-a-date"}) {
            ApplicationContextRunner runner = runner("test")
                    .withPropertyValues("app.security.bootstrap.enabled=true");
            if (expiresAt != null) {
                runner = runner.withPropertyValues("app.security.bootstrap.expires-at=" + expiresAt);
            }
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                ProductionBootstrapMode mode = context.getBean(ProductionBootstrapMode.class);
                assertThat(mode.isRequested()).isFalse();
                assertThat(mode.isActive()).isFalse();
                assertThat(mode.isBootstrapLoginAllowed("admin", admin())).isFalse();
            });
        }
    }

    // ---------------------------------------------------------------- 実行時の期限

    @Test
    void 起動後に期限を過ぎると再起動しなくても無効になる() {
        MutableClock clock = new MutableClock(NOW);
        ProductionBootstrapMode mode = activeMode(clock, "2026-11-13T19:00:00+09:00");
        assertThat(mode.isActive()).isTrue();
        assertThat(mode.isBootstrapLoginAllowed("admin", admin())).isTrue();

        clock.set(Instant.parse("2026-11-13T10:00:00Z"));

        assertThat(mode.isActive()).as("期限ちょうどで無効").isFalse();
        assertThat(mode.isRequested()).as("期限切れの監査判定用に要求状態は残る").isTrue();
        assertThat(mode.isBootstrapLoginAllowed("admin", admin())).isFalse();
    }

    @Test
    void bootstrap_loginは有効な初期管理者adminだけに許す() {
        ProductionBootstrapMode mode = activeMode(new MutableClock(NOW), "2026-11-13T19:00:00+09:00");

        assertThat(mode.isBootstrapLoginAllowed("admin", admin())).isTrue();
        assertThat(mode.isBootstrapLoginAllowed("operator", user("operator", "管理者", 1))).isFalse();
        assertThat(mode.isBootstrapLoginAllowed("ADMIN", admin())).as("大小文字の揺れでは開けない").isFalse();
        assertThat(mode.isBootstrapLoginAllowed("admin", user("ADMIN", "管理者", 1))).isFalse();
        assertThat(mode.isBootstrapLoginAllowed("admin", user("admin", "営業", 1))).isFalse();
        assertThat(mode.isBootstrapLoginAllowed("admin", user("admin", "管理者", 0))).isFalse();
        assertThat(mode.isBootstrapLoginAllowed("admin", null)).isFalse();
    }

    @Test
    void MFA登録はbootstrap_sessionと有効な管理者だけに期限内で許す() {
        MutableClock clock = new MutableClock(NOW);
        ProductionBootstrapMode mode = activeMode(clock, "2026-11-13T19:00:00+09:00");
        MockHttpServletRequest bootstrapRequest = new MockHttpServletRequest();
        bootstrapRequest.getSession(true).setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletRequest plainRequest = new MockHttpServletRequest();

        assertThat(mode.allowsMfaEnrollment(authentication(admin()), bootstrapRequest)).isTrue();
        assertThat(mode.allowsMfaEnrollment(authentication(user("idp-admin", "管理者", 1)), plainRequest))
                .as("OIDCでログインした管理者（break-glass管理者の事前登録）").isTrue();
        assertThat(mode.allowsMfaEnrollment(authentication(user("sales", "営業", 1)), plainRequest)).isFalse();
        assertThat(mode.allowsMfaEnrollment(authentication(user("idp-admin", "管理者", 0)), plainRequest)).isFalse();
        assertThat(mode.allowsMfaEnrollment(null, bootstrapRequest)).isFalse();

        clock.set(Instant.parse("2026-11-13T11:00:00Z"));
        assertThat(mode.allowsMfaEnrollment(authentication(admin()), bootstrapRequest)).isFalse();
        assertThat(mode.allowsMfaEnrollment(authentication(user("idp-admin", "管理者", 1)), plainRequest)).isFalse();
    }

    @Test
    void bootstrap_sessionの印を判定する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(ProductionBootstrapMode.isBootstrapRequest(request)).isFalse();
        request.getSession(true).setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        assertThat(ProductionBootstrapMode.isBootstrapRequest(request)).isTrue();
        assertThat(ProductionBootstrapMode.isBootstrapSession(null)).isFalse();
    }

    @Test
    void 直接生成でも期限の検査規則は同じ() {
        MutableClock clock = new MutableClock(NOW);
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(true);
        assertThatThrownBy(() -> new ProductionBootstrapMode(properties, true, clock).validateAtStartup())
                .isInstanceOf(IllegalStateException.class);
        properties.setExpiresAt(NOW.plus(Duration.ofHours(24)).atOffset(ZoneOffset.ofHours(9)).toString());
        ProductionBootstrapMode exact = new ProductionBootstrapMode(properties, true, clock);
        exact.validateAtStartup();
        assertThat(exact.isActive()).as("ちょうど24時間は許す").isTrue();
    }

    // ---------------------------------------------------------------- helper

    private static ApplicationContextRunner prodRunner() {
        return runner("prod");
    }

    private static ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")))
                .withUserConfiguration(ProductionBootstrapMode.class);
    }

    private static ProductionBootstrapMode activeMode(Clock clock, String expiresAt) {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(true);
        properties.setExpiresAt(expiresAt);
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, true, clock);
        mode.validateAtStartup();
        return mode;
    }

    private static SysUser admin() {
        return user("admin", "管理者", 1);
    }

    private static SysUser user(String username, String role, int status) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(status);
        user.setTenantId("default");
        return user;
    }

    private static UsernamePasswordAuthenticationToken authentication(SysUser user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        return new UsernamePasswordAuthenticationToken(new LoginUser(user, authorities, "default"), null, authorities);
    }

    /** test用の進められる時計。 */
    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
