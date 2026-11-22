package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.PermissionGroup;
import com.ses.entity.PermissionGroupAction;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/** permission groupを優先し、未割当ユーザーだけlegacy role fallbackを使う。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationServiceImpl implements AuthorizationService {

    private static final String ROLE_ADMIN = "管理者";
    private static final String ROLE_HR = "HR";
    private static final String ROLE_MEMBER = "要員";
    /** 既知inventoryのactionを旧menuとの積集合で維持するrole。 */
    private static final Set<String> INTERNAL_ROLES = Set.of("営業", ROLE_HR, "マネージャー");
    /** roleに関係なく管理者だけが実行できるaction。SecurityConfigでも重ねて制限している。 */
    private static final Set<String> ADMIN_ONLY_ACTIONS =
            Set.of("permission.manage", "audit.security.view", "file.scan.retry", "mfa.reset",
                    "user.*", "identity-provider.*", "system-config.*");
    /** NF07 privacy action の接頭辞と resource wildcard（V191 の privacy-admin だけが許可で持つ）。 */
    private static final String PRIVACY_PREFIX = "privacy.";
    private static final String PRIVACY_WILDCARD = "privacy.*";

    private final UserPermissionGroupMapper userPermissionGroupMapper;
    private final PermissionGroupActionMapper permissionGroupActionMapper;
    private final com.ses.mapper.PermissionGroupMapper permissionGroupMapper;
    private final OidcSecurityProperties oidcProperties;

    @Override
    public boolean isAllowed(Authentication authentication, String actionKey) {
        if (authentication == null || !authentication.isAuthenticated() || !StringUtils.hasText(actionKey)) {
            return false;
        }
        return evaluate(SecurityUtils.currentRole(), SecurityUtils::currentUserId, actionKey);
    }

    /**
     * NF01（細粒度epoch計画 §4.1）: DBから読んだ明示actorで {@link #isAllowed} と同じ判定を行う。SecurityContextは読まない。
     *
     * <p>権限group表は設定tenant（{@link #tenantId()}）で引くため、actorのDB上のtenantが設定tenantと一致しない場合は
     * 拒否する（単一tenant配備の前提。計画 §9 RR-14。別tenantの利用者を設定tenantのgroupで評価しない）。</p>
     */
    @Override
    public boolean isAllowedForActor(String actorTenantId, Long userId, String role, String actionKey) {
        if (userId == null || !StringUtils.hasText(role) || !StringUtils.hasText(actionKey)
                || !StringUtils.hasText(actorTenantId) || !tenantId().equals(actorTenantId.trim())) {
            return false;
        }
        return evaluate(role, () -> userId, actionKey);
    }

    /** {@link #isAllowed} と {@link #isAllowedForActor} の共通判定。利用者IDは従来どおりDB判定の例外処理の内側で解決する。 */
    private boolean evaluate(String role, java.util.function.Supplier<Long> userIdResolver, String actionKey) {
        boolean privacyAction = isPrivacyAction(actionKey);
        if ("one-on-one.confidential".equals(actionKey)) {
            if (ROLE_HR.equals(role)) {
                return true;
            }
            if (!ROLE_ADMIN.equals(role)) {
                return false;
            }
        } else if (privacyAction) {
            // NF07 W-5（production-bridge-phased-plan.md §6.2）。管理者role以外は group に関係なく拒否する
            // （privacy-admin の誤割当を data の deny だけに任せない）。管理者でも短絡許可せず、group評価（deny優先）へ進む。
            if (!ROLE_ADMIN.equals(role)) {
                return false;
            }
        } else if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        if (isAdminOnlyAction(actionKey)) {
            return false;
        }
        if (actionKey.startsWith("profile.")) {
            return true;
        }
        try {
            Long userId = userIdResolver.get();
            if (userId != null) {
                Set<Long> assignedGroupIds = userPermissionGroupMapper.selectList(new LambdaQueryWrapper<UserPermissionGroup>()
                                .eq(UserPermissionGroup::getTenantId, tenantId())
                                .eq(UserPermissionGroup::getUserId, userId))
                        .stream().map(UserPermissionGroup::getGroupId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                if (!assignedGroupIds.isEmpty()) {
                    Set<Long> enabledGroupIds = permissionGroupMapper.selectList(new LambdaQueryWrapper<PermissionGroup>()
                                    .eq(PermissionGroup::getTenantId, tenantId())
                                    .eq(PermissionGroup::getEnabled, 1)
                                    .in(PermissionGroup::getId, assignedGroupIds))
                            .stream().map(PermissionGroup::getId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet());
                    if (enabledGroupIds.isEmpty()) {
                        return false;
                    }
                    Set<String> candidates = candidateKeys(actionKey);
                    // 明示拒否はbaseline許可（action_key='*'）より優先する。先に評価しないと
                    // baselineを与えたroleから機密actionを外せない。拒否側は privacy でも '*' を含む同じ候補で照合する
                    // （拒否を弱めない）。
                    if (matches(enabledGroupIds, candidates, 1)) {
                        return false;
                    }
                    // privacy の許可候補からは全体baseline '*' を外す（多重防御）。'*' を複製した group が
                    // privacy.* の deny を欠いても privacy は開かない。許可根拠は privacy-admin の明示 'privacy.*' だけになる。
                    return matches(enabledGroupIds, privacyAction ? privacyAllowCandidates(actionKey) : candidates, 0);
                }
            }
            return legacyRoleAllows(role, actionKey);
        } catch (RuntimeException e) {
            log.warn("action権限のDB判定に失敗したため拒否します: action={}", actionKey, e);
            return false;
        }
    }

    /**
     * group未割当ユーザーのfallback。inventory登録済みactionだけを旧menu層へ渡す。
     * 未知actionを追加コードなしで許可しないことが境界である。
     */
    private boolean legacyRoleAllows(String role, String actionKey) {
        if (role == null) {
            return false;
        }
        // group未割当のユーザーは privacy 管理者になれない。privacy は RESOURCE_NAMES の既知resourceなので、
        // ここで拒否しないと管理者・既知action扱いで通ってしまう（break-glass も同じ commit で拒否する）。
        if (isPrivacyAction(actionKey)) {
            return false;
        }
        if ("one-on-one.confidential".equals(actionKey)) {
            return ROLE_HR.equals(role);
        }
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        // 要員はSecurityConfigのanyRequest().hasAnyRole(...)から除外されており、
        // 到達できる経路自体が本人向けに限定されている。baselineは与えず許可listで表す。
        if (ROLE_MEMBER.equals(role)) {
            return actionKey.equals("file.download") || actionKey.startsWith("profile.")
                    || actionKey.startsWith("my.") || actionKey.startsWith("notifications.");
        }
        if (!INTERNAL_ROLES.contains(role)
                || !com.ses.service.security.ActionPermissionResolver.isKnownAction(actionKey)) {
            return false;
        }
        if (actionKey.startsWith("user.")
                || actionKey.startsWith("identity-provider.")
                || actionKey.startsWith("system-config.")) {
            return false;
        }
        // payroll menuはV21で管理者とHRにだけ付与されている。
        if (actionKey.equals("payroll.view")) {
            return ROLE_HR.equals(role);
        }
        // R3.3の原価マスキング。HRは原価を参照しない。
        if (actionKey.equals("contract.cost.view")) {
            return !ROLE_HR.equals(role);
        }
        return true;
    }

    /**
     * actionKey本体・resource wildcard・baselineの3種を1回のSQLで突き合わせる。
     *
     * <p>ドットを含まないkeyではwildcardがactionKey自身と一致するため、
     * {@code Set.of}（重複で例外）ではなく重複を許す集合で組み立てる。
     */
    private Set<String> candidateKeys(String actionKey) {
        int separator = actionKey.indexOf('.');
        Set<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(actionKey);
        if (separator > 0) {
            candidates.add(actionKey.substring(0, separator + 1) + "*");
        }
        candidates.add("*");
        return candidates;
    }

    /** privacy の許可候補（action本体と resource wildcard だけ。全体baseline '*' は含めない）。 */
    private Set<String> privacyAllowCandidates(String actionKey) {
        Set<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(actionKey);
        candidates.add(PRIVACY_WILDCARD);
        return candidates;
    }

    /** NF07 privacy action（{@code privacy.} 接頭辞。{@code privacy-x} 等の別resourceは含めない）。 */
    private static boolean isPrivacyAction(String actionKey) {
        return actionKey.startsWith(PRIVACY_PREFIX);
    }

    private boolean isAdminOnlyAction(String actionKey) {
        return ADMIN_ONLY_ACTIONS.contains(actionKey)
                || actionKey.startsWith("user.")
                || actionKey.startsWith("identity-provider.")
                || actionKey.startsWith("system-config.")
                // 機能の開放は管理者だけ（FE-RQ-012。group の無い利用者の legacyRoleAllows でも管理者以外を拒否する）
                || actionKey.startsWith("feature-enablement.");
    }

    private boolean matches(Set<Long> enabledGroupIds, Set<String> candidates, int denyFlag) {
        return permissionGroupActionMapper.selectCount(new LambdaQueryWrapper<PermissionGroupAction>()
                .eq(PermissionGroupAction::getTenantId, tenantId())
                .in(PermissionGroupAction::getGroupId, enabledGroupIds)
                .eq(PermissionGroupAction::getDenyFlag, denyFlag)
                .in(PermissionGroupAction::getActionKey, candidates)) > 0;
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }
}
