package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalResponsibility;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.PermissionGroup;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.ApprovalResponsibilityMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T042(F1) L1〜L3: design §6.2の金額帯境界(inclusive/inclusive)、
 * 該当routeなしの拒否、負の金額の絶対値判定、職務分離(R1.4)による自己承認候補の除外、
 * 複数route該当時の決定順（組織の具体性→金額帯の狭さ→version_noの新しさ）を検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RouteResolverServiceTest {

    @Autowired
    private RouteResolverService routeResolverService;
    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private ApprovalResponsibilityMapper approvalResponsibilityMapper;
    @Autowired
    private PermissionGroupMapper permissionGroupMapper;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;
    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    private Long approverId;
    private Long applicantId;

    @BeforeEach
    void setUp() {
        approverId = insertUser("route-approver");
        applicantId = insertUser("route-applicant");
    }

    private Long insertUser(String prefix) {
        return insertUser(prefix, "管理者");
    }

    private Long insertUser(String prefix, String role) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x")
                .realName(prefix)
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private Long insertRoute(String requestType, BigDecimal min, BigDecimal max, int versionNo, Long orgId,
                              Long approverUserId) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L)
                .requestType(requestType)
                .organizationId(orgId)
                .minAmount(min)
                .maxAmount(max)
                .versionNo(versionNo)
                .validFrom(LocalDate.now().minusDays(1))
                .validTo(null)
                .activeFlag(1)
                .build();
        approvalRouteMapper.insert(route);
        ApprovalRouteStep step = ApprovalRouteStep.builder()
                .routeId(route.getId())
                .stepNo(1)
                .parallelGroup(1)
                .approverType("USER")
                .approverValue(String.valueOf(approverUserId))
                .build();
        approvalRouteStepMapper.insert(step);
        return route.getId();
    }

    @Test
    void 金額帯の境界はinclusiveで判定される() {
        insertRoute("route.boundary", BigDecimal.valueOf(10000), BigDecimal.valueOf(50000), 1, null, approverId);

        assertThrows(BusinessException.class, () -> resolve("route.boundary", BigDecimal.valueOf(9999)));
        assertNotNull(resolve("route.boundary", BigDecimal.valueOf(10000)));
        assertNotNull(resolve("route.boundary", BigDecimal.valueOf(10001)));
        assertNotNull(resolve("route.boundary", BigDecimal.valueOf(49999)));
        assertNotNull(resolve("route.boundary", BigDecimal.valueOf(50000)));
        assertThrows(BusinessException.class, () -> resolve("route.boundary", BigDecimal.valueOf(50001)));
    }

    @Test
    void 該当routeが無い場合は拒否される() {
        assertThrows(BusinessException.class, () -> resolve("route.none-configured", BigDecimal.valueOf(1000)));
    }

    @Test
    void 負の金額は絶対値で金額帯判定される() {
        insertRoute("route.negative", BigDecimal.valueOf(10000), BigDecimal.valueOf(50000), 1, null, approverId);
        assertNotNull(resolve("route.negative", BigDecimal.valueOf(-20000)));
        assertThrows(BusinessException.class, () -> resolve("route.negative", BigDecimal.valueOf(-9999)));
    }

    @Test
    void 金額なし申請は金額帯を持たないrouteへのみ流れる() {
        insertRoute("route.noamount", BigDecimal.valueOf(10000), BigDecimal.valueOf(50000), 1, null, approverId);
        assertThrows(BusinessException.class,
                () -> routeResolverService.resolve("route.noamount", null, null, applicantId, LocalDate.now()));

        insertRoute("route.noamount2", null, null, 1, null, approverId);
        assertNotNull(routeResolverService.resolve("route.noamount2", null, null, applicantId, LocalDate.now()));
    }

    @Test
    void ROLEの1行は候補者のanyOfで1slotとして解決される() {
        String role = "営業";
        Long roleUser1 = insertUser("route-role-user1", role);
        Long roleUser2 = insertUser("route-role-user2", role);
        String type = "route.role-any-of." + System.nanoTime();
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(type).organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder()
                .routeId(route.getId()).stepNo(1).parallelGroup(1)
                .approverType("ROLE").approverValue(role).build());

        ResolvedRoute resolved = resolve(type, BigDecimal.ONE);

        assertEquals(1, resolved.steps().get(0).slots().size());
        List<Long> candidates = resolved.steps().get(0).slots().get(0).candidateUserIds();
        assertTrue(candidates.containsAll(List.of(roleUser1, roleUser2)));
    }

    @Test
    void 固定USERが不存在または無効なら承認者解決不能で拒否される() {
        insertRoute("route.invalid-user", null, null, 1, null, 999999999L);
        assertThrows(BusinessException.class,
                () -> routeResolverService.resolve("route.invalid-user", null, null, applicantId, LocalDate.now()));
    }

    @Test
    void 申請者自身しか承認候補が居ないrouteは承認者解決不能で拒否される() {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType("route.self").organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        ApprovalRouteStep step = ApprovalRouteStep.builder()
                .routeId(route.getId()).stepNo(1).parallelGroup(1)
                .approverType("USER").approverValue(String.valueOf(applicantId)).build();
        approvalRouteStepMapper.insert(step);

        assertThrows(BusinessException.class,
                () -> routeResolverService.resolve("route.self", null, null, applicantId, LocalDate.now()));
    }

    @Test
    void 組織の具体性が最優先で決まる() {
        Long specificOrgId = 999001L;
        insertRoute("route.tiebreak-org", BigDecimal.ZERO, BigDecimal.valueOf(100000), 1, null, approverId);
        Long specificRouteId = insertRoute("route.tiebreak-org", BigDecimal.ZERO, BigDecimal.valueOf(100000), 1,
                specificOrgId, approverId);

        ResolvedRoute resolved = routeResolverService.resolve("route.tiebreak-org", specificOrgId,
                BigDecimal.valueOf(5000), applicantId, LocalDate.now());
        assertEquals(specificRouteId, resolved.routeId());
    }

    @Test
    void 組織が同格なら金額帯が狭い方が優先される() {
        insertRoute("route.tiebreak-width", BigDecimal.ZERO, BigDecimal.valueOf(100000), 1, null, approverId);
        Long narrowRouteId = insertRoute("route.tiebreak-width", BigDecimal.ZERO, BigDecimal.valueOf(10000), 1,
                null, approverId);

        ResolvedRoute resolved = routeResolverService.resolve("route.tiebreak-width", null,
                BigDecimal.valueOf(5000), applicantId, LocalDate.now());
        assertEquals(narrowRouteId, resolved.routeId());
    }

    @Test
    void 組織と金額帯が同格ならversion_noが新しい方が優先される() {
        insertRoute("route.tiebreak-version", BigDecimal.ZERO, BigDecimal.valueOf(100000), 1, null, approverId);
        Long newerRouteId = insertRoute("route.tiebreak-version", BigDecimal.ZERO, BigDecimal.valueOf(100000), 2,
                null, approverId);

        ResolvedRoute resolved = routeResolverService.resolve("route.tiebreak-version", null,
                BigDecimal.valueOf(5000), applicantId, LocalDate.now());
        assertEquals(newerRouteId, resolved.routeId());
    }

    @Test
    void 申請者role条件がroute選択に反映される() {
        Long salesApplicant = insertUser("route-sales-applicant", "営業");
        Long hrApplicant = insertUser("route-hr-applicant", "HR");
        Long salesApprover = insertUser("route-sales-approver");
        Long hrApprover = insertUser("route-hr-approver");
        String type = "route.applicant-role." + System.nanoTime();
        Long salesRoute = insertRoleRoute(type, "営業", salesApprover);
        Long hrRoute = insertRoleRoute(type, "HR", hrApprover);

        assertEquals(salesRoute, routeResolverService.resolve(type, null, BigDecimal.ONE,
                salesApplicant, LocalDate.now()).routeId());
        assertEquals(hrRoute, routeResolverService.resolve(type, null, BigDecimal.ONE,
                hrApplicant, LocalDate.now()).routeId());
    }

    @Test
    void 申請者role条件routeを汎用routeより優先し該当しなければ汎用へfallbackする() {
        String type = "route.applicant-role-fallback." + System.nanoTime();
        Long salesApplicant = insertUser("route-sales-fallback-applicant", "営業");
        Long roleApprover = insertUser("route-sales-fallback-approver");
        Long genericRoute = insertRoute(type, null, null, 1, null, approverId);
        Long salesRoute = insertRoleRoute(type, "営業", roleApprover);

        assertEquals(salesRoute, routeResolverService.resolve(type, null, BigDecimal.ONE,
                salesApplicant, LocalDate.now()).routeId());
        assertEquals(genericRoute, routeResolverService.resolve(type, null, BigDecimal.ONE,
                insertUser("route-hr-fallback-applicant", "HR"), LocalDate.now()).routeId());
    }

    @Test
    void 追加approver_sourceが設定値とasOf責任者から解決される() {
        String type = "route.approver-sources." + System.nanoTime();
        Long groupApprover = insertUser("route-group-approver");
        Long organizationApprover = insertUser("route-organization-approver");
        Long financeApprover = insertUser("route-finance-approver");

        PermissionGroup group = new PermissionGroup();
        group.setTenantId("default");
        group.setGroupKey("approval-reviewers-" + System.nanoTime());
        group.setGroupName("承認レビュー担当");
        group.setEnabled(1);
        permissionGroupMapper.insert(group);
        UserPermissionGroup membership = new UserPermissionGroup();
        membership.setTenantId("default");
        membership.setUserId(groupApprover);
        membership.setGroupId(group.getId());
        userPermissionGroupMapper.insert(membership);

        OrganizationUnit organization = OrganizationUnit.builder()
                .tenantId(1L).code("APPROVAL-ORG-" + System.nanoTime()).name("承認対象組織")
                .type("部").validFrom(LocalDate.now().minusDays(1)).status("有効").version(0).build();
        organizationUnitMapper.insert(organization);
        approvalResponsibilityMapper.insert(ApprovalResponsibility.builder()
                .tenantId(1L).responsibilityType("ORGANIZATION_MANAGER")
                .organizationId(organization.getId()).userId(organizationApprover)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build());
        approvalResponsibilityMapper.insert(ApprovalResponsibility.builder()
                .tenantId(1L).responsibilityType("FINANCE_MANAGER")
                .organizationId(null).userId(financeApprover)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build());

        ApprovalRoute route = ApprovalRoute.builder().tenantId(1L).requestType(type)
                .organizationId(organization.getId()).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder().routeId(route.getId()).stepNo(1)
                .parallelGroup(1).approverType("PERMISSION_GROUP").approverValue(group.getGroupKey()).build());
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder().routeId(route.getId()).stepNo(1)
                .parallelGroup(1).approverType("ORGANIZATION_MANAGER").build());
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder().routeId(route.getId()).stepNo(1)
                .parallelGroup(1).approverType("FINANCE_MANAGER").build());

        ResolvedRoute resolved = routeResolverService.resolve(type, organization.getId(), BigDecimal.ONE,
                applicantId, LocalDate.now());
        List<Long> resolvedIds = resolved.steps().get(0).approverUserIds();
        assertTrue(resolvedIds.containsAll(List.of(groupApprover, organizationApprover, financeApprover)));
    }

    @Test
    void 責任者のvalid_from_valid_toと組織scopeは両端inclusiveで不一致を拒否する() {
        LocalDate today = LocalDate.now();
        Long organizationA = insertOrganization("route-boundary-org-a");
        Long organizationB = insertOrganization("route-boundary-org-b");
        String type = "route.responsibility-boundary." + System.nanoTime();

        insertSourceRoute(type, "ORGANIZATION_MANAGER", null, null,
                today.minusDays(2), today.plusDays(2));
        insertResponsibility("ORGANIZATION_MANAGER", organizationA, approverId, today, today);

        ResolvedRoute onStart = resolveAt(type, organizationA, today);
        assertTrue(onStart.steps().get(0).approverUserIds().contains(approverId));
        assertThrows(BusinessException.class,
                () -> resolveAt(type, organizationA, today.minusDays(1)));
        assertThrows(BusinessException.class,
                () -> resolveAt(type, organizationA, today.plusDays(1)));
        assertThrows(BusinessException.class,
                () -> resolveAt(type, organizationB, today));
    }

    @Test
    void FINANCE_MANAGERは組織別と全社assignmentをasOfで解決する() {
        LocalDate today = LocalDate.now();
        Long organizationA = insertOrganization("route-finance-org-a");
        Long organizationB = insertOrganization("route-finance-org-b");
        Long organizationFinance = insertUser("route-finance-org-manager");
        Long globalFinance = insertUser("route-finance-global-manager");
        String type = "route.finance-scope." + System.nanoTime();

        insertSourceRoute(type, "FINANCE_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("FINANCE_MANAGER", organizationA, organizationFinance,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("FINANCE_MANAGER", null, globalFinance,
                today.minusDays(1), today.plusDays(1));

        List<Long> organizationCandidates = resolveAt(type, organizationA, today)
                .steps().get(0).approverUserIds();
        assertTrue(organizationCandidates.containsAll(List.of(organizationFinance, globalFinance)));

        List<Long> otherOrganizationCandidates = resolveAt(type, organizationB, today)
                .steps().get(0).approverUserIds();
        assertTrue(otherOrganizationCandidates.contains(globalFinance));
        assertTrue(!otherOrganizationCandidates.contains(organizationFinance));

        List<Long> tenantWideCandidates = resolveAt(type, null, today)
                .steps().get(0).approverUserIds();
        assertEquals(List.of(globalFinance), tenantWideCandidates);
    }

    @Test
    void permission_groupは無効groupと削除済みmembershipと無効削除済みuserを候補から除外する() {
        PermissionGroup group = insertPermissionGroup("route-active-group", 1);
        Long activeMember = insertUser("route-group-active-member");
        Long disabledMember = insertUser("route-group-disabled-member");
        Long deletedMember = insertUser("route-group-deleted-member");
        Long deletedMembershipMember = insertUser("route-group-membership");
        insertMembership(group.getId(), activeMember);
        insertMembership(group.getId(), disabledMember);
        insertMembership(group.getId(), deletedMember);
        UserPermissionGroup deletedMembership = insertMembership(group.getId(), deletedMembershipMember);

        SysUser disabled = new SysUser();
        disabled.setId(disabledMember);
        disabled.setStatus(0);
        sysUserMapper.updateById(disabled);
        sysUserMapper.deleteById(deletedMember);
        userPermissionGroupMapper.deleteById(deletedMembership.getId());

        String type = "route.permission-group-filter." + System.nanoTime();
        insertSourceRoute(type, "PERMISSION_GROUP", group.getGroupKey(), null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        List<Long> candidates = resolve(type, BigDecimal.ONE).steps().get(0).approverUserIds();
        assertEquals(List.of(activeMember), candidates);

        PermissionGroup disabledGroup = insertPermissionGroup("route-disabled-group", 1);
        insertMembership(disabledGroup.getId(), activeMember);
        String disabledGroupType = "route.permission-group-disabled." + System.nanoTime();
        insertSourceRoute(disabledGroupType, "PERMISSION_GROUP", disabledGroup.getGroupKey(), null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        disabledGroup.setEnabled(0);
        permissionGroupMapper.updateById(disabledGroup);
        assertThrows(BusinessException.class, () -> resolve(disabledGroupType, BigDecimal.ONE));

        PermissionGroup deletedGroup = insertPermissionGroup("route-deleted-group", 1);
        insertMembership(deletedGroup.getId(), activeMember);
        String deletedGroupType = "route.permission-group-deleted." + System.nanoTime();
        insertSourceRoute(deletedGroupType, "PERMISSION_GROUP", deletedGroup.getGroupKey(), null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        permissionGroupMapper.deleteById(deletedGroup.getId());
        assertThrows(BusinessException.class, () -> resolve(deletedGroupType, BigDecimal.ONE));
    }

    @Test
    void 各approver_sourceの候補0件はfail_closedになる() {
        LocalDate today = LocalDate.now();
        PermissionGroup group = insertPermissionGroup("route-empty-group", 1);
        String groupType = "route.empty-group." + System.nanoTime();
        insertSourceRoute(groupType, "PERMISSION_GROUP", group.getGroupKey(), null,
                today.minusDays(1), today.plusDays(1));

        Long organization = insertOrganization("route-empty-responsibility-org");
        String organizationType = "route.empty-organization-manager." + System.nanoTime();
        insertSourceRoute(organizationType, "ORGANIZATION_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));

        String financeType = "route.empty-finance-manager." + System.nanoTime();
        insertSourceRoute(financeType, "FINANCE_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));

        assertThrows(BusinessException.class, () -> resolve(groupType, BigDecimal.ONE));
        assertThrows(BusinessException.class,
                () -> resolveAt(organizationType, organization, today));
        assertThrows(BusinessException.class,
                () -> resolveAt(financeType, organization, today));
    }

    @Test
    void 各approver_sourceで申請者自身しかいない場合はfail_closedになる() {
        LocalDate today = LocalDate.now();
        PermissionGroup group = insertPermissionGroup("route-self-group", 1);
        insertMembership(group.getId(), applicantId);
        String groupType = "route.self-group." + System.nanoTime();
        insertSourceRoute(groupType, "PERMISSION_GROUP", group.getGroupKey(), null,
                today.minusDays(1), today.plusDays(1));

        Long organization = insertOrganization("route-self-responsibility-org");
        String organizationType = "route.self-organization-manager." + System.nanoTime();
        insertSourceRoute(organizationType, "ORGANIZATION_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("ORGANIZATION_MANAGER", organization, applicantId,
                today.minusDays(1), today.plusDays(1));

        String financeType = "route.self-finance-manager." + System.nanoTime();
        insertSourceRoute(financeType, "FINANCE_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("FINANCE_MANAGER", organization, applicantId,
                today.minusDays(1), today.plusDays(1));

        assertThrows(BusinessException.class, () -> resolve(groupType, BigDecimal.ONE));
        assertThrows(BusinessException.class,
                () -> resolveAt(organizationType, organization, today));
        assertThrows(BusinessException.class,
                () -> resolveAt(financeType, organization, today));
    }

    @Test
    void 責任者の無効userと削除済みuserは候補0件として拒否される() {
        LocalDate today = LocalDate.now();
        Long organization = insertOrganization("route-invalid-responsibility-org");
        Long disabledManager = insertUser("route-disabled-manager");
        SysUser disabled = new SysUser();
        disabled.setId(disabledManager);
        disabled.setStatus(0);
        sysUserMapper.updateById(disabled);
        // S09 L4証跡のためテストのみ修正: System.nanoTime()桁数でVARCHAR(50)超過になるflakyを防ぐ
        String organizationType = "route.disabled-org-manager-" + System.nanoTime();
        insertSourceRoute(organizationType, "ORGANIZATION_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("ORGANIZATION_MANAGER", organization, disabledManager,
                today.minusDays(1), today.plusDays(1));

        Long deletedManager = insertUser("route-deleted-manager");
        String financeType = "route.deleted-finance-mgr-" + System.nanoTime();
        insertSourceRoute(financeType, "FINANCE_MANAGER", null, null,
                today.minusDays(1), today.plusDays(1));
        insertResponsibility("FINANCE_MANAGER", null, deletedManager,
                today.minusDays(1), today.plusDays(1));
        sysUserMapper.deleteById(deletedManager);

        assertThrows(BusinessException.class,
                () -> resolveAt(organizationType, organization, today));
        assertThrows(BusinessException.class,
                () -> resolveAt(financeType, organization, today));
    }

    // ==================== APPLICANT_MANAGER tests ====================

    @Test
    void APPLICANT_MANAGERはvalid_from当日を所属期間開始としてinclusiveで解決する() {
        LocalDate today = LocalDate.now();
        Long manager = insertUser("am-boundary-start-manager");
        Long org = insertOrganization("am-boundary-start-org");
        // valid_from = today, valid_to = today+1: asOf=today → 期間内（開始inclusive）
        insertUserOrganization(applicantId, org, manager, today, today.plusDays(1));
        String type = "route.am-boundary-start." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        List<Long> candidates = resolveAt(type, org, today).steps().get(0).approverUserIds();
        assertTrue(candidates.contains(manager),
                "valid_from当日はinclusiveなのでmanagerが候補に含まれる");
    }

    @Test
    void APPLICANT_MANAGERはvalid_to当日を所属期間終了としてinclusiveで解決する() {
        LocalDate today = LocalDate.now();
        Long manager = insertUser("am-boundary-end-manager");
        Long org = insertOrganization("am-boundary-end-org");
        // valid_from = today-1, valid_to = today: asOf=today → 期間内（終了inclusive）
        insertUserOrganization(applicantId, org, manager, today.minusDays(1), today);
        String type = "route.am-boundary-end." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        List<Long> candidates = resolveAt(type, org, today).steps().get(0).approverUserIds();
        assertTrue(candidates.contains(manager),
                "valid_to当日はinclusiveなのでmanagerが候補に含まれる");
    }

    @Test
    void APPLICANT_MANAGERは所属期間外のasOfでは候補0件になりfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long manager = insertUser("am-out-range-manager");
        Long org = insertOrganization("am-out-range-org");
        // valid_from = today-2, valid_to = today-1: asOf=today → 期間外
        insertUserOrganization(applicantId, org, manager, today.minusDays(2), today.minusDays(1));
        String type = "route.am-out-range." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(3), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "所属期間外asOfではAPPLICANT_MANAGERは0件になりfail-closed");
    }

    @Test
    void APPLICANT_MANAGERはmanager_user_idがNULLの場合は候補0件になりfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long org = insertOrganization("am-null-manager-org");
        // manager_user_id = null: 上長未設定
        insertUserOrganization(applicantId, org, null, today.minusDays(1), today.plusDays(1));
        String type = "route.am-null-manager." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "manager_user_idがNULLの場合は候補0件でfail-closed");
    }

    @Test
    void APPLICANT_MANAGERは無効managerを候補から除外しfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long disabledManager = insertUser("am-disabled-manager");
        Long org = insertOrganization("am-disabled-manager-org");
        insertUserOrganization(applicantId, org, disabledManager, today.minusDays(1), today.plusDays(1));
        // managerを無効化
        SysUser patch = new SysUser();
        patch.setId(disabledManager);
        patch.setStatus(0);
        sysUserMapper.updateById(patch);
        String type = "route.am-disabled-manager." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "無効manager(status=0)は候補から除外されfail-closed");
    }

    @Test
    void APPLICANT_MANAGERは削除済みmanagerを候補から除外しfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long deletedManager = insertUser("am-deleted-manager");
        Long org = insertOrganization("am-deleted-manager-org");
        insertUserOrganization(applicantId, org, deletedManager, today.minusDays(1), today.plusDays(1));
        // managerを論理削除
        sysUserMapper.deleteById(deletedManager);
        String type = "route.am-deleted-manager." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "論理削除済みmanagerは候補から除外されfail-closed");
    }

    @Test
    void APPLICANT_MANAGERは存在しないmanager_user_idをmapper境界で除外しfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long org = insertOrganization("am-nonexistent-manager-org");
        // V1の共有H2 schemaと実MySQL V60はともにmanager_user_idへFKを持つため、
        // 存在しないsys_user.idをt_user_organizationへ直接fixtureできない。
        // 代替としてmapper境界の不存在ID→nullと、DBで表現可能なNULL assignmentの
        // resolver fail-closedを同一回帰で確認する。実MySQL側のFK存在はFlyway smokeで検証する。
        assertNull(sysUserMapper.selectById(999999999L),
                "存在しないmanager_user_idはSysUserMapper境界でnullになる");
        insertUserOrganization(applicantId, org, null, today.minusDays(1), today.plusDays(1));
        String type = "route.am-nonexistent-manager." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "FK上表現できない不存在IDもmapper null/NULL assignmentでは候補0件としてfail-closed");
    }

    @Test
    void APPLICANT_MANAGERで申請者本人しかmanager候補にいない場合はfail_closedになる() {
        LocalDate today = LocalDate.now();
        Long org = insertOrganization("am-self-manager-org");
        // manager_user_id = applicantId 自身
        insertUserOrganization(applicantId, org, applicantId, today.minusDays(1), today.plusDays(1));
        String type = "route.am-self-manager." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(1), today.plusDays(10));

        assertThrows(BusinessException.class,
                () -> resolveAt(type, org, today),
                "申請者自身がmanagerの場合は職務分離(R1.4)で除外されfail-closed");
    }

    @Test
    void APPLICANT_MANAGERのroute解決はasOf時点のsnapshotで固定される() {
        LocalDate today = LocalDate.now();
        Long manager1 = insertUser("am-snapshot-manager-past");
        Long manager2 = insertUser("am-snapshot-manager-current");
        Long org = insertOrganization("am-snapshot-org");
        // 過去期間(today-5 〜 today-2): manager1
        insertUserOrganization(applicantId, org, manager1, today.minusDays(5), today.minusDays(2));
        // 現在期間(today-1 〜 today+5): manager2
        insertUserOrganization(applicantId, org, manager2, today.minusDays(1), today.plusDays(5));
        String type = "route.am-snapshot." + System.nanoTime();
        insertSourceRoute(type, "APPLICANT_MANAGER", null, null,
                today.minusDays(10), today.plusDays(10));

        // asOf=today-3（過去期間内）: manager1が候補
        List<Long> pastCandidates = resolveAt(type, org, today.minusDays(3)).steps().get(0).approverUserIds();
        assertTrue(pastCandidates.contains(manager1), "過去asOfではmanager1が候補");
        assertTrue(!pastCandidates.contains(manager2), "過去asOfではmanager2は候補外");

        // asOf=today（現在期間内）: manager2が候補
        List<Long> currentCandidates = resolveAt(type, org, today).steps().get(0).approverUserIds();
        assertTrue(!currentCandidates.contains(manager1), "現在asOfではmanager1は候補外");
        assertTrue(currentCandidates.contains(manager2), "現在asOfではmanager2が候補");
    }

    private void insertUserOrganization(Long userId, Long organizationId, Long managerUserId,
                                        LocalDate validFrom, LocalDate validTo) {
        com.ses.entity.UserOrganization uo = com.ses.entity.UserOrganization.builder()
                .userId(userId)
                .organizationId(organizationId)
                .managerUserId(managerUserId)
                .primaryFlag(1)
                .validFrom(validFrom)
                .validTo(validTo)  // valid_toを設定することでACTIVE_PRIMARY_USER_IDのUNIQUE制約に抵触しない
                .version(0)
                .build();
        userOrganizationMapper.insert(uo);
    }

    private Long insertOrganization(String prefix) {
        OrganizationUnit organization = OrganizationUnit.builder()
                .tenantId(1L).code(prefix + "-" + System.nanoTime()).name(prefix)
                .type("部").validFrom(LocalDate.now().minusDays(10)).status("有効").version(0).build();
        organizationUnitMapper.insert(organization);
        return organization.getId();
    }

    private PermissionGroup insertPermissionGroup(String prefix, int enabled) {
        PermissionGroup group = new PermissionGroup();
        group.setTenantId("default");
        group.setGroupKey(prefix + "-" + System.nanoTime());
        group.setGroupName(prefix);
        group.setEnabled(enabled);
        permissionGroupMapper.insert(group);
        return group;
    }

    private UserPermissionGroup insertMembership(Long groupId, Long userId) {
        UserPermissionGroup membership = new UserPermissionGroup();
        membership.setTenantId("default");
        membership.setGroupId(groupId);
        membership.setUserId(userId);
        userPermissionGroupMapper.insert(membership);
        return membership;
    }

    private Long insertSourceRoute(String requestType, String approverType, String approverValue,
                                   Long organizationId, LocalDate validFrom, LocalDate validTo) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).organizationId(organizationId)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(validFrom).validTo(validTo).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder()
                .routeId(route.getId()).stepNo(1).parallelGroup(1)
                .approverType(approverType).approverValue(approverValue).build());
        return route.getId();
    }

    private void insertResponsibility(String type, Long organizationId, Long userId,
                                      LocalDate validFrom, LocalDate validTo) {
        approvalResponsibilityMapper.insert(ApprovalResponsibility.builder()
                .tenantId(1L).responsibilityType(type).organizationId(organizationId).userId(userId)
                .validFrom(validFrom).validTo(validTo).activeFlag(1).build());
    }

    private ResolvedRoute resolveAt(String requestType, Long organizationId, LocalDate asOf) {
        return routeResolverService.resolve(requestType, organizationId, BigDecimal.ONE, applicantId, asOf);
    }

    private Long insertRoleRoute(String requestType, String applicantRole, Long approverUserId) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).applicantRoleCondition(applicantRole)
                .organizationId(null).minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).validTo(null).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder().routeId(route.getId()).stepNo(1)
                .parallelGroup(1).approverType("USER").approverValue(String.valueOf(approverUserId)).build());
        return route.getId();
    }

    private ResolvedRoute resolve(String requestType, BigDecimal amount) {
        return routeResolverService.resolve(requestType, null, amount, applicantId, LocalDate.now());
    }
}
