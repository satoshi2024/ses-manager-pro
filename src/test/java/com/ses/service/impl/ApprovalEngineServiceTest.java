package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T042(F1) L1〜L3: 申請から最初のstepへの到達、並列groupの全員承認/1人却下、
 * 職務分離(R1.4)による自己承認拒否、代理期間の内外(design §6.1)、
 * 同一slotへの二重click冪等性、version+current_stepのCASを検証する。
 * F2の対象adapterは未登録のため、最終承認は"approved"で終端するだけで業務operationは呼ばれない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalEngineServiceTest {

    @Autowired
    private ApprovalEngineService approvalEngineService;
    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;
    @Autowired
    private ApprovalActionMapper approvalActionMapper;
    @Autowired
    private ApprovalDelegationMapper approvalDelegationMapper;
    @Autowired
    private SysUserMapper sysUserMapper;

    private Long applicantId;
    private Long approver1Id;
    private Long approver2Id;
    private Long delegateId;

    @BeforeEach
    void setUp() {
        applicantId = insertUser("engine-applicant");
        approver1Id = insertUser("engine-approver1");
        approver2Id = insertUser("engine-approver2");
        delegateId = insertUser("engine-delegate");
    }

    private Long insertUser(String prefix) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x")
                .realName(prefix)
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 各stepを承認者id一覧として渡す（同一step内の複数idは並列group）。 */
    private void insertRoute(String requestType, List<List<Long>> steps) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId()).stepNo(stepNo).parallelGroup(stepNo)
                        .approverType("USER").approverValue(String.valueOf(approverId)).build();
                approvalRouteStepMapper.insert(step);
            }
        }
    }

    private ApprovalRequest request(String requestType) {
        return approvalEngineService.request(new ApprovalRequestCommand(
                requestType, "TEST", 1L, 1L, applicantId, null, BigDecimal.valueOf(1000),
                Map.of("k", "v"), null, null));
    }

    @Test
    void 申請すると最初のstepでin_reviewになる() {
        insertRoute("engine.simple", List.of(List.of(approver1Id)));
        ApprovalRequest req = request("engine.simple");
        assertEquals("in_review", req.getStatus());
        assertEquals(1, req.getCurrentStep());
        assertTrue(req.getRequestNo() != null && req.getRequestNo().startsWith("AR-"));
    }

    @Test
    void 単一承認者stepは承認で終端approvedになる() {
        insertRoute("engine.single-approve", List.of(List.of(approver1Id)));
        ApprovalRequest req = request("engine.single-approve");
        approvalEngineService.approve(req.getId(), approver1Id, "OK");
        assertEquals("approved", approvalRequestMapper.selectById(req.getId()).getStatus());
    }

    @Test
    void 並列groupは全員承認で次stepへ進む() {
        insertRoute("engine.parallel-advance", List.of(List.of(approver1Id, approver2Id), List.of(approver1Id)));
        ApprovalRequest req = request("engine.parallel-advance");

        approvalEngineService.approve(req.getId(), approver1Id, "1人目");
        ApprovalRequest afterFirst = approvalRequestMapper.selectById(req.getId());
        assertEquals("in_review", afterFirst.getStatus());
        assertEquals(1, afterFirst.getCurrentStep());

        approvalEngineService.approve(req.getId(), approver2Id, "2人目");
        ApprovalRequest afterSecond = approvalRequestMapper.selectById(req.getId());
        assertEquals("in_review", afterSecond.getStatus());
        assertEquals(2, afterSecond.getCurrentStep());
    }

    @Test
    void 並列groupの1人却下で即終端する() {
        insertRoute("engine.parallel-reject", List.of(List.of(approver1Id, approver2Id)));
        ApprovalRequest req = request("engine.parallel-reject");

        approvalEngineService.reject(req.getId(), approver1Id, "却下");
        assertEquals("rejected", approvalRequestMapper.selectById(req.getId()).getStatus());
    }

    @Test
    void 自己承認しか候補がいないrouteは申請作成時点で拒否される() {
        insertRoute("engine.self-approval", List.of(List.of(applicantId)));
        assertThrows(BusinessException.class, () -> request("engine.self-approval"));
    }

    @Test
    void 承認者ではないuserは承認できない() {
        insertRoute("engine.not-approver", List.of(List.of(approver1Id)));
        ApprovalRequest req = request("engine.not-approver");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalEngineService.approve(req.getId(), approver2Id, "権限なし"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 代理期間内は代理者が承認でき代理元がaction履歴へ記録される() {
        insertRoute("engine.delegate-in", List.of(List.of(approver1Id)));
        approvalDelegationMapper.insert(ApprovalDelegation.builder()
                .fromUserId(approver1Id).toUserId(delegateId)
                .validFrom(LocalDate.now().minusDays(1)).validTo(LocalDate.now().plusDays(1))
                .build());

        ApprovalRequest req = request("engine.delegate-in");
        approvalEngineService.approve(req.getId(), delegateId, "代理承認");

        assertEquals("approved", approvalRequestMapper.selectById(req.getId()).getStatus());
        List<ApprovalAction> actions = approvalActionMapper.selectList(
                new LambdaQueryWrapper<ApprovalAction>().eq(ApprovalAction::getRequestId, req.getId()));
        assertEquals(1, actions.size());
        assertEquals(approver1Id, actions.get(0).getDelegatedFrom());
        assertEquals(delegateId, actions.get(0).getApproverUserId());
    }

    @Test
    void 代理期間外は代理者が承認できない() {
        insertRoute("engine.delegate-out", List.of(List.of(approver1Id)));
        approvalDelegationMapper.insert(ApprovalDelegation.builder()
                .fromUserId(approver1Id).toUserId(delegateId)
                .validFrom(LocalDate.now().minusDays(10)).validTo(LocalDate.now().minusDays(5))
                .build());

        ApprovalRequest req = request("engine.delegate-out");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalEngineService.approve(req.getId(), delegateId, "期限切れ代理"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 本人と代理の同時解決は先着1件だけが有効になる() {
        insertRoute("engine.delegate-both", List.of(List.of(approver1Id, approver2Id)));
        approvalDelegationMapper.insert(ApprovalDelegation.builder()
                .fromUserId(approver1Id).toUserId(delegateId)
                .validFrom(LocalDate.now().minusDays(1)).validTo(LocalDate.now().plusDays(1))
                .build());
        ApprovalRequest req = request("engine.delegate-both");

        approvalEngineService.approve(req.getId(), approver1Id, "本人が先着");
        approvalEngineService.approve(req.getId(), delegateId, "代理は2件目、無視される");

        long approveActionsForSlot = approvalActionMapper.selectCount(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, req.getId())
                .eq(ApprovalAction::getApproverSlotUserId, approver1Id));
        assertEquals(1, approveActionsForSlot, "本人slotへのactionは1件のみのはず（2件目はCAS失敗で無視）");
        // approver2がまだ未承認のため、並列groupは完了していない
        assertEquals("in_review", approvalRequestMapper.selectById(req.getId()).getStatus());
    }

    @Test
    void 同一slotへの二重clickは業務操作を二重実行しない() {
        insertRoute("engine.double-click", List.of(List.of(approver1Id, approver2Id)));
        ApprovalRequest req = request("engine.double-click");

        approvalEngineService.approve(req.getId(), approver1Id, "1回目");
        approvalEngineService.approve(req.getId(), approver1Id, "2回目(二重click)");

        long count = approvalActionMapper.selectCount(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, req.getId())
                .eq(ApprovalAction::getApproverUserId, approver1Id)
                .eq(ApprovalAction::getAction, "APPROVE"));
        assertEquals(1, count);
        assertEquals("in_review", approvalRequestMapper.selectById(req.getId()).getStatus());
    }

    @Test
    void 終端到達後の再承認は状態不正として拒否される() {
        insertRoute("engine.post-terminal", List.of(List.of(approver1Id)));
        ApprovalRequest req = request("engine.post-terminal");
        approvalEngineService.approve(req.getId(), approver1Id, "承認");

        assertThrows(BusinessException.class, () -> approvalEngineService.approve(req.getId(), approver1Id, "再承認"));
    }

    @Test
    void request行のversionCASは古いversionでは0件更新になる() {
        insertRoute("engine.cas", List.of(List.of(approver1Id)));
        ApprovalRequest req = request("engine.cas");
        int staleVersionRows = 0;
        int fresh = approvalRequestMapper.selectById(req.getId()).getVersion();
        // 別transactionが先にversionを進めた状況を模す
        approvalRequestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", req.getId()).set("version", fresh + 1));
        // 古いversionを条件にしたCAS更新は0件のはず
        staleVersionRows = approvalRequestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", req.getId()).eq("version", fresh)
                .set("status", "approved").set("version", fresh + 1));
        assertEquals(0, staleVersionRows);
    }
}
