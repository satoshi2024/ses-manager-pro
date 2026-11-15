package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OrganizationRelationHistory;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationRelationHistoryMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.OrganizationService;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.DisableDefaultLegalEntityTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U8（NF01細粒度epoch計画 §2.2・§4.2・§7.4 Demo (2)(3)、W3-16a後半 組織・所属）: 実Spring beanとH2で、組織階層writer・
 * 統合・所属writerが同じtransactionで組織部分木epoch（{@code m_organization_unit.org_scope_epoch}）・actor epoch・
 * engineer epoch を進める条件を確かめる。
 *
 * <ul>
 *   <li>親の付替・無効化・削除・登録は、変化した組織自身と旧・新の祖先鎖だけを1進め、無関係な部分木は変えない。
 *       表示属性だけの更新・同じ状態への変更では進めない。</li>
 *   <li>統合は統合元・統合先・子組織と各祖先鎖、所属が変わる影響ユーザーのactor、統合元所属の要員と連携要員の
 *       engineerを1回ずつ進める。</li>
 *   <li>所属writerは本人のactorと本人の連携要員のengineerを進め、tenantはDBの行の値を使う。上長の変更でも進め、
 *       同じ値の更新では進めない。上長本人・他の利用者は変えない。</li>
 *   <li>所属の一括終了は自分で増分する（W3-15 review P3-6）。利用者の無効化との入れ子でも1回だけ。</li>
 *   <li>上限では409 {@code error.authorization.epochUnavailable} で止め、業務更新ごとrollback-onlyにする（E-6）。</li>
 * </ul>
 *
 * <p>クラス全体を {@code @Transactional} にし、試験の行・epochの変化はすべてrollbackする。fenceは同じtransactionの
 * 同じanchorを1回しか進めないため、前提の行はwriterを通さずmapperで作り、「進めない」確認は同じanchorを進める前に
 * 行う。epochは差分で比べる。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@DisableDefaultLegalEntityTestFixture
class OrganizationScopeEpochWriterIntegrationTest {

    private static final String TENANT = "default";
    private static final LocalDate VALID_FROM = LocalDate.of(2026, 1, 1);
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private OrganizationService organizationService;
    @Autowired private SysUserService sysUserService;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private OrganizationRelationHistoryMapper relationHistoryMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ===== 組織階層writer =====

    @Test
    void 親の付替は自身と旧_新の祖先鎖だけを1進め無関係な部分木は変えない() {
        long oldRoot = insertOrganization(null);
        long oldParent = insertOrganization(oldRoot);
        long moved = insertOrganization(oldParent);
        long newRoot = insertOrganization(null);
        long newParent = insertOrganization(newRoot);
        long unrelatedRoot = insertOrganization(null);
        long unrelatedChild = insertOrganization(unrelatedRoot);
        Map<Long, Long> before = organizationEpochs(oldRoot, oldParent, moved, newRoot, newParent, unrelatedRoot,
                unrelatedChild);

        assertTrue(organizationService.updateOrganization(patchOf(moved, newParent), 0));

        assertAdvanced(before, List.of(oldRoot, oldParent, moved, newRoot, newParent),
                List.of(unrelatedRoot, unrelatedChild));
        assertEquals(newParent, organizationService.getById(moved).getParentId());
    }

    @Test
    void 表示属性だけの更新では組織部分木epochを進めない() {
        long root = insertOrganization(null);
        long renamed = insertOrganization(root);
        Map<Long, Long> before = organizationEpochs(root, renamed);
        OrganizationUnit patch = patchOf(renamed, root);
        patch.setName("名称だけ変更");

        assertTrue(organizationService.updateOrganization(patch, 0));

        assertAdvanced(before, List.of(), List.of(root, renamed));
        assertEquals("名称だけ変更", organizationService.getById(renamed).getName());
    }

    @Test
    void 無効化は自身と祖先鎖を1進め同じ状態への変更では進めない() {
        long root = insertOrganization(null);
        long parent = insertOrganization(root);
        long leaf = insertOrganization(parent);
        long unrelated = insertOrganization(null);
        Map<Long, Long> before = organizationEpochs(root, parent, leaf, unrelated);

        assertTrue(organizationService.updateStatus(leaf, "有効", 0));
        assertAdvanced(before, List.of(), List.of(root, parent, leaf, unrelated));

        assertTrue(organizationService.updateStatus(leaf, "無効", 1));
        assertAdvanced(before, List.of(root, parent, leaf), List.of(unrelated));
        assertEquals("無効", organizationService.getById(leaf).getStatus());
    }

    @Test
    void 削除と登録はそれぞれの祖先鎖だけを1進め新しい組織自身は0のまま() {
        long removedRoot = insertOrganization(null);
        long removedParent = insertOrganization(removedRoot);
        long removed = insertOrganization(removedParent);
        long createdRoot = insertOrganization(null);
        long createdParent = insertOrganization(createdRoot);
        long unrelated = insertOrganization(null);
        Map<Long, Long> before = organizationEpochs(removedRoot, removedParent, removed, createdRoot, createdParent,
                unrelated);

        assertTrue(organizationService.removeById(removed));
        OrganizationUnit created = OrganizationUnit.builder().code("W316A2-NEW-" + SEQUENCE.incrementAndGet())
                .name("登録組織").type("課").parentId(createdParent).validFrom(VALID_FROM).status("有効").version(0)
                .build();
        assertTrue(organizationService.save(created));

        assertAdvanced(before, List.of(removedRoot, removedParent, removed, createdRoot, createdParent),
                List.of(unrelated));
        assertEquals(0L, organizationEpoch(created.getId()), "新規の組織はDEFAULT 0のまま（提出がまだ無い）");
    }

    @Test
    void 統合は組織と祖先鎖_影響ユーザー_統合元所属の要員と連携要員を1回ずつ進める() {
        long sourceRoot = insertOrganization(null);
        long source = insertOrganization(sourceRoot);
        long child = insertOrganization(source);
        long targetRoot = insertOrganization(null);
        long target = insertOrganization(targetRoot);
        long unrelatedOrganization = insertOrganization(null);
        long member = insertUser(TENANT, "HR");
        long otherUser = insertUser(TENANT, "HR");
        long linkedEngineer = insertEngineer(TENANT, null);
        insertLink(linkedEngineer, member, TENANT);
        long engineerInSource = insertEngineer(TENANT, source);
        long unrelatedEngineer = insertEngineer(TENANT, null);
        insertAssignment(member, source, null, 1);
        insertAssignment(otherUser, unrelatedOrganization, null, 1);
        Map<Long, Long> organizationsBefore = organizationEpochs(sourceRoot, source, child, targetRoot, target,
                unrelatedOrganization);
        long memberBefore = actorEpoch(member);
        long otherUserBefore = actorEpoch(otherUser);
        long linkedBefore = engineerEpoch(linkedEngineer);
        long inSourceBefore = engineerEpoch(engineerInSource);
        long unrelatedEngineerBefore = engineerEpoch(unrelatedEngineer);

        assertTrue(organizationService.merge(source, target, 0));

        assertAdvanced(organizationsBefore, List.of(sourceRoot, source, child, targetRoot, target),
                List.of(unrelatedOrganization));
        assertEquals(memberBefore + 1, actorEpoch(member), "所属が変わる影響ユーザーのactor");
        assertEquals(otherUserBefore, actorEpoch(otherUser));
        assertEquals(linkedBefore + 1, engineerEpoch(linkedEngineer), "影響ユーザーの連携要員");
        assertEquals(inSourceBefore + 1, engineerEpoch(engineerInSource), "統合元所属の要員");
        assertEquals(unrelatedEngineerBefore, engineerEpoch(unrelatedEngineer));
        assertEquals(target, organizationService.getById(child).getParentId());
        assertEquals(target, engineerMapper.selectById(engineerInSource).getOrganizationId());
    }

    // ===== 所属writer =====

    @Test
    void 所属の登録はDBの行のtenantで本人のactorと連携要員のengineerだけを1進め組織は変えない() {
        // 要求側のtenant context（default）と異なる行のtenant（tenant-b）で進む＝anchorはDBの行の値で作る。
        long organization = insertOrganization(null);
        long user = insertUser("tenant-b", "HR");
        long engineer = insertEngineer("tenant-b", null);
        insertLink(engineer, user, "tenant-b");
        long otherUser = insertUser(TENANT, "HR");
        long actorBefore = actorEpoch(user);
        long engineerBefore = engineerEpoch(engineer);
        long otherBefore = actorEpoch(otherUser);
        long organizationBefore = organizationEpoch(organization);

        organizationService.assignUser(UserOrganization.builder().userId(user).organizationId(organization)
                .primaryFlag(1).validFrom(VALID_FROM).build());

        assertEquals(actorBefore + 1, actorEpoch(user));
        assertEquals(engineerBefore + 1, engineerEpoch(engineer));
        assertEquals(otherBefore, actorEpoch(otherUser), "別の利用者は変えない（細粒度）");
        assertEquals(organizationBefore, organizationEpoch(organization), "所属の変化は組織部分木epochを進めない");
    }

    @Test
    void 所属の上長変更は本人のactorと連携要員のengineerを1進め同じ値の更新と上長本人は変えない() {
        long organization = insertOrganization(null);
        long user = insertUser(TENANT, "HR");
        long engineer = insertEngineer(TENANT, null);
        insertLink(engineer, user, TENANT);
        long manager = insertUser(TENANT, "マネージャー");
        long assignmentId = insertAssignment(user, organization, null, 1);
        long actorBefore = actorEpoch(user);
        long engineerBefore = engineerEpoch(engineer);
        long managerBefore = actorEpoch(manager);

        UserOrganization same = assignmentPatch(assignmentId, organization, null);
        assertTrue(organizationService.updateUserOrganization(same, 0));
        assertEquals(actorBefore, actorEpoch(user), "述語が読む列が同じ値なら進めない");
        assertEquals(engineerBefore, engineerEpoch(engineer));

        UserOrganization withManager = assignmentPatch(assignmentId, organization, manager);
        assertTrue(organizationService.updateUserOrganization(withManager, 1));
        assertEquals(actorBefore + 1, actorEpoch(user));
        assertEquals(engineerBefore + 1, engineerEpoch(engineer), "上長経路は連携要員のengineer epochで覆う");
        assertEquals(managerBefore, actorEpoch(manager), "上長の設定では上長本人を進めない");
        assertEquals(manager, userOrganizationMapper.selectById(assignmentId).getManagerUserId());
    }

    @Test
    void 所属の異動と解除はそれぞれ本人のactorと連携要員のengineerを1進める() {
        long from = insertOrganization(null);
        long to = insertOrganization(null);
        long movedUser = insertUser(TENANT, "HR");
        long movedEngineer = insertEngineer(TENANT, null);
        insertLink(movedEngineer, movedUser, TENANT);
        long releasedUser = insertUser(TENANT, "HR");
        long releasedEngineer = insertEngineer(TENANT, null);
        insertLink(releasedEngineer, releasedUser, TENANT);
        long movedRow = insertAssignment(movedUser, from, null, 1);
        long releasedRow = insertAssignment(releasedUser, from, null, 1);
        long movedActorBefore = actorEpoch(movedUser);
        long movedEngineerBefore = engineerEpoch(movedEngineer);
        long releasedActorBefore = actorEpoch(releasedUser);
        long releasedEngineerBefore = engineerEpoch(releasedEngineer);

        organizationService.transferUser(UserOrganization.builder().userId(movedUser).organizationId(to)
                .primaryFlag(1).validFrom(LocalDate.of(2026, 7, 1)).version(0).build(), 0);
        assertTrue(organizationService.releaseAssignment(releasedRow, LocalDate.of(2026, 6, 30), 0));

        assertEquals(movedActorBefore + 1, actorEpoch(movedUser));
        assertEquals(movedEngineerBefore + 1, engineerEpoch(movedEngineer));
        assertEquals(LocalDate.of(2026, 6, 30), userOrganizationMapper.selectById(movedRow).getValidTo());
        assertEquals(releasedActorBefore + 1, actorEpoch(releasedUser));
        assertEquals(releasedEngineerBefore + 1, engineerEpoch(releasedEngineer));
        assertEquals(LocalDate.of(2026, 6, 30), userOrganizationMapper.selectById(releasedRow).getValidTo());
    }

    @Test
    void 所属の一括終了は自分で本人のactorと連携要員のengineerを進め上長解除だけでもactorを進める() {
        long organization = insertOrganization(null);
        long other = insertOrganization(null);
        long leaver = insertUser(TENANT, "HR");
        long leaverEngineer = insertEngineer(TENANT, null);
        insertLink(leaverEngineer, leaver, TENANT);
        long member = insertUser(TENANT, "HR");
        long managerOnly = insertUser(TENANT, "マネージャー");
        long untouched = insertUser(TENANT, "HR");
        insertAssignment(leaver, organization, null, 1);
        long managedByLeaver = insertAssignment(member, organization, leaver, 1);
        long managedByManagerOnly = insertAssignment(member, other, managerOnly, 0);
        long leaverBefore = actorEpoch(leaver);
        long leaverEngineerBefore = engineerEpoch(leaverEngineer);
        long memberBefore = actorEpoch(member);
        long managerOnlyBefore = actorEpoch(managerOnly);
        long untouchedBefore = actorEpoch(untouched);

        assertEquals(1, organizationService.closeAssignmentsForUser(leaver, LocalDate.of(2026, 6, 30)));
        assertEquals(0, organizationService.closeAssignmentsForUser(managerOnly, LocalDate.of(2026, 6, 30)));
        assertEquals(0, organizationService.closeAssignmentsForUser(untouched, LocalDate.of(2026, 6, 30)));

        assertEquals(leaverBefore + 1, actorEpoch(leaver));
        assertEquals(leaverEngineerBefore + 1, engineerEpoch(leaverEngineer));
        assertEquals(managerOnlyBefore + 1, actorEpoch(managerOnly), "上長解除の影響は上長本人の述語だけ（計画 §2.2）");
        assertEquals(memberBefore, actorEpoch(member), "上長を外された側は進めない");
        assertEquals(untouchedBefore, actorEpoch(untouched), "何も変わらなければ進めない");
        assertNull(userOrganizationMapper.selectById(managedByLeaver).getManagerUserId());
        assertNull(userOrganizationMapper.selectById(managedByManagerOnly).getManagerUserId());
    }

    @Test
    void 利用者の無効化と所属の一括終了の入れ子でもactorとengineerは1回だけ進む() {
        Authentication operator = authenticate(insertUser(TENANT, "管理者"));
        long organization = insertOrganization(null);
        long user = insertUser(TENANT, "HR");
        long engineer = insertEngineer(TENANT, null);
        insertLink(engineer, user, TENANT);
        long row = insertAssignment(user, organization, null, 1);
        long actorBefore = actorEpoch(user);
        long engineerBefore = engineerEpoch(engineer);

        sysUserService.updateUserStatus(user, 0, operator);

        assertEquals(actorBefore + 1, actorEpoch(user), "statusの変化と所属の終了が同じtransactionでも1回");
        assertEquals(engineerBefore + 1, engineerEpoch(engineer));
        assertNotNull(userOrganizationMapper.selectById(row).getValidTo());
    }

    @Test
    void 既に無効な利用者の再無効化でも所属を閉じれば一括終了がactorを進める() {
        // W3-15 review P3-6: 呼出元（updateUserStatus）はstatusが変わらないため進めないが、所属の変化は漏らさない。
        Authentication operator = authenticate(insertUser(TENANT, "管理者"));
        long organization = insertOrganization(null);
        long user = insertUser(TENANT, "HR");
        jdbc.update("UPDATE sys_user SET status = 0 WHERE id = ?", user);
        long row = insertAssignment(user, organization, null, 1);
        long actorBefore = actorEpoch(user);

        sysUserService.updateUserStatus(user, 0, operator);

        assertEquals(actorBefore + 1, actorEpoch(user));
        assertNotNull(userOrganizationMapper.selectById(row).getValidTo());
    }

    @Test
    void 組織部分木epochが上限なら409で止め業務更新をrollback_onlyにする() {
        long root = insertOrganization(null);
        long leaf = insertOrganization(root);
        jdbc.update("UPDATE m_organization_unit SET org_scope_epoch = ? WHERE id = ?", Long.MAX_VALUE, root);

        BusinessException error = assertThrows(BusinessException.class,
                () -> organizationService.updateStatus(leaf, "無効", 0));

        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());
        assertEquals(Long.MAX_VALUE, organizationEpoch(root), "上限の値は変えない");
        Boolean rollbackOnly = new TransactionTemplate(transactionManager).execute(status -> status.isRollbackOnly());
        assertEquals(Boolean.TRUE, rollbackOnly, "状態の更新は業務更新ごとrollbackされる");
    }

    // ===== helper =====

    /** 組織と初版の親子履歴を、fenceを通さずに作る（同じtransactionの増分の重複除去に影響させない）。 */
    private long insertOrganization(Long parentId) {
        long seq = SEQUENCE.incrementAndGet();
        OrganizationUnit unit = OrganizationUnit.builder().code("W316A2-" + seq).name("W3-16a後半組織" + seq)
                .type("部").parentId(parentId).validFrom(VALID_FROM).status("有効").version(0).build();
        organizationUnitMapper.insert(unit);
        relationHistoryMapper.insert(OrganizationRelationHistory.builder().organizationId(unit.getId())
                .parentId(parentId).status("有効").validFrom(VALID_FROM).validTo(null).build());
        return unit.getId();
    }

    private OrganizationUnit patchOf(long organizationId, Long parentId) {
        OrganizationUnit current = organizationUnitMapper.selectById(organizationId);
        OrganizationUnit patch = OrganizationUnit.builder().code(current.getCode()).name(current.getName())
                .type(current.getType()).parentId(parentId).validFrom(current.getValidFrom())
                .status(current.getStatus()).build();
        patch.setId(organizationId);
        return patch;
    }

    private long insertUser(String tenantId, String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(tenantId).username("w316a2_" + seq).password("pass")
                .realName("W3-16a後半試験" + seq).email("w316a2-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private long insertEngineer(String tenantId, Long organizationId) {
        Engineer engineer = Engineer.builder().tenantId(tenantId).legalEntityId(1L).organizationId(organizationId)
                .fullName("W3-16a後半要員" + SEQUENCE.incrementAndGet()).status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private void insertLink(long engineerId, long userId, String tenantId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(tenantId);
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        engineerAccountLinkMapper.insert(link);
    }

    private long insertAssignment(long userId, long organizationId, Long managerUserId, int primaryFlag) {
        UserOrganization row = UserOrganization.builder().userId(userId).organizationId(organizationId)
                .managerUserId(managerUserId).primaryFlag(primaryFlag).validFrom(VALID_FROM).version(0).build();
        userOrganizationMapper.insert(row);
        return row.getId();
    }

    private static UserOrganization assignmentPatch(long assignmentId, long organizationId, Long managerUserId) {
        UserOrganization patch = UserOrganization.builder().organizationId(organizationId).managerUserId(managerUserId)
                .primaryFlag(1).validFrom(VALID_FROM).build();
        patch.setId(assignmentId);
        return patch;
    }

    private Authentication authenticate(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())), TENANT);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    private Map<Long, Long> organizationEpochs(long... organizationIds) {
        Map<Long, Long> epochs = new LinkedHashMap<>();
        for (long id : organizationIds) {
            epochs.put(id, organizationEpoch(id));
        }
        return epochs;
    }

    private void assertAdvanced(Map<Long, Long> before, List<Long> advanced, List<Long> unchanged) {
        for (Long id : advanced) {
            assertEquals(before.get(id) + 1, organizationEpoch(id), "組織 " + id + " は1だけ進める");
        }
        for (Long id : unchanged) {
            assertEquals(before.get(id), organizationEpoch(id), "組織 " + id + " は変えない");
        }
    }

    private long organizationEpoch(long organizationId) {
        return jdbc.queryForObject("SELECT org_scope_epoch FROM m_organization_unit WHERE id = ?", Long.class,
                organizationId);
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private long engineerEpoch(long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
    }
}
