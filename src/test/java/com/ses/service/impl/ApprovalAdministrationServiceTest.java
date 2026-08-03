package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.approval.ApprovalDelegationRequest;
import com.ses.dto.approval.ApprovalRoutePreviewRequest;
import com.ses.dto.approval.ApprovalRouteSaveRequest;
import com.ses.dto.approval.ApprovalRouteStepRequest;
import com.ses.dto.approval.ApprovalRouteView;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalDelegationTypeMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.approval.ApprovalAdministrationService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalAdministrationServiceTest {
    @Autowired ApprovalAdministrationService administrationService;
    @Autowired ApprovalEngineService approvalEngineService;
    @Autowired ApprovalRequestMapper requestMapper;
    @Autowired ApprovalDelegationMapper delegationMapper;
    @Autowired ApprovalDelegationTypeMapper delegationTypeMapper;
    @Autowired SysUserMapper userMapper;

    private Long applicantId;
    private Long approver1Id;
    private Long approver2Id;
    private Long delegateId;

    @BeforeEach
    void setUp() {
        applicantId = insertUser("a2-applicant");
        approver1Id = insertUser("a2-approver1");
        approver2Id = insertUser("a2-approver2");
        delegateId = insertUser("a2-delegate");
    }

    private Long insertUser(String prefix) {
        SysUser user = SysUser.builder().username(prefix + "-" + System.nanoTime()).password("x")
                .realName(prefix).role("管理者").status(1).build();
        userMapper.insert(user);
        return user.getId();
    }

    private ApprovalRouteSaveRequest route(String type, Long routeId, Long approver, LocalDate from) {
        return new ApprovalRouteSaveRequest(routeId, type, null, null, null, from, null,
                List.of(new ApprovalRouteStepRequest(1, 1, "USER", String.valueOf(approver), null)));
    }

    @Test
    void route改版は旧行とsnapshotを変更せずversionを増やす() {
        String type = "a2.version." + System.nanoTime();
        ApprovalRouteView first = administrationService.createRouteVersion(route(type, null, approver1Id, LocalDate.now()), applicantId);
        ApprovalRouteView second = administrationService.createRouteVersion(route(type, first.id(), approver2Id, LocalDate.now()), applicantId);
        assertEquals(1, first.versionNo());
        assertEquals(2, second.versionNo());
        assertNotEquals(first.id(), second.id());
        ApprovalRequest request = approvalEngineService.request(new ApprovalRequestCommand(type, "TEST", 1L, 1L,
                applicantId, null, BigDecimal.TEN, Map.of("v", 1), null, null));
        assertTrue(request.getRouteSnapshotJson().contains(String.valueOf(approver2Id)));
        String snapshot = request.getRouteSnapshotJson();
        administrationService.createRouteVersion(route(type, second.id(), approver1Id, LocalDate.now().plusDays(1)), applicantId);
        assertEquals(snapshot, requestMapper.selectById(request.getId()).getRouteSnapshotJson());
    }

    @Test
    void approverPreviewは指定asOfのversionと解決済み承認者を返す() {
        String type = "a2.preview." + System.nanoTime();
        ApprovalRouteView route = administrationService.createRouteVersion(route(type, null, approver1Id, LocalDate.now()), applicantId);
        var preview = administrationService.preview(new ApprovalRoutePreviewRequest(type, null, BigDecimal.ONE,
                applicantId, LocalDate.now()));
        assertEquals(route.id(), preview.routeId());
        assertEquals(List.of(approver1Id), preview.steps().get(0).resolvedApproverUserIds());
    }

    @Test
    void 代理期間が申請後に開始すると実行時点で承認可能になる() {
        String type = "a2.delegation-start." + System.nanoTime();
        administrationService.createRouteVersion(route(type, null, approver1Id, LocalDate.now()), applicantId);
        ApprovalDelegation delegation = ApprovalDelegation.builder().fromUserId(approver1Id).toUserId(delegateId)
                .validFrom(LocalDate.now().plusDays(1)).validTo(LocalDate.now().plusDays(3)).reason("休暇").build();
        delegationMapper.insert(delegation);
        ApprovalRequest request = approvalEngineService.request(new ApprovalRequestCommand(type, "TEST", 1L, 1L,
                applicantId, null, BigDecimal.ONE, Map.of(), null, null));
        assertThrows(BusinessException.class, () -> approvalEngineService.approve(request.getId(), delegateId, "早すぎる"));
        delegationMapper.update(null, new UpdateWrapper<ApprovalDelegation>().eq("id", delegation.getId())
                .set("valid_from", LocalDate.now()));
        approvalEngineService.approve(request.getId(), delegateId, "開始後");
        assertEquals("approved", requestMapper.selectById(request.getId()).getStatus());
    }

    @Test
    void 代理期間が申請後に終了すると実行時点で拒否される() {
        String type = "a2.delegation-end." + System.nanoTime();
        administrationService.createRouteVersion(route(type, null, approver1Id, LocalDate.now()), applicantId);
        ApprovalDelegation delegation = ApprovalDelegation.builder().fromUserId(approver1Id).toUserId(delegateId)
                .validFrom(LocalDate.now().minusDays(3)).validTo(LocalDate.now().plusDays(1)).reason("期間終了確認").build();
        delegationMapper.insert(delegation);
        ApprovalRequest request = approvalEngineService.request(new ApprovalRequestCommand(type, "TEST", 1L, 1L,
                applicantId, null, BigDecimal.ONE, Map.of(), null, null));
        delegationMapper.update(null, new UpdateWrapper<ApprovalDelegation>().eq("id", delegation.getId())
                .set("valid_to", LocalDate.now().minusDays(1)));
        assertThrows(BusinessException.class, () -> approvalEngineService.approve(request.getId(), delegateId, "終了後"));
    }

    @Test
    void 代理登録は対象種別理由承認者を監査表示できる() {
        var created = administrationService.createDelegation(new ApprovalDelegationRequest(approver1Id, delegateId,
                LocalDate.now(), null, List.of("contract.activate"), "長期休暇"), applicantId);
        assertEquals(approver1Id, created.fromUserId());
        assertEquals(delegateId, created.toUserId());
        assertEquals(List.of("contract.activate"), created.requestTypes());
        assertEquals(List.of("contract.activate"), delegationTypeMapper.selectRequestTypes(created.id()));
        assertEquals("長期休暇", created.reason());
        assertEquals(applicantId, created.approvedBy());

        administrationService.deleteDelegation(created.id());
        assertTrue(delegationTypeMapper.selectRequestTypes(created.id()).isEmpty());
        assertNull(delegationMapper.selectById(created.id()));
    }

    @Test
    void routeの固定USERに非数値を指定すると登録を拒否する() {
        String type = "a2.invalid-user-value." + System.nanoTime();
        ApprovalRouteSaveRequest request = new ApprovalRouteSaveRequest(null, type, null, null, null,
                LocalDate.now(), null,
                List.of(new ApprovalRouteStepRequest(1, 1, "USER", "not-a-user-id", null)));
        assertThrows(BusinessException.class, () -> administrationService.createRouteVersion(request, applicantId));
    }

    @Test
    void 代理の逆期間は登録を拒否する() {
        ApprovalDelegationRequest request = new ApprovalDelegationRequest(approver1Id, delegateId,
                LocalDate.now(), LocalDate.now().minusDays(1), List.of("contract.activate"), "期間不正");
        assertThrows(BusinessException.class, () -> administrationService.createDelegation(request, applicantId));
    }
}
