package com.ses.config;

import com.ses.entity.SysUser;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 本番初回起動の期限付きbootstrap mode（配備決定: bootstrap方式A）。
 *
 * <p>空DBの本番には {@code admin} 1件しか居らず、IdP・外部identity・break-glass管理者・MFA登録も無いため、
 * 通常のprod設定（local login無効・break-glassはincident必須）では最初の1人がログインできない。
 * 本modeは期限付きで次だけを開ける。
 * <ul>
 *   <li>初期管理者 {@value #BOOTSTRAP_USERNAME} のlocal password login（他のユーザーのlocal loginは引き続き拒否）。</li>
 *   <li>そのsession（bootstrap session）は、パスワード変更 → MFA登録の順に強制し、完了後もbootstrapに必要な入口
 *       （管理者の作成・権限group、IdPと外部identityの登録、MFA、パスワード変更、logout）だけに制限する
 *       （{@link BootstrapSessionFilter}）。</li>
 *   <li>bootstrap中はOIDCでログインした管理者もMFA（TOTP）を登録できる（break-glass管理者の事前登録のため）。</li>
 *   <li>{@link ProductionSecurityConfigurationValidator} はbreak-glass登録の検査だけを省略する。</li>
 * </ul>
 *
 * <p>prod profileの時だけ意味を持つ。prod以外では {@code enabled} を設定しても何もしない（既存の挙動を変えない）。
 * prodで {@code enabled=true} の場合、期限が未設定・期限切れ・現在から{@value #MAX_WINDOW_HOURS}時間超なら
 * bean生成時（Tomcatのport開始前）に起動を拒否する。起動後に期限を過ぎた場合は、再起動しなくても
 * bootstrap loginとbootstrap sessionの要求を拒否する（{@link #isActive()} を要求ごとに評価する）。
 */
@Component
@EnableConfigurationProperties(ProductionBootstrapProperties.class)
public class ProductionBootstrapMode {

    private static final Logger log = LoggerFactory.getLogger(ProductionBootstrapMode.class);

    /** bootstrap loginを許可する唯一のusername（V2で投入される初期管理者）。 */
    public static final String BOOTSTRAP_USERNAME = "admin";

    /** bootstrap loginで発行したsessionの印。 */
    public static final String SESSION_ATTRIBUTE = "SES_BOOTSTRAP_SESSION";

    /** 期限として許す最大の長さ（時間）。 */
    static final long MAX_WINDOW_HOURS = 24;

    private static final Duration MAX_WINDOW = Duration.ofHours(MAX_WINDOW_HOURS);
    private static final String ADMIN_ROLE = "管理者";

    private final ProductionBootstrapProperties properties;
    private final boolean production;
    private final Clock clock;
    private volatile Instant expiresAt;

    @Autowired
    public ProductionBootstrapMode(ProductionBootstrapProperties properties, Environment environment, Clock clock) {
        this(properties, environment.acceptsProfiles(Profiles.of("prod")), clock);
    }

    /** test用。prod判定と時計を明示して生成する。 */
    ProductionBootstrapMode(ProductionBootstrapProperties properties, boolean production, Clock clock) {
        this.properties = properties;
        this.production = production;
        this.clock = clock;
    }

    /** 期限の検査。prodで有効化されているのに期限が不正なら起動を拒否する。 */
    @PostConstruct
    void validateAtStartup() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!production) {
            log.warn("[bootstrap] app.security.bootstrap.enabled はprod profile以外では無視します");
            return;
        }
        Instant parsed = parseExpiresAt(properties.getExpiresAt());
        Instant now = Instant.now(clock);
        if (!parsed.isAfter(now)) {
            throw new IllegalStateException("[bootstrap] app.security.bootstrap.expires-at が既に過ぎています。"
                    + "bootstrap modeは起動しません（期限を設定し直すか、bootstrapを無効にしてください）");
        }
        if (Duration.between(now, parsed).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalStateException("[bootstrap] app.security.bootstrap.expires-at は現在から"
                    + MAX_WINDOW_HOURS + "時間以内でなければなりません。bootstrap modeは起動しません");
        }
        this.expiresAt = parsed;
        log.warn("[bootstrap] 期限付きbootstrap modeが有効です（期限: {}）。初期管理者 {} のlocal loginだけを許可し、"
                + "break-glass登録の起動時検査を省略します。完了後は必ず無効にして再起動してください",
                parsed, BOOTSTRAP_USERNAME);
    }

    private static Instant parseExpiresAt(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("[bootstrap] app.security.bootstrap.enabled=true には"
                    + " app.security.bootstrap.expires-at（offset付きISO-8601）の指定が必要です。bootstrap modeは起動しません");
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("[bootstrap] app.security.bootstrap.expires-at はoffset付きISO-8601"
                    + "（例: 2026-11-14T18:00:00+09:00）で指定してください。bootstrap modeは起動しません", e);
        }
    }

    /** prodで有効化が要求されているか（期限内かは問わない。期限切れの拒否と監査の判定に使う）。 */
    public boolean isRequested() {
        return production && properties.isEnabled();
    }

    /** prodで有効化され、かつ現在が期限内か。要求ごとに評価し、期限を過ぎたら再起動を待たずにfalseになる。 */
    public boolean isActive() {
        Instant deadline = expiresAt;
        return isRequested() && deadline != null && Instant.now(clock).isBefore(deadline);
    }

    /** 検査済みの期限（無効・未検査ならnull）。 */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isBootstrapUsername(String username) {
        return BOOTSTRAP_USERNAME.equals(username);
    }

    /**
     * bootstrap期間中に、指定usernameのlocal password loginを許すか。
     * 入力usernameとDBのusernameの両方が {@value #BOOTSTRAP_USERNAME} と完全一致する有効な管理者だけを許す
     * （DBの照合順序による大小文字の揺れでは開けない）。
     */
    public boolean isBootstrapLoginAllowed(String username, SysUser user) {
        return isActive() && isBootstrapUsername(username) && user != null
                && isBootstrapUsername(user.getUsername()) && ADMIN_ROLE.equals(user.getRole())
                && Integer.valueOf(1).equals(user.getStatus());
    }

    /**
     * bootstrap期間中に、MFA（TOTP）の登録・検証APIを使ってよいか。
     * bootstrap session本人、またはOIDC等でログインした有効な管理者（break-glass管理者の事前登録）に限る。
     */
    public boolean allowsMfaEnrollment(Authentication authentication, HttpServletRequest request) {
        if (!isActive() || authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        if (request != null && isBootstrapSession(request.getSession(false))) {
            return true;
        }
        if (!(authentication.getPrincipal() instanceof LoginUser loginUser) || loginUser.getSysUser() == null
                || !Integer.valueOf(1).equals(loginUser.getSysUser().getStatus())) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + ADMIN_ROLE).equals(authority.getAuthority()));
    }

    /** bootstrap loginで発行したsessionか。 */
    public static boolean isBootstrapSession(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ATTRIBUTE));
    }

    /** bootstrap loginで発行したsessionへの要求か。 */
    public static boolean isBootstrapRequest(HttpServletRequest request) {
        return request != null && isBootstrapSession(request.getSession(false));
    }
}
