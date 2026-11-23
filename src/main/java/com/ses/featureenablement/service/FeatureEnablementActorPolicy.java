package com.ses.featureenablement.service;

import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.MfaEnforcementFilter;
import com.ses.entity.SysUser;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.BreakGlassService;
import com.ses.config.ProductionBootstrapMode;
import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 機能の開放の行為者の判定（feature-enablement-console design §11.2・§11.3。FE-RQ-003）。申請・承認・却下・取下げ・閉鎖・署名の
 * 登録と確認の全てに使う。
 *
 * <ul>
 *   <li>session: prod は OIDC の session（{@code OidcLoginUser}）で MFA を済ませたものだけ。prod 以外は local の form login
 *       （{@code LoginUser}）も許す。break-glass の incident の属性（{@link BreakGlassService#INCIDENT_ID_ATTRIBUTE}）・bootstrap の
 *       session（{@link ProductionBootstrapMode#isBootstrapSession}）・break-glass の username の session は使えない（理由は design
 *       §11.2: break-glass は IdP 障害時の限られた操作の口座で、bootstrap は承認者の独立性が無い）。session が無い・破棄済みも拒否。</li>
 *   <li>有効な管理者: 要求ごとに DB から読み直す（role {@code 管理者}・{@code status=1}・未削除・lock 中でない・設定の tenant。
 *       {@code SecurityContextCallerContextProvider.isCurrentActiveAccount} と同じ判定）。</li>
 *   <li>NF07 の開放単位（申請・承認・却下・取下げ・閉鎖）と、NF07 の署名の種別・組の登録・確認・却下・取消し・延長の行為者は、
 *       有効な privacy-admin group の所属者に限る（J-05・design §11.3。FE-C05）。所属は要求ごとに DB から読み直す（照会できなければ
 *       拒否）。{@code '*'}・{@code privacy.} の拒否行を持つ有効な group にも所属する者は除く（開放の条件の数え方
 *       {@code UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames} と揃える。OIDC の session と break-glass の除外は上の
 *       session の規則が受け持つ。review rb-fe-c P3-3）。</li>
 * </ul>
 * 判定に失敗すれば 403 {@code error.featureEnablement.actorNotAllowed}（理由の詳細は返さない）。
 */
@Component
public class FeatureEnablementActorPolicy {

    /** NF07 の行為者を privacy-admin に限る規則（FE-C05 で有効にした）。 */
    public static final boolean PRIVACY_ADMIN_RULE_ENABLED = true;

    /** privacy-admin の group key（{@code m_permission_group.group_key}）。 */
    public static final String PRIVACY_ADMIN_GROUP_KEY = "privacy-admin";

    public static final String ACTOR_NOT_ALLOWED = "error.featureEnablement.actorNotAllowed";

    private final SysUserMapper sysUserMapper;
    private final Environment environment;
    private final ObjectProvider<OidcSecurityProperties> oidcProperties;
    private final FeatureEnablementTenant tenant;
    private final Clock clock;
    private final ObjectProvider<UserPermissionGroupMapper> privacyAdminMembers;

    /** privacy-admin の所属を読めない生成（NF07 の行為者は全て拒否する。既存の試験のため）。 */
    public FeatureEnablementActorPolicy(SysUserMapper sysUserMapper, Environment environment,
                                        ObjectProvider<OidcSecurityProperties> oidcProperties,
                                        FeatureEnablementTenant tenant, Clock clock) {
        this(sysUserMapper, environment, oidcProperties, tenant, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FeatureEnablementActorPolicy(SysUserMapper sysUserMapper, Environment environment,
                                        ObjectProvider<OidcSecurityProperties> oidcProperties,
                                        FeatureEnablementTenant tenant, Clock clock,
                                        ObjectProvider<UserPermissionGroupMapper> privacyAdminMembers) {
        this.privacyAdminMembers = privacyAdminMembers;
        this.sysUserMapper = sysUserMapper;
        this.environment = environment;
        this.oidcProperties = oidcProperties;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** session の種別と有効な管理者を確かめて行為者を返す（不可なら 403）。 */
    public FeatureEnablementActor resolve(Authentication authentication, HttpSession session) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser) || loginUser.getSysUser() == null
                || loginUser.getSysUser().getId() == null) {
            throw denied();
        }
        Long userId = loginUser.getSysUser().getId();
        if (!sessionAllowed(loginUser, userId, session)) {
            throw denied();
        }
        return requireActiveAdministrator(userId);
    }

    /** 有効な管理者を DB から読み直して確かめる（transaction の中で毎回呼ぶ。同じ package の service だけが使う。P3-7）。 */
    FeatureEnablementActor requireActiveAdministrator(Long userId) {
        if (userId == null) {
            throw denied();
        }
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenant.tenantId());
        if (!activeAdministrator(user)) {
            throw denied();
        }
        return new FeatureEnablementActor(user.getId(), user.getUsername());
    }

    /**
     * NF07 の開放単位・署名の種別・組の行為者を privacy-admin に限る（J-05）。有効な privacy-admin group（有効・未削除）の、有効・
     * 未削除の利用者の所属行（未削除）を要求ごとに読む。所属が無い・読めなければ 403。
     */
    public void requirePrivacyAdminIfRequired(boolean privacyTarget, FeatureEnablementActor actor) {
        if (!PRIVACY_ADMIN_RULE_ENABLED || !privacyTarget) {
            return;
        }
        if (!isPrivacyAdmin(actor)) {
            throw denied();
        }
    }

    /** 行為者が有効な privacy-admin の所属者か（読めなければ false）。 */
    public boolean isPrivacyAdmin(FeatureEnablementActor actor) {
        if (actor == null || actor.userId() == null) {
            return false;
        }
        UserPermissionGroupMapper mapper = privacyAdminMembers == null ? null : privacyAdminMembers.getIfAvailable();
        if (mapper == null) {
            return false;
        }
        try {
            List<Long> members = mapper.selectActiveUserIdsByGroupKey(tenant.tenantId(), PRIVACY_ADMIN_GROUP_KEY);
            // privacy の拒否行を持つ group にも所属する者は除く（開放の条件の privacy-admin の数え方と揃える。review rb-fe-c P3-3）
            return members != null && members.contains(actor.userId())
                    && mapper.countPrivacyDenyMemberships(tenant.tenantId(), actor.userId()) == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 設定の tenant の有効な管理者の ID（通知の宛先）。 */
    public List<Long> activeAdministratorIds() {
        return sysUserMapper.selectActiveByRoleAndTenant(StatusConstants.ROLE_ADMIN, tenant.tenantId()).stream()
                .filter(this::activeAdministrator).map(SysUser::getId).filter(Objects::nonNull).toList();
    }

    boolean sessionAllowed(LoginUser loginUser, Long userId, HttpSession session) {
        if (session == null) {
            return false;
        }
        try {
            if (session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE) != null
                    || ProductionBootstrapMode.isBootstrapSession(session)) {
                return false;
            }
        } catch (IllegalStateException invalidated) {
            return false;
        }
        OidcSecurityProperties properties = oidcProperties == null ? null : oidcProperties.getIfAvailable();
        if (properties != null && properties.isBreakGlassUsername(loginUser.getUsername())) {
            return false;
        }
        if (loginUser instanceof OidcLoginUser) {
            try {
                return MfaEnforcementFilter.isVerifiedFor(session, userId);
            } catch (IllegalStateException invalidated) {
                return false;
            }
        }
        // local の form login は prod 以外だけ（dev の Demo と試験）
        return environment == null || !environment.acceptsProfiles(Profiles.of("prod"));
    }

    private boolean activeAdministrator(SysUser user) {
        if (user == null || user.getId() == null || !Integer.valueOf(1).equals(user.getStatus())
                || !Integer.valueOf(0).equals(user.getDeletedFlag())
                || !StatusConstants.ROLE_ADMIN.equals(user.getRole())
                || !tenant.tenantId().equals(user.getTenantId())) {
            return false;
        }
        LocalDateTime lockedUntil = user.getLockedUntil();
        return lockedUntil == null || !lockedUntil.isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
    }

    private static BusinessException denied() {
        return BusinessException.of(403, ACTOR_NOT_ALLOWED);
    }
}
