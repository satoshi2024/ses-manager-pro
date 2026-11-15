package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.common.security.OutboundUrlGuard;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.PermissionGroup;
import com.ses.entity.RoleMenu;
import com.ses.entity.SysUser;
import com.ses.entity.SystemConfig;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import com.ses.mapper.MenuMapper;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.NotificationService;
import com.ses.service.impl.RoleMenuServiceImpl;
import com.ses.service.impl.SystemConfigServiceImpl;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.impl.PermissionGroupManagementServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U7（NF01細粒度epoch計画 §4.1・§6.2、W3-15）: F1 writer（role menu・認可config・権限group割当）を実際の
 * {@link AuthorizationEpochFence} とmockの {@link AuthorizationEpochMapper} で動かし、増分の条件をDB無しで固定する。
 *
 * <ul>
 *   <li>変化の無い更新では進めない。変化があれば同じtransactionで1回だけ進める（入れ子・再呼出しでも1回）。</li>
 *   <li>全体epoch行のX lockは旧値の読取より前に取る（E-2）。</li>
 *   <li>REQUIRES_NEWで外側をsuspendした内側transactionは、外側の増分済み集合を共有しない。</li>
 *   <li>上限（増分0件）では409 {@code error.authorization.epochUnavailable} で止め、afterの副作用（cache失効等）を行わない。
 *       業務更新ごとのrollbackは実transactionの試験（H2統合・M2/M3）で確かめる。</li>
 *   <li>actor epochのtenantは設定tenantではなく、lockした利用者行のtenantを使う。</li>
 * </ul>
 *
 * <p>SysUserServiceImpl は MyBatis-Plus の基底実装がSqlSessionを要するため、H2統合試験
 * （{@code GlobalActorEpochWriterIntegrationTest}）で扱う。</p>
 */
class GlobalActorEpochWriterTest {

    private static final String SCOPE_KEY = AuthorizationConfigKeys.SALES_OWN_DATA_ONLY;

    private AuthorizationEpochMapper epochMapper;
    private AuthorizationEpochFence fence;

    @BeforeEach
    void setUp() {
        epochMapper = mock(AuthorizationEpochMapper.class);
        when(epochMapper.selectGlobalForUpdate()).thenReturn(new EpochRow(5L, 0));
        when(epochMapper.incrementGlobal()).thenReturn(1);
        when(epochMapper.incrementActor(anyLong(), anyString())).thenReturn(1);
        fence = new AuthorizationEpochFence(epochMapper);
        beginTransaction();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            completeTransaction();
        }
        TransactionSynchronizationManager.clear();
        SecurityContextHolder.clearContext();
    }

    // ===== RoleMenuServiceImpl =====

    @Test
    void role_menuの集合が変われば全体epochを1回だけ進め同じ集合では進めない() {
        RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        RoleMenuServiceImpl service = roleMenuService(roleMenuMapper, menuCache);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(11L)));

        service.replaceMenus("営業", List.of());
        service.replaceMenus("営業", List.of());

        verify(epochMapper, times(1)).incrementGlobal();
        InOrder order = inOrder(epochMapper, roleMenuMapper);
        order.verify(epochMapper).selectGlobalForUpdate();
        order.verify(roleMenuMapper).selectList(any());
        order.verify(roleMenuMapper).delete(any());
        order.verify(epochMapper).incrementGlobal();
        verify(menuCache, times(2)).invalidate();

        completeTransaction();
        beginTransaction();
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        service.replaceMenus("営業", List.of());
        verify(epochMapper, times(1)).incrementGlobal();
        verify(epochMapper, times(3)).selectGlobalForUpdate();
    }

    @Test
    void role_menuの不正なroleはDBに触れる前に拒否し全体epoch行もlockしない() {
        RoleMenuServiceImpl service = roleMenuService(mock(RoleMenuMapper.class), mock(MenuCacheService.class));

        BusinessException admin = assertThrows(BusinessException.class, () -> service.replaceMenus("管理者", List.of()));
        BusinessException invalid = assertThrows(BusinessException.class, () -> service.replaceMenus("不明", List.of()));

        assertEquals(403, admin.getCode());
        assertEquals(400, invalid.getCode());
        verify(epochMapper, never()).selectGlobalForUpdate();
    }

    @Test
    void role_menuの全体epochが上限なら409で止めcacheを失効しない() {
        RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        RoleMenuServiceImpl service = roleMenuService(roleMenuMapper, menuCache);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(11L)));
        when(epochMapper.incrementGlobal()).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.replaceMenus("HR", List.of()));

        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());
        verify(menuCache, never()).invalidate();
    }

    @Test
    void REQUIRES_NEWの内側transactionは外側の増分済み集合を共有せず別に全体epochを進める() {
        RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
        RoleMenuServiceImpl service = roleMenuService(roleMenuMapper, mock(MenuCacheService.class));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(11L)));

        service.replaceMenus("営業", List.of());
        List<TransactionSynchronization> outer = suspend();
        beginTransaction();
        service.replaceMenus("営業", List.of());
        completeTransaction();
        resume(outer);
        service.replaceMenus("営業", List.of());

        verify(epochMapper, times(2)).incrementGlobal();
    }

    // ===== SystemConfigServiceImpl =====

    @Test
    void 認可キーの値が変われば全体epochを1回進め同じ値では進めない() {
        SystemConfigMapper configMapper = mock(SystemConfigMapper.class);
        SystemConfigServiceImpl service = new SystemConfigServiceImpl(configMapper, new OutboundUrlGuard(), fence);
        when(configMapper.selectById(SCOPE_KEY)).thenReturn(config("false"), config("false"), config("true"));

        service.updateAll(List.of(new SystemConfig(SCOPE_KEY, "true", "営業の担当データ限定")));

        verify(epochMapper, times(1)).incrementGlobal();
        InOrder order = inOrder(epochMapper, configMapper);
        order.verify(epochMapper).selectGlobalForUpdate();
        // 旧値の読取とput内の既存行確認（2回）→ 書込み → 書込み後の値の読取 → 増分
        order.verify(configMapper, times(2)).selectById(SCOPE_KEY);
        order.verify(configMapper).updateById(any(SystemConfig.class));
        order.verify(configMapper).selectById(SCOPE_KEY);
        order.verify(epochMapper).incrementGlobal();

        completeTransaction();
        beginTransaction();
        when(configMapper.selectById(SCOPE_KEY)).thenReturn(config("true"));
        service.updateAll(List.of(new SystemConfig(SCOPE_KEY, "true", "営業の担当データ限定")));
        verify(epochMapper, times(1)).incrementGlobal();
        verify(epochMapper, times(2)).selectGlobalForUpdate();
    }

    @Test
    void 非認可キーだけの更新では全体epoch行をlockせず進めない() {
        SystemConfigMapper configMapper = mock(SystemConfigMapper.class);
        SystemConfigServiceImpl service = new SystemConfigServiceImpl(configMapper, new OutboundUrlGuard(), fence);
        when(configMapper.selectById("notice.bench-warn-days")).thenReturn(new SystemConfig("notice.bench-warn-days", "30", ""));

        service.updateAll(List.of(new SystemConfig("notice.bench-warn-days", "45", "ベンチ警告日数")));

        verify(configMapper).updateById(any(SystemConfig.class));
        verify(epochMapper, never()).selectGlobalForUpdate();
        verify(epochMapper, never()).incrementGlobal();
    }

    @Test
    void 認可キーは全体epoch行をlockしていないtransactionからputで書けない() {
        SystemConfigMapper configMapper = mock(SystemConfigMapper.class);
        SystemConfigServiceImpl service = new SystemConfigServiceImpl(configMapper, new OutboundUrlGuard(), fence);

        assertThrows(IllegalStateException.class, () -> service.put(SCOPE_KEY, "true", "直接put"));
        completeTransaction();
        assertThrows(IllegalStateException.class, () -> service.put(SCOPE_KEY, "true", "transaction外のput"));

        verify(configMapper, never()).insert(any(SystemConfig.class));
        verify(configMapper, never()).updateById(any(SystemConfig.class));
    }

    @Test
    void システム管理キーを含む要求は全体epoch行をlockする前に拒否する() {
        SystemConfigMapper configMapper = mock(SystemConfigMapper.class);
        SystemConfigServiceImpl service = new SystemConfigServiceImpl(configMapper, new OutboundUrlGuard(), fence);

        BusinessException error = assertThrows(BusinessException.class, () -> service.updateAll(List.of(
                new SystemConfig(SCOPE_KEY, "true", ""), new SystemConfig("closing.confirmed-months", "[]", ""))));

        assertEquals(400, error.getCode());
        verify(epochMapper, never()).selectGlobalForUpdate();
    }

    // ===== PermissionGroupManagementServiceImpl =====

    @Test
    void 権限group割当が変わればlockした行のtenantでactorを1回だけ進め同じ集合では進めない() {
        PermissionFixture fixture = new PermissionFixture();
        when(fixture.userGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 2L)));

        fixture.service.replaceAssignments(7L, Set.of(3L), fixture.authentication);
        fixture.service.replaceAssignments(7L, Set.of(4L), fixture.authentication);

        // 設定tenant（tenant-a）ではなく、lockした利用者行のtenant（tenant-db）で進める。
        verify(epochMapper, times(1)).incrementActor(7L, "tenant-db");
        verify(epochMapper, never()).incrementActor(anyLong(), org.mockito.ArgumentMatchers.eq("tenant-a"));

        completeTransaction();
        beginTransaction();
        when(fixture.userGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 3L)));
        fixture.service.replaceAssignments(7L, Set.of(3L), fixture.authentication);
        verify(epochMapper, times(1)).incrementActor(7L, "tenant-db");
    }

    @Test
    void 権限group割当でactor_epochが上限なら409で止めsessionを失効しない() {
        PermissionFixture fixture = new PermissionFixture();
        when(fixture.userGroupMapper.selectList(any())).thenReturn(List.of(assignment(7L, 2L)));
        when(epochMapper.incrementActor(7L, "tenant-db")).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.replaceAssignments(7L, Set.of(3L), fixture.authentication));

        assertEquals(409, error.getCode());
        verify(fixture.sessionService, never()).revokeAllForUser(anyLong(), anyString());
    }

    @Test
    void actor_anchorはlockした行のIDとtenantが無ければ409にする() {
        SysUser noTenant = new SysUser();
        noTenant.setId(7L);
        BusinessException error = assertThrows(BusinessException.class, () -> ActorEpochAnchors.of(noTenant));
        assertEquals(409, error.getCode());
        assertThrows(BusinessException.class, () -> ActorEpochAnchors.of(null));
        SysUser locked = new SysUser();
        locked.setId(7L);
        locked.setTenantId("tenant-db");
        assertEquals(AuthorizationEpochAnchor.actor("tenant-db", 7L), ActorEpochAnchors.of(locked));
    }

    // ===== helper =====

    private RoleMenuServiceImpl roleMenuService(RoleMenuMapper roleMenuMapper, MenuCacheService menuCache) {
        RoleMenuServiceImpl service = new RoleMenuServiceImpl(mock(MenuMapper.class), menuCache, fence);
        ReflectionTestUtils.setField(service, "baseMapper", roleMenuMapper);
        return service;
    }

    private final class PermissionFixture {
        final PermissionGroupMapper groupMapper = mock(PermissionGroupMapper.class);
        final UserPermissionGroupMapper userGroupMapper = mock(UserPermissionGroupMapper.class);
        final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
        final PersistentSessionService sessionService = mock(PersistentSessionService.class);
        final PermissionGroupManagementServiceImpl service;
        final Authentication authentication;

        PermissionFixture() {
            OidcSecurityProperties properties = new OidcSecurityProperties();
            properties.setTenantId("tenant-a");
            service = new PermissionGroupManagementServiceImpl(groupMapper, userGroupMapper, sysUserMapper,
                    auditLogMapper, sessionService, properties, mock(PermissionGroupActionMapper.class),
                    mock(NotificationService.class), fence);
            SysUser target = new SysUser();
            target.setId(7L);
            target.setTenantId("tenant-db");
            target.setRole("営業");
            when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(target);
            // 要求した1件のgroupが有効として見つかる（件数照合だけを満たせばよい）。
            when(groupMapper.selectList(any())).thenReturn(List.of(group(3L)));
            when(userGroupMapper.insert(any(UserPermissionGroup.class))).thenReturn(1);
            when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);
            SysUser operator = new SysUser();
            operator.setId(1L);
            operator.setUsername("admin");
            operator.setRole("管理者");
            LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
            authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }

    private static PermissionGroup group(Long id) {
        PermissionGroup group = new PermissionGroup();
        group.setId(id);
        group.setTenantId("tenant-a");
        group.setGroupKey("custom-" + id);
        group.setEnabled(1);
        return group;
    }

    private static UserPermissionGroup assignment(Long userId, Long groupId) {
        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setTenantId("tenant-a");
        assignment.setUserId(userId);
        assignment.setGroupId(groupId);
        return assignment;
    }

    private static RoleMenu roleMenu(Long menuId) {
        return RoleMenu.builder().role("営業").menuId(menuId).build();
    }

    private static SystemConfig config(String value) {
        return new SystemConfig(SCOPE_KEY, value, "営業の担当データ限定");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void completeTransaction() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    /** AbstractPlatformTransactionManager.suspend と同じ手順（同期のsuspend → 同期を外す）。 */
    private static List<TransactionSynchronization> suspend() {
        List<TransactionSynchronization> outer = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        outer.forEach(TransactionSynchronization::suspend);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        return outer;
    }

    private static void resume(List<TransactionSynchronization> outer) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        outer.forEach(TransactionSynchronizationManager::registerSynchronization);
        outer.forEach(TransactionSynchronization::resume);
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
