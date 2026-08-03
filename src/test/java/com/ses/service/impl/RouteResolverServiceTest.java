package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.SysUserMapper;
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

    private ResolvedRoute resolve(String requestType, BigDecimal amount) {
        return routeResolverService.resolve(requestType, null, amount, applicantId, LocalDate.now());
    }
}
