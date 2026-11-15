package com.ses.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.Menu;
import com.ses.entity.SysUser;
import com.ses.entity.SystemConfig;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.MenuMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.security.epoch.AuthorizationConfigKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NF01 CONFIRMのreader側部品（細粒度epoch計画 §4.1、§2.1 (a)〜(f)、§6.2 U6）。
 *
 * <p>SecurityContextを使わず、DBから読んだ明示actor（tenant・利用者ID・role・基準日）で次を評価する。判定規則は既存の
 * {@code LifecycleScopeService}（assertCanEditTask・canViewCase・isTaskVisibleToUser）、{@code OrganizationScopeServiceImpl}
 * （hasFullAccess・allowedOrganizationIds・allowedDirectUserIds・allowedEngineerIds）、{@code DataScopeServiceImpl}
 * （isScoped・allowedEngineerIds）、{@code MenuPermissionFilter}（role→menu、管理者は迂回）、
 * {@link AuthorizationService#isAllowed}（{@link ActionPermissionResolver} がrouteから導くaction key）と同じにする。
 * 既存サービスの挙動は変えない。</p>
 *
 * <ul>
 *   <li>configとmenuは {@code m_system_config}・{@code t_role_menu}・{@code m_menu} から直接読む。
 *       {@code SystemConfigServiceImpl} のcache（afterCommitで失効）、{@code MenuCacheService}、
 *       {@link ReportScopeContext}、DataScope・組織scopeのrequest cacheは使わない。</li>
 *   <li>呼出側（CONFIRM attempt、W3-21a）が全体epoch行・利用者行・要員行等のS lockを保持した状態で呼ぶこと。
 *       本クラス自身はlockを取らない（anchorのS lock保持下では子行のwriterがanchorのXを取れないため、通常読取で値が安定する。
 *       計画 §2.5）。</li>
 *   <li>tenantは要求・sessionではなく、{@link #resolveActor} がDBから読んだ値だけを使う。</li>
 *   <li>MyBatisの一次cacheはtransaction（SqlSession）単位のため、CONFIRM attemptごとの {@code REQUIRES_NEW} で新しくなる。
 *       同じattempt内ではanchorのS lockにより読む行が変わらないので、一次cacheの再利用は判定を古くしない。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ExplicitActorAuthorizationEvaluator {

    static final String ROLE_ADMIN = "管理者";
    static final String ROLE_HR = "HR";
    static final String ROLE_MANAGER = "マネージャー";
    static final String ROLE_SALES = "営業";
    static final String ROLE_MEMBER = "要員";
    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_ADMIN, ROLE_HR, ROLE_MANAGER, ROLE_SALES, ROLE_MEMBER);

    private final SysUserMapper sysUserMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final SystemConfigMapper systemConfigMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final AuthorizationService authorizationService;
    private final OrganizationScopeService organizationScopeService;

    /**
     * DB上の明示actor。role・tenantは {@code sys_user} 行の値で、呼出側の入力値ではない。
     *
     * @param asOf 組織所属・組織階層の基準日
     */
    public record ExplicitActor(String tenantId, long userId, String role, LocalDate asOf) {
        public ExplicitActor {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    // ===== (a) 主体の有効性 =====

    /**
     * 利用者行を {@code id + tenant_id} で読み、有効（{@code status=1}・未削除）で既知roleの場合だけactorを返す
     * （{@code LifecycleScopeService#requireTenant} と同じ条件。ただしSecurityContextの値と突き合わせず、DBの値を正とする）。
     */
    public Optional<ExplicitActor> resolveActor(String tenantId, Long userId, LocalDate asOf) {
        String tenant = normalized(tenantId);
        if (tenant == null || userId == null || asOf == null) {
            return Optional.empty();
        }
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenant);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !KNOWN_ROLES.contains(user.getRole())) {
            return Optional.empty();
        }
        String persistedTenant = normalized(user.getTenantId());
        if (persistedTenant == null) {
            return Optional.empty();
        }
        return Optional.of(new ExplicitActor(persistedTenant, user.getId(), user.getRole(), asOf));
    }

    // ===== (b) role→menu =====

    /** 指定menuをroleが使えるか。管理者は menu設定を迂回する（{@code MenuPermissionFilter} と同じ）。 */
    public boolean isMenuAllowed(ExplicitActor actor, String menuKey) {
        if (actor == null || !StringUtils.hasText(menuKey)) {
            return false;
        }
        if (ROLE_ADMIN.equals(actor.role())) {
            return true;
        }
        return roleMenuKeys(actor.role()).contains(menuKey);
    }

    /**
     * routeへの到達可否を {@code MenuPermissionFilter} と同じ規則・順序で判定する（menu判定対象外のpathの素通し →
     * portalの拒否 → action keyを導けない内部APIの拒否（認証基盤の例外を除く）→ action判定 → 管理者迂回 → 最長一致menu判定）。
     *
     * <p>{@code uri} はcontext pathを除いたアプリ内path（{@code RequestPaths.pathWithinApplication} と同じ）で渡す。
     * menu判定対象外のpath（{@code /logout}・静的資源・通知一覧等）はfilterと同じくtrueを返すため、CONFIRMの呼出側
     * （W3-21a）は提出・確認の固定routeだけを渡すこと（W3-15 review P3-1）。</p>
     */
    public boolean isRouteAllowed(ExplicitActor actor, String httpMethod, String uri) {
        if (actor == null || !StringUtils.hasText(httpMethod) || !StringUtils.hasText(uri)) {
            return false;
        }
        if (!isMenuControlledPath(uri)) {
            return true;
        }
        // Portalは専用SecurityFilterChainと権限modelが入るまで既存内部roleへ流さない（filterと同じ）。
        if (uri.equals("/portal") || uri.startsWith("/portal/")
                || uri.equals("/api/portal") || uri.startsWith("/api/portal/")) {
            return false;
        }
        String actionKey = ActionPermissionResolver.resolve(httpMethod, uri);
        if (uri.startsWith("/api/") && actionKey == null
                && !ActionPermissionResolver.isAuthenticationInfrastructure(httpMethod, uri)
                && !(uri.equals("/api/auth") || uri.startsWith("/api/auth/"))) {
            return false;
        }
        List<Menu> menus = menuMapper.selectList(new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder));
        Optional<Menu> matched = menus.stream()
                .filter(menu -> matchedPrefixLength(menu, uri) > 0)
                .max((a, b) -> Integer.compare(matchedPrefixLength(a, uri), matchedPrefixLength(b, uri)));
        if (actionKey == null && matched.isPresent() && !uri.startsWith("/api/")) {
            actionKey = ActionPermissionResolver.resolve("GET", matched.get().getApiPrefix());
            if (actionKey == null) {
                return false;
            }
        }
        if (actionKey != null && !isActionAllowed(actor, actionKey)) {
            return false;
        }
        if (ROLE_ADMIN.equals(actor.role())) {
            return true;
        }
        if (matched.isEmpty()) {
            return true;
        }
        return roleMenuKeys(actor.role()).contains(matched.get().getMenuKey());
    }

    // ===== (c) action権限 =====

    /** {@link AuthorizationService#isAllowed} の明示actor版（baseline＋deny、deny優先の規則は同じ）。 */
    public boolean isActionAllowed(ExplicitActor actor, String actionKey) {
        if (actor == null || !StringUtils.hasText(actionKey)) {
            return false;
        }
        return authorizationService.isAllowedForActor(actor.tenantId(), actor.userId(), actor.role(), actionKey);
    }

    // ===== 認可config（global） =====

    /** {@code scope.sales-own-data-only} をDBから直接読む（未設定・空は既定値 false）。 */
    public boolean isSalesOwnDataOnly() {
        SystemConfig row = systemConfigMapper.selectById(AuthorizationConfigKeys.SALES_OWN_DATA_ONLY);
        String value = row == null ? null : row.getConfigValue();
        return "true".equalsIgnoreCase(StringUtils.hasText(value) ? value : "false");
    }

    // ===== (e)(f) 組織scope =====

    public boolean hasOrganizationFullAccess(ExplicitActor actor) {
        return actor != null && organizationScopeService.hasFullAccessFor(actor.role());
    }

    public Set<Long> allowedOrganizationIds(ExplicitActor actor) {
        if (actor == null) {
            return Set.of();
        }
        return organizationScopeService.allowedOrganizationIdsFor(actor.userId(), actor.role(), actor.asOf());
    }

    public Set<Long> allowedDirectUserIds(ExplicitActor actor) {
        if (actor == null) {
            return Set.of();
        }
        return organizationScopeService.allowedDirectUserIdsFor(actor.userId(), actor.role(), actor.asOf());
    }

    public Set<Long> organizationAllowedEngineerIds(ExplicitActor actor) {
        if (actor == null) {
            return Set.of();
        }
        return organizationScopeService.allowedEngineerIdsFor(actor.tenantId(), actor.userId(), actor.role(),
                actor.asOf());
    }

    // ===== (f) 文書ACLのENGINEER分岐が使うDataScope =====

    /** {@code DataScopeServiceImpl#isScoped} の明示actor版。 */
    public boolean isDataScoped(ExplicitActor actor) {
        if (actor == null) {
            return true;
        }
        if (ROLE_MEMBER.equals(actor.role())) {
            return true;
        }
        if (!hasOrganizationFullAccess(actor)) {
            return true;
        }
        return isSalesOwnDataOnly() && ROLE_SALES.equals(actor.role());
    }

    /** {@code DataScopeServiceImpl#allowedEngineerIds(LocalDate)} の明示actor版（{@link #isDataScoped} がtrueの場合に使う）。 */
    public Set<Long> dataScopeAllowedEngineerIds(ExplicitActor actor) {
        if (actor == null) {
            return Set.of();
        }
        if (ROLE_MEMBER.equals(actor.role())) {
            EngineerAccountLink link = engineerAccountLinkMapper.selectByUserIdAndTenant(actor.userId(), actor.tenantId());
            return link == null || link.getEngineerId() == null ? Collections.emptySet() : Set.of(link.getEngineerId());
        }
        if (!hasOrganizationFullAccess(actor)) {
            return organizationAllowedEngineerIds(actor);
        }
        return engineerSalesMapper.selectList(new QueryWrapper<EngineerSales>()
                        .eq("sales_user_id", actor.userId())
                        .isNull("released_at"))
                .stream().map(EngineerSales::getEngineerId).filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** DataScopeの対象外か、対象でも許可要員に含まれるか（{@code DataScopeService#assertAllowedEngineer} の明示actor版）。 */
    public boolean isEngineerAllowedByDataScope(ExplicitActor actor, Long engineerId) {
        if (actor == null || engineerId == null) {
            return false;
        }
        return !isDataScoped(actor) || dataScopeAllowedEngineerIds(actor).contains(engineerId);
    }

    // ===== (d)(e) lifecycle =====

    /** {@code LifecycleScopeService#canViewCase} の明示actor版。 */
    public boolean canViewCase(ExplicitActor actor, LifecycleCase lcCase, Engineer engineer) {
        if (actor == null || !tenantMatches(actor, lcCase, engineer)) {
            return false;
        }
        String role = actor.role();
        if (ROLE_ADMIN.equals(role) || ROLE_HR.equals(role)) {
            return true;
        }
        if (ROLE_MEMBER.equals(role)) {
            EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerIdAndTenant(engineer.getId(),
                    actor.tenantId());
            return link != null && Objects.equals(link.getSysUserId(), actor.userId());
        }
        if (ROLE_MANAGER.equals(role)) {
            if (hasOrganizationFullAccess(actor)) {
                return true;
            }
            return engineer.getOrganizationId() != null
                    && allowedOrganizationIds(actor).contains(engineer.getOrganizationId());
        }
        if (ROLE_SALES.equals(role)) {
            return isActiveSalesOf(actor, engineer);
        }
        return false;
    }

    /** {@code LifecycleScopeService#isTaskVisibleToUser} の明示actor版。 */
    public boolean isTaskVisible(ExplicitActor actor, LifecycleTask task) {
        if (actor == null || task == null || !KNOWN_ROLES.contains(actor.role())) {
            return false;
        }
        String role = actor.role();
        if (ROLE_MEMBER.equals(role)) {
            return Integer.valueOf(1).equals(task.getIsEngineerVisible());
        }
        if (ROLE_SALES.equals(role)) {
            if (Integer.valueOf(1).equals(task.getIsEngineerVisible())) {
                return true;
            }
            return ROLE_SALES.equals(task.getAssigneeRole())
                    || (task.getAssigneeUserId() != null && Objects.equals(task.getAssigneeUserId(), actor.userId()));
        }
        return ROLE_ADMIN.equals(role) || ROLE_HR.equals(role) || ROLE_MANAGER.equals(role);
    }

    /**
     * {@code LifecycleScopeService#assertCanEditTask} の明示actor版。拒否は同じ status・message key の
     * {@link BusinessException} で返す（存在を推測させない404を含む）。
     */
    public void assertCanEditTask(ExplicitActor actor, LifecycleTask task, LifecycleCase lcCase, Engineer engineer) {
        if (actor == null) {
            throw BusinessException.of(403, "error.lifecycle.accessDenied");
        }
        if (!tenantMatches(actor, lcCase, engineer)) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        String role = actor.role();
        if (!canViewCase(actor, lcCase, engineer) || !isTaskVisible(actor, task)) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        if (ROLE_ADMIN.equals(role) || ROLE_HR.equals(role)) {
            return;
        }
        if (task.getAssigneeUserId() != null && Objects.equals(task.getAssigneeUserId(), actor.userId())) {
            if (ROLE_MEMBER.equals(role) && !Integer.valueOf(1).equals(task.getIsEngineerVisible())) {
                throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
            }
            return;
        }
        if (ROLE_MEMBER.equals(role)) {
            throw BusinessException.of(403, "error.lifecycle.notAssignedToYou", "あなたに割り当てられたタスクではありません");
        }
        if (task.getAssigneeRole() != null && task.getAssigneeRole().equals(role)) {
            return;
        }
        if (ROLE_MANAGER.equals(role) && canViewCase(actor, lcCase, engineer)) {
            return;
        }
        if (ROLE_SALES.equals(role) && canViewCase(actor, lcCase, engineer) && isTaskVisible(actor, task)) {
            return;
        }
        throw BusinessException.of(403, "error.lifecycle.taskEditDenied", "このタスクを実行する権限がありません");
    }

    // ===== 内部 =====

    private boolean tenantMatches(ExplicitActor actor, LifecycleCase lcCase, Engineer engineer) {
        return lcCase != null && engineer != null
                && actor.tenantId().equals(normalized(lcCase.getTenantId()))
                && actor.tenantId().equals(normalized(engineer.getTenantId()));
    }

    private boolean isActiveSalesOf(ExplicitActor actor, Engineer engineer) {
        List<EngineerSalesDto> active = engineerSalesMapper.selectActiveWithNames(engineer.getId());
        return active != null && active.stream().anyMatch(s -> Objects.equals(s.getSalesUserId(), actor.userId()));
    }

    private Set<String> roleMenuKeys(String role) {
        List<String> keys = roleMenuMapper.selectMenuKeysByRole(role);
        return keys == null ? Set.of() : Set.copyOf(keys.stream().filter(Objects::nonNull).toList());
    }

    /** {@code MenuPermissionFilter#isMenuControlledPath} と同じ判定。 */
    private static boolean isMenuControlledPath(String uri) {
        if (uri.startsWith("/api/notifications") && !uri.startsWith("/api/notifications/generate")) {
            return false;
        }
        return !uri.equals("/logout") && !uri.equals("/") && (uri.startsWith("/api/") || !uri.contains("."));
    }

    private static int matchedPrefixLength(Menu menu, String uri) {
        int length = 0;
        String pathPrefix = menu.getPathPrefix();
        if (pathPrefix != null && !pathPrefix.isEmpty() && uri.startsWith(pathPrefix)) {
            length = pathPrefix.length();
        }
        String apiPrefix = menu.getApiPrefix();
        if (apiPrefix != null && !apiPrefix.isEmpty() && uri.startsWith(apiPrefix)) {
            length = Math.max(length, apiPrefix.length());
        }
        return length;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
