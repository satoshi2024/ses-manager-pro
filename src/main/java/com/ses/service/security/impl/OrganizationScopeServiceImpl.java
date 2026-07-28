package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 組織スコープの実装。組織ID条件はSQL wrapperへ追加し、画面後処理では絞り込まない。
 *
 * <p><b>シングルトンである理由</b>: 本サービスは{@code WorkRecordService#approve}のような
 * 業務サービスから呼ばれる。業務サービスはHTTPリクエスト以外（バッチ、{@code @Async}、
 * テストのワーカースレッド）からも実行されるため、{@code @RequestScope}にすると
 * {@code ScopeNotActiveException}（No thread-bound request found）で承認そのものが落ちる。
 * キャッシュはリクエスト属性へ退避し、リクエスト外では都度算出する（下記{@code cache()}）。
 */
@Service
@RequiredArgsConstructor
public class OrganizationScopeServiceImpl implements OrganizationScopeService {

    private static final String ROLE_ADMIN = "管理者";
    private static final String ROLE_MANAGER = "マネージャー";

    private final OrganizationUnitMapper organizationUnitMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final ContractMapper contractMapper;
    private final InvoiceMapper invoiceMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    /**
     * {@code OrganizationServiceImpl} は上長のscope検査で本サービスを参照するため、
     * 双方をシングルトンにすると循環参照になる。遅延解決して循環を断つ。
     */
    private final ObjectProvider<OrganizationService> organizationServiceProvider;
    private final ObjectProvider<DataScopeService> dataScopeServiceProvider;

    /** 既存DBの所属backfill完了前はscopeを公開しないための明示的な rollout gate。 */
    @Value("${organization.scope.enabled:false}")
    private boolean organizationScopeEnabled;

    /** リクエスト属性へ退避するキャッシュの格納キー。 */
    private static final String CACHE_ATTRIBUTE = OrganizationScopeServiceImpl.class.getName() + ".CACHE";

    /**
     * 組織階層によるscopeを受けないか。
     *
     * <p>R3.1の三分岐をここで表現する。管理者は全件。部門責任者(マネージャー)だけが
     * 「自組織と子組織」へ絞られる。営業/HR/要員などの一般ユーザーは<b>既存のrole・DataScopeの範囲</b>が
     * 唯一の母集団であり、組織scopeで<b>さらに狭めない</b>。
     *
     * <p>組織で追加的に絞ると、営業部に所属する営業が技術部所属の要員の契約を担当している
     * 通常の運用で積集合が空になり、自分の担当データすら見えなくなる。
     * これはR3.1が意図した挙動ではないため、業務データへの組織scopeは部門責任者に限定する。
     */
    @Override
    public boolean hasFullAccess() {
        if (!organizationScopeEnabled) {
            return true;
        }
        String role = SecurityUtils.currentRole();
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        return !ROLE_MANAGER.equals(role);
    }

    @Override
    public Set<Long> allowedOrganizationIds(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (hasFullAccess()) {
            // 空集合は「制限なし」ではなく「組織条件を付けない」を意味する。
            // 呼び出し側は必ず hasFullAccess() を先に見ること。
            return Collections.emptySet();
        }
        Long userId = resolveCurrentUserId();
        String role = SecurityUtils.currentRole();
        if (userId == null || role == null) {
            return Collections.emptySet();
        }

        List<UserOrganization> assignments = activeAssignments(userId, date);
        List<UserOrganization> managedAssignments = ROLE_MANAGER.equals(role)
                ? userOrganizationMapper.selectActiveByManagerUserId(userId, date)
                : List.of();
        List<UserOrganization> cacheAssignments = new java.util.ArrayList<>(assignments);
        cacheAssignments.addAll(managedAssignments);
        LocalDateTime version = cacheAssignments.stream()
                .map(UserOrganization::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MIN);
        ScopeCacheKey key = new ScopeCacheKey(null, userId, role, date, version);
        ScopeCache cache = cache();
        if (cache != null && key.equals(cache.key) && cache.ids != null) {
            return cache.ids;
        }

        Set<Long> result = new HashSet<>();
        for (UserOrganization assignment : assignments) {
            if (assignment.getOrganizationId() == null) {
                continue;
            }
            // ここへ来るのは部門責任者(マネージャー)だけ。主所属の組織とその子孫が閲覧範囲になる。
            if (Integer.valueOf(1).equals(assignment.getPrimaryFlag())) {
                result.addAll(organizationServiceProvider.getObject()
                        .descendantIds(assignment.getOrganizationId(), date));
            }
        }
        Set<Long> resolved = Collections.unmodifiableSet(result);
        if (cache != null) {
            cache.key = key;
            cache.ids = resolved;
        }
        return resolved;
    }

    /**
     * リクエストスコープのキャッシュ置き場を返す。リクエスト外（バッチ・{@code @Async}・
     * ワーカースレッド）では{@code null}を返し、キャッシュせず毎回算出する。
     *
     * <p>スレッドローカルにしないのは、Tomcatのワーカースレッドが使い回されるため、
     * リクエスト境界で捨てないと別ユーザーへ許可組織が漏れる／古い権限が残るからである。
     */
    private ScopeCache cache() {
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object existing = attributes.getAttribute(CACHE_ATTRIBUTE,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof ScopeCache scopeCache) {
            return scopeCache;
        }
        ScopeCache created = new ScopeCache();
        attributes.setAttribute(CACHE_ATTRIBUTE, created,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return created;
    }

    private static final class ScopeCache {
        private ScopeCacheKey key;
        private Set<Long> ids;
    }

    @Override
    public Set<Long> allowedDirectUserIds(LocalDate asOf) {
        if (!ROLE_MANAGER.equals(SecurityUtils.currentRole()) || resolveCurrentUserId() == null) {
            return Set.of();
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return userOrganizationMapper.selectActiveByManagerUserId(resolveCurrentUserId(), date)
                .stream().map(UserOrganization::getUserId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<Long> allowedEngineerIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(engineerAccountLinkMapper.selectEngineerIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedContractIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectContractIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedInvoiceIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        return Set.copyOf(invoiceMapper.selectInvoiceIdsByOrganizationScope(
                new java.util.ArrayList<>(allowedOrganizationIds(asOf)),
                new java.util.ArrayList<>(allowedDirectUserIds(asOf)),
                asOf == null ? LocalDate.now() : asOf));
    }

    @Override
    public Set<Long> allowedCustomerIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        Set<Long> contractIds = allowedContractIds(asOf);
        if (contractIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectCustomerIdsByContractIds(new java.util.ArrayList<>(contractIds)));
    }

    @Override
    public Set<Long> allowedProjectIds(LocalDate asOf) {
        if (hasFullAccess()) {
            return Set.of();
        }
        Set<Long> contractIds = allowedContractIds(asOf);
        if (contractIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(contractMapper.selectProjectIdsByContractIds(new java.util.ArrayList<>(contractIds)));
    }

    @Override
    public boolean isAllowedUser(Long targetUserId, LocalDate asOf) {
        if (hasFullAccess()) {
            return true;
        }
        if (targetUserId == null || resolveCurrentUserId() == null) {
            return false;
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        if (ROLE_MANAGER.equals(SecurityUtils.currentRole())
                && allowedDirectUserIds(date).contains(targetUserId)) {
            return true;
        }
        for (UserOrganization assignment : activeAssignments(targetUserId, date)) {
            if (assignment.getOrganizationId() != null
                    && allowedOrganizationIds(date).contains(assignment.getOrganizationId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<OrganizationUnit> listVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        return organizationUnitMapper.selectList(visibleQuery(legalEntityId, asOf, true));
    }

    @Override
    public long countVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        return organizationUnitMapper.selectCount(visibleQuery(legalEntityId, asOf, false));
    }

    @Override
    public List<OrganizationUnit> exportVisibleOrganizations(Long legalEntityId, LocalDate asOf) {
        // exportも同じSQL母集団を使う。画面一覧を取得してから絞り込まない。
        return organizationUnitMapper.selectList(visibleQuery(legalEntityId, asOf, true));
    }

    @Override
    public <T> LambdaQueryWrapper<T> applyOrganizationScope(LambdaQueryWrapper<T> query,
                                                              SFunction<T, ?> organizationColumn,
                                                              LocalDate asOf) {
        if (hasFullAccess()) {
            return query;
        }
        Set<Long> ids = allowedOrganizationIds(asOf);
        if (ids.isEmpty()) {
            // ID列に存在しない値を入れ、SQL側で0件にする。取得後filterは行わない。
            return query.eq(organizationColumn, -1L);
        }
        return query.in(organizationColumn, ids);
    }

    @Override
    public Set<Long> intersectWithDataScope(Collection<Long> organizationIds,
                                             Collection<Long> dataScopeIds) {
        Set<Long> orgIds = organizationIds == null ? Set.of() : new HashSet<>(organizationIds);
        DataScopeService dataScope = dataScopeServiceProvider.getIfAvailable();
        if (dataScope == null || !dataScope.isScoped()) {
            return Collections.unmodifiableSet(orgIds);
        }
        if (dataScopeIds == null) {
            return Set.of();
        }
        orgIds.retainAll(dataScopeIds);
        return Collections.unmodifiableSet(orgIds);
    }

    @Override
    public void assertAllowedOrganization(Long organizationId, LocalDate asOf) {
        if (organizationId == null || (!hasFullAccess()
                && !allowedOrganizationIds(asOf).contains(organizationId))) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
    }

    private List<UserOrganization> activeAssignments(Long userId, LocalDate asOf) {
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, asOf)
                .and(w -> w.isNull(UserOrganization::getValidTo)
                        .or().ge(UserOrganization::getValidTo, asOf))
                .orderByAsc(UserOrganization::getId));
    }

    private Long resolveCurrentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null || sysUserMapper == null) {
            return userId;
        }
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            return null;
        }
        com.ses.entity.SysUser user = sysUserMapper.selectByUsername(username);
        return user == null ? null : user.getId();
    }

    private LambdaQueryWrapper<OrganizationUnit> visibleQuery(Long legalEntityId,
                                                                LocalDate asOf,
                                                                boolean ordered) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LambdaQueryWrapper<OrganizationUnit> query = new LambdaQueryWrapper<OrganizationUnit>()
                .eq(legalEntityId != null, OrganizationUnit::getLegalEntityId, legalEntityId)
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date))
                .eq(OrganizationUnit::getStatus, "有効");
        if (ordered) {
            query.orderByAsc(OrganizationUnit::getParentId)
                    .orderByAsc(OrganizationUnit::getCode);
        }
        return applyOrganizationScope(query, OrganizationUnit::getId, date);
    }

    private record ScopeCacheKey(Long tenantId, Long userId, String role,
                                 LocalDate asOf, LocalDateTime version) {
    }
}
