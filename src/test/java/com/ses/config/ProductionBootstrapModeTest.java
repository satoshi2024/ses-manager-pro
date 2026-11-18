package com.ses.config;

import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        bootstrapRequest.getSession().setAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE, 1L);
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
        assertThatThrownBy(() -> new ProductionBootstrapMode(properties, true, clock, notCompletedLatch())
                .validateAtStartup())
                .isInstanceOf(IllegalStateException.class);
        properties.setExpiresAt(NOW.plus(Duration.ofHours(24)).atOffset(ZoneOffset.ofHours(9)).toString());
        ProductionBootstrapMode exact = new ProductionBootstrapMode(properties, true, clock, notCompletedLatch());
        exact.validateAtStartup();
        assertThat(exact.isActive()).as("ちょうど24時間は許す").isTrue();
    }

    @Test
    void bootstrap_sessionの印は認証主体に結び付ける() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        assertThat(ProductionBootstrapMode.isBootstrapSessionOf(session, authentication(admin())))
                .as("本人のuser idが結び付いていない印は認めない").isFalse();

        session.setAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE, 1L);
        assertThat(ProductionBootstrapMode.isBootstrapSessionOf(session, authentication(admin()))).isTrue();

        SysUser otherAdmin = user("admin", "管理者", 1);
        otherAdmin.setId(2L);
        assertThat(ProductionBootstrapMode.isBootstrapSessionOf(session, authentication(otherAdmin)))
                .as("別のuser idの主体には引き継がない").isFalse();
        assertThat(ProductionBootstrapMode.isBootstrapSessionOf(session, authentication(user("idp-admin", "管理者", 1))))
                .as("admin以外の主体には引き継がない").isFalse();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        DefaultOAuth2User oidcUser = new DefaultOAuth2User(authorities, Map.of("sub", "admin"), "sub");
        assertThat(ProductionBootstrapMode.isBootstrapSessionOf(session,
                new OAuth2AuthenticationToken(oidcUser, authorities, "enterprise")))
                .as("OIDC loginの主体には引き継がない").isFalse();

        // 別の主体へ引き継がれた印は、MFA登録の許可にも使わない（営業ではOIDC管理者の経路にも当たらない）
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        ProductionBootstrapMode mode = activeMode(new MutableClock(NOW), "2026-11-13T19:00:00+09:00");
        SysUser sales = user("admin", "営業", 1);
        sales.setId(3L);
        assertThat(mode.allowsMfaEnrollment(authentication(sales), request)).isFalse();
    }

    // ---------------------------------------------------------------- 完了marker（review P2-A）

    @Test
    void 完了済みでreopen_ticketが無ければ起動を拒否する() {
        ProductionBootstrapCompletionLatch latch = completedLatch();
        prodRunner(latch)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("[bootstrap] 初回bootstrapは完了済みです")
                            .hasStackTraceContaining("reopen-ticket");
                });
        verify(latch, never()).recordReopened(any(), any(), any());
    }

    @Test
    void 完了済みでも承認ticketがあれば再開を必須監査してから起動する() {
        ProductionBootstrapCompletionLatch latch = completedLatch();
        prodRunner(latch)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00",
                        "app.security.bootstrap.reopen-ticket= SEC-1234 ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionBootstrapMode mode = context.getBean(ProductionBootstrapMode.class);
                    assertThat(mode.isActive()).isTrue();
                    assertThat(mode.getReopenTicket()).isEqualTo("SEC-1234");
                });
        verify(latch).recordReopened("SEC-1234", Instant.parse("2026-11-13T11:00:00Z"), COMPLETED_AT);
    }

    @Test
    void 再開の必須監査を永続化できなければ起動を拒否する() {
        ProductionBootstrapCompletionLatch latch = completedLatch();
        doThrow(new IllegalStateException("audit unavailable")).when(latch).recordReopened(any(), any(), any());
        prodRunner(latch)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00",
                        "app.security.bootstrap.reopen-ticket=CHG0012345")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("BOOTSTRAP_REOPENED");
                });
    }

    @Test
    void 未完了なら承認ticket無しで従来どおり起動し再開の監査をしない() {
        ProductionBootstrapCompletionLatch latch = notCompletedLatch();
        prodRunner(latch)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionBootstrapMode mode = context.getBean(ProductionBootstrapMode.class);
                    assertThat(mode.isActive()).isTrue();
                    assertThat(mode.getReopenTicket()).isNull();
                });
        verify(latch).findCompletedAt();
        verify(latch, never()).recordReopened(any(), any(), any());
    }

    @Test
    void 完了markerを確認できなければ起動を拒否する() {
        ProductionBootstrapCompletionLatch unreadable = mock(ProductionBootstrapCompletionLatch.class);
        when(unreadable.findCompletedAt()).thenThrow(new IllegalStateException("db unavailable"));
        prodRunner(unreadable)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("完了marker");
                });

        // latch自体が無いcontextでも起動しない（fail-closed）
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")))
                .withUserConfiguration(ProductionBootstrapMode.class)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 承認ticketの形式が不正なら起動を拒否する() {
        for (String ticket : new String[]{"SEC", "1234-SEC", "SEC 1234", "SEC-1234;drop", "ABCDEFG"}) {
            prodRunner(completedLatch())
                    .withPropertyValues("app.security.bootstrap.enabled=true",
                            "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00",
                            "app.security.bootstrap.reopen-ticket=" + ticket)
                    .run(context -> {
                        assertThat(context).as("ticket=%s", ticket).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("reopen-ticket の形式");
                    });
        }
        assertThat(ProductionBootstrapMode.REOPEN_TICKET_PATTERN.matcher("SEC-1234").matches()).isTrue();
        assertThat(ProductionBootstrapMode.REOPEN_TICKET_PATTERN.matcher("CHG0012345").matches()).isTrue();
    }

    @Test
    void prod以外と無効時は完了markerを参照しない() {
        ProductionBootstrapCompletionLatch latch = completedLatch();
        runner("test", latch)
                .withPropertyValues("app.security.bootstrap.enabled=true",
                        "app.security.bootstrap.expires-at=2026-11-13T20:00:00+09:00")
                .run(context -> assertThat(context).hasNotFailed());
        prodRunner(latch)
                .withPropertyValues("app.security.bootstrap.reopen-ticket=SEC-1234")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionBootstrapMode.class).isActive()).isFalse();
                });
        verifyNoInteractions(latch);
    }

    // ---------------------------------------------------------------- helper

    /** 完了markerの値（test用）。 */
    private static final String COMPLETED_AT = "2026-11-10T12:00:00+09:00";

    private static ApplicationContextRunner prodRunner() {
        return runner("prod");
    }

    private static ApplicationContextRunner prodRunner(ProductionBootstrapCompletionLatch latch) {
        return runner("prod", latch);
    }

    private static ApplicationContextRunner runner(String profile) {
        return runner(profile, notCompletedLatch());
    }

    private static ApplicationContextRunner runner(String profile, ProductionBootstrapCompletionLatch latch) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")))
                .withBean(ProductionBootstrapCompletionLatch.class, () -> latch)
                .withUserConfiguration(ProductionBootstrapMode.class);
    }

    /** 初回bootstrap（完了markerが無い）を返すlatch。 */
    static ProductionBootstrapCompletionLatch notCompletedLatch() {
        ProductionBootstrapCompletionLatch latch = mock(ProductionBootstrapCompletionLatch.class);
        when(latch.findCompletedAt()).thenReturn(Optional.empty());
        return latch;
    }

    private static ProductionBootstrapCompletionLatch completedLatch() {
        ProductionBootstrapCompletionLatch latch = mock(ProductionBootstrapCompletionLatch.class);
        when(latch.findCompletedAt()).thenReturn(Optional.of(COMPLETED_AT));
        return latch;
    }

    private static ProductionBootstrapMode activeMode(Clock clock, String expiresAt) {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(true);
        properties.setExpiresAt(expiresAt);
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, true, clock, notCompletedLatch());
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
