package com.ses.service.impl;

import com.ses.common.constant.NotificationLinks;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.entity.Contract;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 契約更新エスカレーション（FR-06）の単体テスト。
 * 段階抽出・dedupeキーの安定性・対応済み(確定ドラフト)の除外を検証する。
 */
class RenewalEscalationServiceImplTest {

    private ContractMapper contractMapper;
    private SysUserMapper sysUserMapper;
    private SystemConfigService systemConfigService;
    private NotificationService notificationService;
    private RenewalEscalationServiceImpl service;

    @BeforeEach
    void setUp() {
        contractMapper = mock(ContractMapper.class);
        sysUserMapper = mock(SysUserMapper.class);
        systemConfigService = mock(SystemConfigService.class);
        notificationService = mock(NotificationService.class);
        service = new RenewalEscalationServiceImpl(contractMapper, sysUserMapper, systemConfigService, notificationService);
        when(systemConfigService.getString("renewal.escalation-days", "30:営業,14:上長")).thenReturn("30:営業,14:上長");
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of());
    }

    private Contract contract(Long id, LocalDate endDate, Long salesUserId) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractNo("C-" + id);
        c.setEndDate(endDate);
        c.setStatus(StatusConstants.CONTRACT_ACTIVE);
        c.setSalesUserId(salesUserId);
        return c;
    }

    @Test
    void escalateUnhandled_期限到達で担当営業へ通知() {
        // 30日前ステージに到達(endDate = today+30)
        Contract c = contract(1L, LocalDate.now().plusDays(30), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));

        int notified = service.escalateUnhandled();

        assertEquals(1, notified);
        verify(notificationService).publishToUser(eq(7L), eq("RENEWAL_ESCALATION"), anyString(), anyString(),
                eq(NotificationLinks.CONTRACT_RENEWAL_CALENDAR), anyString());
    }

    @Test
    void escalateUnhandled_期限未到達なら通知しない() {
        // どちらのステージにも未到達(endDate = today+40)
        Contract c = contract(1L, LocalDate.now().plusDays(40), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));

        int notified = service.escalateUnhandled();

        assertEquals(0, notified);
        verifyNoInteractions(notificationService);
    }

    @Test
    void escalateUnhandled_両ステージ到達なら営業と上長の両方へ通知() {
        // 14日前ステージにも到達(endDate = today+14) -> 30日前・14日前の両方が対象
        Contract c = contract(1L, LocalDate.now().plusDays(14), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));
        SysUser manager = new SysUser();
        manager.setId(99L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(manager));

        int notified = service.escalateUnhandled();

        assertEquals(2, notified); // 営業1件 + 上長1件
        verify(notificationService).publishToUser(eq(7L), eq("RENEWAL_ESCALATION"), anyString(), anyString(), anyString(), anyString());
        verify(notificationService).publishToUser(eq(99L), eq("RENEWAL_ESCALATION"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void escalateUnhandled_未帰属契約は全体通知にフォールバックする() {
        Contract c = contract(1L, LocalDate.now().plusDays(30), null);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));

        service.escalateUnhandled();

        verify(notificationService).publishToUser(isNull(), eq("RENEWAL_ESCALATION"), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void escalateUnhandled_確定ドラフトがある契約は対応済みとして除外する() {
        Contract c = contract(1L, LocalDate.now().plusDays(30), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));
        ContractDraftStatusDto confirmedDraft = new ContractDraftStatusDto();
        confirmedDraft.setRenewedFromContractId(1L);
        confirmedDraft.setStatus("稼動中"); // 確定 = 稼動中/終了のみ
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of(confirmedDraft));

        int notified = service.escalateUnhandled();

        assertEquals(0, notified);
        verifyNoInteractions(notificationService);
    }

    @Test
    void escalateUnhandled_未確定ドラフトのみでは対応済み扱いしない() {
        Contract c = contract(1L, LocalDate.now().plusDays(30), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));
        ContractDraftStatusDto pendingDraft = new ContractDraftStatusDto();
        pendingDraft.setRenewedFromContractId(1L);
        pendingDraft.setStatus("準備中"); // 未確定
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of(pendingDraft));

        int notified = service.escalateUnhandled();

        assertEquals(1, notified);
    }

    @Test
    void escalateUnhandled_解約済みドラフトは確定扱いせずエスカレーションを継続する() {
        // 一度自動生成されたドラフトが解約された場合、後続契約は実質存在しないため
        // 「確定(対応済み)」とみなしてエスカレーションを止めてはならない。
        Contract c = contract(1L, LocalDate.now().plusDays(30), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));
        ContractDraftStatusDto cancelledDraft = new ContractDraftStatusDto();
        cancelledDraft.setRenewedFromContractId(1L);
        cancelledDraft.setStatus("解約");
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of(cancelledDraft));

        int notified = service.escalateUnhandled();

        assertEquals(1, notified);
        verify(notificationService).publishToUser(eq(7L), eq("RENEWAL_ESCALATION"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void escalateUnhandled_複数契約が上長ステージに該当しても上長一覧は1回だけ問い合わせる() {
        Contract c1 = contract(1L, LocalDate.now().plusDays(14), 7L);
        Contract c2 = contract(2L, LocalDate.now().plusDays(14), 8L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1, c2));
        SysUser manager = new SysUser();
        manager.setId(99L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(manager));

        int notified = service.escalateUnhandled();

        assertEquals(4, notified); // (営業1+上長1) x 2契約
        verify(sysUserMapper, times(1)).selectList(any());
    }

    @Test
    void escalateUnhandled_同日2回実行してもdedupeKeyは同一で安定する() {
        Contract c = contract(1L, LocalDate.now().plusDays(30), 7L);
        when(contractMapper.selectList(any())).thenReturn(List.of(c));

        service.escalateUnhandled();
        ArgumentCaptor<String> firstKey = ArgumentCaptor.forClass(String.class);
        verify(notificationService).publishToUser(eq(7L), anyString(), anyString(), anyString(), anyString(), firstKey.capture());

        reset(notificationService);
        service.escalateUnhandled();
        ArgumentCaptor<String> secondKey = ArgumentCaptor.forClass(String.class);
        verify(notificationService).publishToUser(eq(7L), anyString(), anyString(), anyString(), anyString(), secondKey.capture());

        assertEquals(firstKey.getValue(), secondKey.getValue());
    }

    @Test
    void escalateUnhandled_対象契約が無ければ何もしない() {
        when(contractMapper.selectList(any())).thenReturn(List.of());

        int notified = service.escalateUnhandled();

        assertEquals(0, notified);
        verifyNoInteractions(notificationService);
        verify(contractMapper, never()).selectDraftStatusesByOriginalIds(any());
    }
}
