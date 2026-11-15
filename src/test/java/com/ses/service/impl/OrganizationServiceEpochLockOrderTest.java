package com.ses.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.security.epoch.ActorShareLock;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U8（NF01細粒度epoch計画 §3.4・§4.2・§6.2、W3-16a後半 組織・所属）: OrganizationServiceImpl のwriterが、anchorを
 * rank順（3 利用者 → 6 組織 → 7 要員 → 8 所属行）・同じrank内はID昇順にlockしてから書き、lockした行のtenantで
 * epochを進めることをmockで固定する（実DBでの待ち・増分はH2の {@code OrganizationScopeEpochWriterIntegrationTest} と
 * MySQLの {@code OrganizationScopeFenceMySqlTest}）。
 *
 * <ul>
 *   <li>組織の祖先鎖は発見した順ではなくID昇順にlockし、lock後の読み直しで鎖が変わっていれば書かずに409で止める。</li>
 *   <li>所属の登録は 組織行 → 所属行 の順（§11 F-4。旧実装は逆順）。上長はS、本人はXで、ID昇順。</li>
 *   <li>所属の一括終了は本メソッド自身がactorを進める（W3-15 review P3-6）。上長解除だけでも本人のactorを進める。</li>
 *   <li>統合は 影響ユーザー → 組織 → 要員 → 所属行 の順にlockする。</li>
 *   <li>epochを進められない409は握りつぶさずに呼出元へ伝える（業務更新ごとrollbackさせる）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceEpochLockOrderTest {

    private static final LocalDate VALID_FROM = LocalDate.of(2026, 1, 1);

    @Mock private OrganizationUnitMapper organizationUnitMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private CostCenterMapper costCenterMapper;
    @Mock private ManagementBudgetMapper managementBudgetMapper;
    @Mock private MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private AuthorizationEpochFence authorizationEpochFence;
    @Mock private ActorShareLock actorShareLock;
    @Mock private EngineerMapper engineerMapper;
    @InjectMocks private OrganizationServiceImpl service;

    /** lambda wrapper の列名解決に要るtable情報（Spring contextなしの単体試験のため手で初期化する）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(OrganizationUnit.class, UserOrganization.class, Engineer.class,
                com.ses.entity.CostCenter.class, com.ses.entity.ManagementBudget.class,
                com.ses.entity.MonthlyAccountingDimension.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", organizationUnitMapper);
        ReflectionTestUtils.setField(service, "engineerMapper", engineerMapper);
    }

    @Test
    void 祖先鎖は発見した順ではなくID昇順にlockし旧_新の鎖の組織部分木epochを進める() {
        // 対象5の旧祖先鎖は 5 → 9 → 2、新しい親7の祖先鎖は 7 → 3。発見順は 5, 9, 2, 7, 3 だが、lockはID昇順。
        stubOrganization(5L, 9L);
        stubOrganization(9L, 2L);
        stubOrganization(2L, null);
        stubOrganization(7L, 3L);
        stubOrganization(3L, null);
        when(organizationUnitMapper.updateById(any(OrganizationUnit.class))).thenReturn(1);
        OrganizationUnit patch = organization(5L, 7L);

        service.updateOrganization(patch, 0);

        InOrder order = inOrder(organizationUnitMapper, authorizationEpochFence);
        for (long id : new long[] {2L, 3L, 5L, 7L, 9L}) {
            order.verify(organizationUnitMapper).selectByIdForUpdate(id);
        }
        order.verify(organizationUnitMapper).updateById(patch);
        order.verify(authorizationEpochFence).advanceAll(organizationAnchors(2L, 3L, 5L, 7L, 9L));
    }

    @Test
    void 祖先鎖がlockの前後で変わると組織行を書かずに409で止める() {
        // discoveryでは 5 → 9 → 2 だったが、lock後の読み直しでは 5 の親が 8 に変わっている（lockしていない組織）。
        OrganizationUnit before = unit(5L, 9L);
        OrganizationUnit after = unit(5L, 8L);
        when(organizationUnitMapper.selectById(5L)).thenReturn(before, after);
        when(organizationUnitMapper.selectByIdForUpdate(5L)).thenReturn(before);
        stubOrganization(9L, 2L);
        stubOrganization(2L, null);
        stubOrganization(8L, null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateOrganization(organization(5L, 9L), 0));

        assertEquals(409, error.getCode());
        assertEquals("error.organization.versionConflict", error.getMessageKey());
        verify(organizationUnitMapper, never()).selectByIdForUpdate(8L);
        verify(organizationUnitMapper, never()).updateById(any(OrganizationUnit.class));
        verifyNoInteractions(authorizationEpochFence);
    }

    @Test
    void 所属の登録は上長S_本人X_対象組織_連携要員_所属行の順にlockし行のtenantでactorとengineerを進める() {
        // 上長（ID 10）は本人（ID 20）より小さいため先に共有lockする。tenantは行の値（要求・contextの値ではない）。
        SysUser user = user(20L, "tenant-b");
        when(sysUserMapper.selectByIdForUpdate(20L)).thenReturn(user);
        when(sysUserMapper.selectById(10L)).thenReturn(manager(10L));
        when(engineerAccountLinkMapper.selectByUserId(20L)).thenReturn(link(40L, 20L));
        when(organizationUnitMapper.selectByIdForUpdate(30L)).thenReturn(unit(30L, null));
        when(engineerMapper.selectScopeAnchorForUpdate(40L)).thenReturn(engineer(40L, "tenant-b"));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of());
        when(userOrganizationMapper.insert(any(UserOrganization.class))).thenReturn(1);
        UserOrganization assignment = UserOrganization.builder().userId(20L).organizationId(30L).managerUserId(10L)
                .primaryFlag(1).validFrom(VALID_FROM).build();

        service.assignUser(assignment);

        InOrder order = inOrder(actorShareLock, sysUserMapper, organizationUnitMapper, engineerMapper,
                userOrganizationMapper, authorizationEpochFence);
        order.verify(actorShareLock).lockActive(10L, null);
        order.verify(sysUserMapper).selectByIdForUpdate(20L);
        order.verify(organizationUnitMapper).selectByIdForUpdate(30L);
        order.verify(engineerMapper).selectScopeAnchorForUpdate(40L);
        order.verify(userOrganizationMapper).selectByUserForUpdate(20L);
        order.verify(userOrganizationMapper).insert(assignment);
        order.verify(authorizationEpochFence).advanceAll(
                List.of(AuthorizationEpochAnchor.actor("tenant-b", 20L), AuthorizationEpochAnchor.engineer("tenant-b", 40L)));
    }

    @Test
    void 上長のIDが本人より大きければ本人のX_lockを先に取る() {
        when(sysUserMapper.selectByIdForUpdate(20L)).thenReturn(user(20L, "default"));
        when(sysUserMapper.selectById(50L)).thenReturn(manager(50L));
        when(organizationUnitMapper.selectByIdForUpdate(30L)).thenReturn(unit(30L, null));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of());
        when(userOrganizationMapper.insert(any(UserOrganization.class))).thenReturn(1);

        service.assignUser(UserOrganization.builder().userId(20L).organizationId(30L).managerUserId(50L)
                .primaryFlag(0).validFrom(VALID_FROM).build());

        InOrder order = inOrder(sysUserMapper, actorShareLock, organizationUnitMapper, userOrganizationMapper);
        order.verify(sysUserMapper).selectByIdForUpdate(20L);
        order.verify(actorShareLock).lockActive(50L, null);
        order.verify(organizationUnitMapper).selectByIdForUpdate(30L);
        order.verify(userOrganizationMapper).selectByUserForUpdate(20L);
        // 連携要員が無ければ本人のactorだけを進める。
        verify(authorizationEpochFence).advanceAll(List.of(AuthorizationEpochAnchor.actor("default", 20L)));
    }

    @Test
    void 所属の一括終了は本人_連携要員_所属行_上長解除の順にlockしactorとengineerの増分を自分で行う() {
        when(sysUserMapper.selectByIdForUpdateIncludingDeleted(20L)).thenReturn(user(20L, "default"));
        when(engineerAccountLinkMapper.selectByUserId(20L)).thenReturn(link(40L, 20L));
        when(engineerMapper.selectScopeAnchorForUpdate(40L)).thenReturn(engineer(40L, "default"));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of(openAssignment(100L, 20L)));
        when(userOrganizationMapper.updateById(any(UserOrganization.class))).thenReturn(1);
        when(userOrganizationMapper.clearManager(20L)).thenReturn(2);

        assertEquals(1, service.closeAssignmentsForUser(20L, LocalDate.of(2026, 6, 30)));

        InOrder order = inOrder(sysUserMapper, engineerMapper, userOrganizationMapper, authorizationEpochFence);
        order.verify(sysUserMapper).selectByIdForUpdateIncludingDeleted(20L);
        order.verify(engineerMapper).selectScopeAnchorForUpdate(40L);
        order.verify(userOrganizationMapper).selectByUserForUpdate(20L);
        order.verify(userOrganizationMapper).updateById(any(UserOrganization.class));
        order.verify(userOrganizationMapper).clearManager(20L);
        order.verify(authorizationEpochFence).advanceAll(
                List.of(AuthorizationEpochAnchor.actor("default", 20L), AuthorizationEpochAnchor.engineer("default", 40L)));
    }

    @Test
    void 所属の一括終了は上長解除だけでも本人のactorを進め何も変わらなければ進めない() {
        when(sysUserMapper.selectByIdForUpdateIncludingDeleted(20L)).thenReturn(user(20L, "default"));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of());
        when(userOrganizationMapper.clearManager(20L)).thenReturn(1);
        when(sysUserMapper.selectByIdForUpdateIncludingDeleted(21L)).thenReturn(user(21L, "default"));
        when(userOrganizationMapper.selectByUserForUpdate(21L)).thenReturn(List.of());
        when(userOrganizationMapper.clearManager(21L)).thenReturn(0);

        assertEquals(0, service.closeAssignmentsForUser(20L, LocalDate.of(2026, 6, 30)));
        verify(authorizationEpochFence).advance(AuthorizationEpochAnchor.actor("default", 20L));

        assertEquals(0, service.closeAssignmentsForUser(21L, LocalDate.of(2026, 6, 30)));
        verify(authorizationEpochFence, never()).advance(AuthorizationEpochAnchor.actor("default", 21L));
        verify(authorizationEpochFence, never()).advanceAll(any());
    }

    @Test
    void 統合は上長S_影響ユーザーX_組織_要員_所属行の順にlockし全anchorを1回の増分で進める() {
        // 統合元11（親なし）・統合先12（親なし）。影響ユーザー20（上長10）、連携要員40、統合元所属の要員41。
        OrganizationUnit source = unit(11L, null);
        OrganizationUnit target = unit(12L, null);
        when(organizationUnitMapper.selectById(11L)).thenReturn(source);
        when(organizationUnitMapper.selectById(12L)).thenReturn(target);
        when(organizationUnitMapper.selectByIdForUpdate(11L)).thenReturn(source);
        when(organizationUnitMapper.selectByIdForUpdate(12L)).thenReturn(target);
        when(organizationUnitMapper.updateById(any(OrganizationUnit.class))).thenReturn(1);
        UserOrganization sourceRow = openAssignment(100L, 20L);
        sourceRow.setOrganizationId(11L);
        sourceRow.setManagerUserId(10L);
        when(userOrganizationMapper.selectList(any())).thenReturn(List.of(sourceRow));
        when(userOrganizationMapper.selectByUserOrganizationForUpdate(eq(11L), any(LocalDate.class)))
                .thenReturn(List.of(sourceRow));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of(sourceRow));
        when(userOrganizationMapper.updateById(any(UserOrganization.class))).thenReturn(1);
        when(userOrganizationMapper.insert(any(UserOrganization.class))).thenReturn(1);
        when(sysUserMapper.selectByIdForUpdateIncludingDeleted(20L)).thenReturn(user(20L, "default"));
        when(engineerAccountLinkMapper.selectByUserId(20L)).thenReturn(link(40L, 20L));
        Engineer inSource = new Engineer();
        inSource.setId(41L);
        when(engineerMapper.selectList(any())).thenReturn(List.of(inSource));
        when(engineerMapper.selectScopeAnchorForUpdate(40L)).thenReturn(engineer(40L, "default"));
        when(engineerMapper.selectScopeAnchorForUpdate(41L)).thenReturn(engineer(41L, "default"));

        service.merge(11L, 12L, 0);

        InOrder order = inOrder(actorShareLock, sysUserMapper, organizationUnitMapper, engineerMapper,
                userOrganizationMapper, authorizationEpochFence);
        order.verify(actorShareLock).lockActive(10L, null);
        order.verify(sysUserMapper).selectByIdForUpdateIncludingDeleted(20L);
        order.verify(organizationUnitMapper).selectByIdForUpdate(11L);
        order.verify(organizationUnitMapper).selectByIdForUpdate(12L);
        order.verify(engineerMapper).selectScopeAnchorForUpdate(40L);
        order.verify(engineerMapper).selectScopeAnchorForUpdate(41L);
        order.verify(userOrganizationMapper).selectByUserForUpdate(20L);
        order.verify(userOrganizationMapper).selectByUserOrganizationForUpdate(eq(11L), any(LocalDate.class));
        order.verify(engineerMapper).reassignOrganization(11L, 12L);
        order.verify(organizationUnitMapper).updateById(source);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AuthorizationEpochAnchor>> anchors = ArgumentCaptor.forClass(Collection.class);
        order.verify(authorizationEpochFence).advanceAll(anchors.capture());
        assertEquals(Set.of(AuthorizationEpochAnchor.actor("default", 20L), AuthorizationEpochAnchor.organization(11L),
                        AuthorizationEpochAnchor.organization(12L), AuthorizationEpochAnchor.engineer("default", 40L),
                        AuthorizationEpochAnchor.engineer("default", 41L)),
                Set.copyOf(anchors.getValue()), "上長（S）は進めない。統合元・統合先・影響ユーザー・要員を1回ずつ");
        verify(authorizationEpochFence, never()).advance(any());
    }

    @Test
    void epochを進められない409は握りつぶさずに呼出元へ伝える() {
        when(sysUserMapper.selectByIdForUpdate(20L)).thenReturn(user(20L, "default"));
        when(organizationUnitMapper.selectByIdForUpdate(30L)).thenReturn(unit(30L, null));
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of());
        when(userOrganizationMapper.insert(any(UserOrganization.class))).thenReturn(1);
        BusinessException unavailable = BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        when(authorizationEpochFence.advanceAll(any())).thenThrow(unavailable);

        BusinessException error = assertThrows(BusinessException.class, () -> service.assignUser(
                UserOrganization.builder().userId(20L).organizationId(30L).primaryFlag(1).validFrom(VALID_FROM).build()));

        assertSame(unavailable, error, "業務更新と同じtransactionで投げ、rollbackさせる（E-6）");
        verify(actorShareLock, never()).lockActive(anyLong(), isNull());
    }

    // ===== helper =====

    private void stubOrganization(long id, Long parentId) {
        OrganizationUnit row = unit(id, parentId);
        when(organizationUnitMapper.selectById(id)).thenReturn(row);
        when(organizationUnitMapper.selectByIdForUpdate(id)).thenReturn(row);
    }

    private static List<AuthorizationEpochAnchor.Organization> organizationAnchors(long... ids) {
        Set<Long> sorted = new TreeSet<>();
        for (long id : ids) {
            sorted.add(id);
        }
        return sorted.stream().map(AuthorizationEpochAnchor::organization).collect(Collectors.toList());
    }

    private static OrganizationUnit unit(long id, Long parentId) {
        OrganizationUnit unit = OrganizationUnit.builder().code("ORG-" + id).name("組織" + id).type("部")
                .parentId(parentId).validFrom(VALID_FROM).status("有効").version(0).build();
        unit.setId(id);
        return unit;
    }

    private static OrganizationUnit organization(long id, Long parentId) {
        OrganizationUnit patch = unit(id, parentId);
        patch.setName("更新後の組織" + id);
        return patch;
    }

    private static SysUser user(long id, String tenantId) {
        SysUser user = SysUser.builder().tenantId(tenantId).username("user" + id).role("HR").status(1).build();
        user.setId(id);
        return user;
    }

    private static SysUser manager(long id) {
        SysUser manager = SysUser.builder().tenantId("default").username("manager" + id).role("マネージャー")
                .status(1).build();
        manager.setId(id);
        return manager;
    }

    private static EngineerAccountLink link(long engineerId, long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        return link;
    }

    private static Engineer engineer(long id, String tenantId) {
        Engineer engineer = new Engineer();
        engineer.setId(id);
        engineer.setTenantId(tenantId);
        return engineer;
    }

    private static UserOrganization openAssignment(long id, long userId) {
        UserOrganization row = UserOrganization.builder().userId(userId).organizationId(30L).primaryFlag(1)
                .validFrom(VALID_FROM).validTo(null).version(3).build();
        row.setId(id);
        return row;
    }
}
