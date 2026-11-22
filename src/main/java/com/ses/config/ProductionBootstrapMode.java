package com.ses.config;

import com.ses.entity.SysUser;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 本番初回起動の期限付きbootstrap mode（配備決定: bootstrap方式A）。
 *
 * <p>空DBの本番には {@code admin} 1件しか居らず、IdP・外部identity・break-glass管理者・MFA登録も無いため、
 * 通常のprod設定（local login無効・break-glassはincident必須）では最初の1人がログインできない。
 * 本modeは期限付きで次だけを開ける。
 * <ul>
 *   <li>初期管理者 {@value #BOOTSTRAP_USERNAME} のlocal password login（他のユーザーのlocal loginは引き続き拒否）。</li>
 *   <li>そのsession（bootstrap session）は、パスワード変更 → MFA登録の順に強制し、完了後もbootstrapに必要な入口
 *       （管理者の作成、権限groupとrole別menuの参照、IdPと外部identityの登録、MFA、パスワード変更、logout）だけに制限する
 *       （{@link BootstrapSessionFilter}）。</li>
 *   <li>bootstrap中はOIDCでログインした管理者もMFA（TOTP）を登録できる（break-glass管理者の事前登録のため）。</li>
 *   <li>{@link ProductionSecurityConfigurationValidator} はbreak-glass登録の検査だけを省略する。</li>
 * </ul>
 *
 * <p>prod profileの時だけ意味を持つ。prod以外では {@code enabled} を設定しても何もしない（既存の挙動を変えない）。
 * prodで {@code enabled=true} の場合、期限が未設定・期限切れ・現在から{@value #MAX_WINDOW_HOURS}時間超なら
 * bean生成時（Tomcatのport開始前）に起動を拒否する。起動後に期限を過ぎた場合は、再起動しなくても
 * bootstrap loginとbootstrap sessionの要求を拒否する（{@link #isActive()} を要求ごとに評価する）。
 *
 * <p>初回bootstrapの完了後（{@link ProductionBootstrapCompletionLatch} の完了markerが有る状態）は、承認済みの変更ticket
 * （{@code app.security.bootstrap.reopen-ticket}）が無ければ起動を拒否する。ticketが有れば必須監査
 * {@code BOOTSTRAP_REOPENED}（ticket・期限）を残してから起動し、監査を永続化できなければ起動を拒否する（review P2-A）。
 * 完了markerを確認できない場合（latch不在・DBを読めない）も起動を拒否する（fail-closed）。
 *
 * <p>再開の必須監査は bean 生成時（{@code @PostConstruct}）ではなく、起動の最後の {@link ApplicationRunner}（main の thread。
 * backup の書込み静止の startup permit の下）で書く（RB-QUIESCE 2a review P1-1・design.md L-13）。prod の profile では静止応答の
 * 登録の前（UNARMED）に業務の接続が読取り専用になり、bean 生成時の書込みは失敗するため。Tomcat はこの runner より前に要求を
 * 受け始めるので、監査を書けるまでは再開を有効にしない（{@link #isActive()} は false）。監査を書けなければ従来どおり起動を
 * 拒否する。期限・ticket の形式・完了 marker の確認（読むだけ）は bean 生成時のまま行う。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(ProductionBootstrapProperties.class)
public class ProductionBootstrapMode implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionBootstrapMode.class);

    /** bootstrap loginを許可する唯一のusername（V2で投入される初期管理者）。 */
    public static final String BOOTSTRAP_USERNAME = "admin";

    /** bootstrap loginで発行したsessionの印。 */
    public static final String SESSION_ATTRIBUTE = "SES_BOOTSTRAP_SESSION";

    /**
     * bootstrap loginの本人（{@code sys_user.id}）。印をsessionだけでなく認証主体へ結び付け、同じブラウザで別の主体が
     * loginした場合に印を引き継がせない（review P3-a）。
     */
    public static final String SESSION_USER_ATTRIBUTE = "SES_BOOTSTRAP_USER_ID";

    /** 期限として許す最大の長さ（時間）。 */
    static final long MAX_WINDOW_HOURS = 24;

    /** 再開ticketの形式（英字で始まり、英数字と {@code - _ . : # /} からなる4〜64文字で、数字を1文字以上含む）。 */
    static final Pattern REOPEN_TICKET_PATTERN = Pattern.compile("(?=[^0-9]*[0-9])[A-Za-z][A-Za-z0-9._:#/-]{3,63}");

    private static final Duration MAX_WINDOW = Duration.ofHours(MAX_WINDOW_HOURS);
    private static final String ADMIN_ROLE = "管理者";

    private final ProductionBootstrapProperties properties;
    private final boolean production;
    private final Clock clock;
    private final Supplier<ProductionBootstrapCompletionLatch> completionLatchSupplier;
    private volatile Instant expiresAt;
    private volatile String reopenTicket;
    /** 監査を書く前の再開（bean 生成時に検査し、{@link #run} で監査を書いてから有効にする）。 */
    private volatile PendingReopen pendingReopen;

    /** 再開の監査に書く値。 */
    private record PendingReopen(String ticket, Instant deadline, String completedAt) {
    }

    /**
     * 完了marker（{@link ProductionBootstrapCompletionLatch}）は、prodでbootstrapを有効化した時だけ取得する
     * （prod以外・無効時はDBに触れない）。
     */
    @Autowired
    public ProductionBootstrapMode(ProductionBootstrapProperties properties, Environment environment, Clock clock,
                                   ObjectProvider<ProductionBootstrapCompletionLatch> completionLatchProvider) {
        this.properties = properties;
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
        this.clock = clock;
        this.completionLatchSupplier = completionLatchProvider::getIfAvailable;
    }

    /** test用。prod判定・時計・完了markerを明示して生成する（latchがnullなら、prodで有効化すると起動を拒否する）。 */
    ProductionBootstrapMode(ProductionBootstrapProperties properties, boolean production, Clock clock,
                            ProductionBootstrapCompletionLatch completionLatch) {
        this.properties = properties;
        this.production = production;
        this.clock = clock;
        this.completionLatchSupplier = () -> completionLatch;
    }

    /**
     * 期限と再開承認の検査（読むだけ）。prodで有効化されているのに期限が不正、または完了済みなのに承認ticketが無い
     * 場合は起動を拒否する。再開の必須監査は {@link #run} で書く。
     */
    @PostConstruct
    void validateAtStartup() {
        if (!properties.isEnabled()) {
            if (production && StringUtils.hasText(properties.getReopenTicket())) {
                log.warn("[bootstrap] bootstrapが無効のため app.security.bootstrap.reopen-ticket は使いません。設定を外してください");
            }
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
        String ticket = parseReopenTicket(properties.getReopenTicket());
        Optional<String> completedAt = requireReopenApprovalIfCompleted(ticket);
        if (completedAt.isPresent()) {
            // 再開の必須監査は起動の最後（run）に書き、書けるまで再開を有効にしない（review P1-1）
            this.pendingReopen = new PendingReopen(ticket, parsed, completedAt.get());
            log.warn("[bootstrap] 完了済みのbootstrapを承認ticket {} で再開します。必須監査（BOOTSTRAP_REOPENED）を"
                    + "起動の最後に書いてから有効にします（期限: {}）", ticket, parsed);
            return;
        }
        this.reopenTicket = null;
        this.expiresAt = parsed;
        logActivated(parsed);
    }

    /**
     * 起動の最後（{@link ApplicationRunner}。main の thread で backup の書込み静止の startup permit を持つ）に、bean 生成時に
     * 検査した再開の必須監査を書き、書けたら再開を有効にする。書けなければ起動を拒否する（fail-closed）。
     * {@link ProductionSecurityConfigurationValidator} もこの判定の前に呼ぶ（何度呼んでも1回だけ書く）。
     */
    @Override
    public void run(ApplicationArguments args) {
        completePendingReopen();
    }

    /** 保留中の再開があれば必須監査を書いて有効にする（冪等）。 */
    public synchronized void completePendingReopen() {
        PendingReopen pending = pendingReopen;
        if (pending == null) {
            return;
        }
        ProductionBootstrapCompletionLatch latch = completionLatchSupplier.get();
        if (latch == null) {
            throw new IllegalStateException("[bootstrap] 初回bootstrapの完了markerを確認できないため、bootstrap modeは起動しません");
        }
        try {
            latch.recordReopened(pending.ticket(), pending.deadline(), pending.completedAt());
        } catch (RuntimeException e) {
            throw new IllegalStateException("[bootstrap] bootstrap再開の必須監査（BOOTSTRAP_REOPENED）を永続化できないため、"
                    + "bootstrap modeは起動しません", e);
        }
        this.pendingReopen = null;
        this.reopenTicket = pending.ticket();
        this.expiresAt = pending.deadline();
        log.warn("[bootstrap] 完了済みのbootstrapを承認ticket {} で再開しました（期限: {}、初回完了: {}）",
                pending.ticket(), pending.deadline(), pending.completedAt());
        logActivated(pending.deadline());
    }

    private static void logActivated(Instant deadline) {
        log.warn("[bootstrap] 期限付きbootstrap modeが有効です（期限: {}）。初期管理者 {} のlocal loginだけを許可し、"
                + "break-glass登録の起動時検査を省略します。完了後は必ず無効にして再起動してください",
                deadline, BOOTSTRAP_USERNAME);
    }

    /**
     * 初回bootstrapの完了markerが有れば、承認済みticketを要求する（読むだけ。再開の必須監査は {@link #run} で書く）。
     *
     * @return 完了済みのbootstrapを再開する場合は完了日時（初回bootstrapなら空）
     */
    private Optional<String> requireReopenApprovalIfCompleted(String ticket) {
        ProductionBootstrapCompletionLatch latch = completionLatchSupplier.get();
        if (latch == null) {
            throw new IllegalStateException("[bootstrap] 初回bootstrapの完了markerを確認できないため、bootstrap modeは起動しません");
        }
        Optional<String> completedAt;
        try {
            completedAt = latch.findCompletedAt();
        } catch (RuntimeException e) {
            throw new IllegalStateException("[bootstrap] 初回bootstrapの完了marker（m_system_config: "
                    + ProductionBootstrapCompletionLatch.CONFIG_KEY + "）を読めないため、bootstrap modeは起動しません", e);
        }
        if (completedAt.isEmpty()) {
            if (ticket != null) {
                log.warn("[bootstrap] 初回bootstrapの完了markerが無いため初回bootstrapとして起動します"
                        + "（app.security.bootstrap.reopen-ticket は使いません）");
            }
            return Optional.empty();
        }
        if (ticket == null) {
            throw new IllegalStateException("[bootstrap] 初回bootstrapは完了済みです（completed-at="
                    + (completedAt.get().isEmpty() ? "不明" : completedAt.get()) + "）。本番稼働後のbootstrap再開は"
                    + "承認不要のbreak-glassに等しいため、承認済みの変更ticketを app.security.bootstrap.reopen-ticket"
                    + "（SECURITY_BOOTSTRAP_REOPEN_TICKET）に指定しない限り、bootstrap modeは起動しません");
        }
        return completedAt;
    }

    private static String parseReopenTicket(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String ticket = value.trim();
        if (!REOPEN_TICKET_PATTERN.matcher(ticket).matches()) {
            throw new IllegalStateException("[bootstrap] app.security.bootstrap.reopen-ticket の形式が不正です"
                    + "（英字で始まり、英数字と - _ . : # / からなる4〜64文字で、数字を1文字以上含む。例: SEC-1234）。"
                    + "bootstrap modeは起動しません");
        }
        return ticket;
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

    /** 完了済みのbootstrapを再開した場合の承認ticket（初回bootstrap・無効ならnull）。 */
    public String getReopenTicket() {
        return reopenTicket;
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
     * bootstrap session本人（印が現在の認証主体に結び付いているもの）、またはOIDC等でログインした有効な管理者
     * （break-glass管理者の事前登録）に限る。
     */
    public boolean allowsMfaEnrollment(Authentication authentication, HttpServletRequest request) {
        if (!isActive() || authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        if (request != null && isBootstrapSessionOf(request.getSession(false), authentication)) {
            return true;
        }
        if (!(authentication.getPrincipal() instanceof LoginUser loginUser) || loginUser.getSysUser() == null
                || !Integer.valueOf(1).equals(loginUser.getSysUser().getStatus())) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + ADMIN_ROLE).equals(authority.getAuthority()));
    }

    /**
     * bootstrap loginで発行したsessionの印が有るか（認証主体との結び付きは見ない）。
     * 期限切れの失効・監査の対象判定に使う（印が有れば制限・失効の側へ倒す）。入口や権限の許可には
     * {@link #isBootstrapSessionOf(HttpSession, Authentication)} を使う。
     */
    public static boolean isBootstrapSession(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ATTRIBUTE));
    }

    /**
     * bootstrap loginで発行したsessionで、かつ現在の認証主体がそのbootstrap loginの本人か（review P3-a）。
     * local password login（{@link UsernamePasswordAuthenticationToken}）の {@value #BOOTSTRAP_USERNAME} で、
     * {@code sys_user.id} がlogin時に結び付けた {@link #SESSION_USER_ATTRIBUTE} と一致する場合だけtrue。
     */
    public static boolean isBootstrapSessionOf(HttpSession session, Authentication authentication) {
        if (!isBootstrapSession(session) || !(authentication instanceof UsernamePasswordAuthenticationToken)
                || !(authentication.getPrincipal() instanceof LoginUser loginUser) || loginUser.getSysUser() == null) {
            return false;
        }
        Long userId = loginUser.getSysUser().getId();
        return userId != null && userId.equals(session.getAttribute(SESSION_USER_ATTRIBUTE))
                && BOOTSTRAP_USERNAME.equals(loginUser.getSysUser().getUsername());
    }

    /** bootstrap loginで発行したsessionへの要求か。 */
    public static boolean isBootstrapRequest(HttpServletRequest request) {
        return request != null && isBootstrapSession(request.getSession(false));
    }
}
