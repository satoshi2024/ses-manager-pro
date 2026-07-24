package com.ses.service.impl;

import com.ses.common.constant.RenewalState;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.dto.contract.RenewalCalendarItemDto;
import com.ses.dto.contract.RenewalCalendarResponseDto;
import com.ses.mapper.ContractMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 契約更新カレンダー（FR-06）状態導出・期間フィルタ・データスコープの単体テスト。
 */
class RenewalCalendarServiceImplTest {

    private ContractMapper contractMapper;
    private SystemConfigService systemConfigService;
    private DataScopeService dataScopeService;
    private RenewalCalendarServiceImpl service;

    @BeforeEach
    void setUp() {
        contractMapper = mock(ContractMapper.class);
        systemConfigService = mock(SystemConfigService.class);
        dataScopeService = mock(DataScopeService.class);
        service = new RenewalCalendarServiceImpl(contractMapper, systemConfigService, dataScopeService);
        when(systemConfigService.getInt("notice.contract-end-days", 30)).thenReturn(30);
        when(dataScopeService.isScoped()).thenReturn(false);
    }

    private RenewalCalendarItemDto item(Long id, LocalDate endDate, String decision) {
        RenewalCalendarItemDto dto = new RenewalCalendarItemDto();
        dto.setContractId(id);
        dto.setContractNo("C-" + id);
        dto.setEndDate(endDate);
        dto.setStatus(StatusConstants.CONTRACT_ACTIVE);
        dto.setRenewalDecision(decision);
        return dto;
    }

    private ContractDraftStatusDto draft(Long originalId, String status) {
        ContractDraftStatusDto d = new ContractDraftStatusDto();
        d.setRenewedFromContractId(originalId);
        d.setStatus(status);
        return d;
    }

    @Test
    void getCalendar_from_to_逆転は例外() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 7, 1);
        assertThrows(BusinessException.class, () -> service.getCalendar(from, to));
    }

    @Test
    void getCalendar_ドラフト無しは未対応() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        RenewalCalendarItemDto original = item(1L, LocalDate.of(2026, 7, 21), null);
        when(contractMapper.selectRenewalCalendarCandidates(eq(StatusConstants.CONTRACT_ACTIVE), any(), any(), eq(null), any()))
                .thenReturn(new ArrayList<>(List.of(original)));
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of());

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertEquals(1, res.getItems().size());
        assertEquals(RenewalState.UNHANDLED, res.getItems().get(0).getRenewalState());
        assertEquals(LocalDate.of(2026, 6, 21), res.getItems().get(0).getRenewalDueDate());
        assertFalse(res.isTruncated());
    }

    @Test
    void getCalendar_未確定ドラフトはDRAFT() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        RenewalCalendarItemDto original = item(1L, LocalDate.of(2026, 7, 21), null);
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(original)));
        when(contractMapper.selectDraftStatusesByOriginalIds(any()))
                .thenReturn(List.of(draft(1L, "準備中")));

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertEquals(RenewalState.DRAFT, res.getItems().get(0).getRenewalState());
    }

    @Test
    void getCalendar_確定ドラフトはCONFIRMED() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        RenewalCalendarItemDto original = item(1L, LocalDate.of(2026, 7, 21), null);
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(original)));
        when(contractMapper.selectDraftStatusesByOriginalIds(any()))
                .thenReturn(List.of(draft(1L, "稼動中")));

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertEquals(RenewalState.CONFIRMED, res.getItems().get(0).getRenewalState());
    }

    @Test
    void getCalendar_解約されたドラフトはCONFIRMEDにしない() {
        // ドラフトが解約された場合、後続契約は実質存在しない。準備中以外を一律「確定」扱いすると
        // 中止された更新が緑(対応済み)表示になり、エスカレーションも誤って停止してしまう。
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        RenewalCalendarItemDto original = item(1L, LocalDate.of(2026, 7, 21), null);
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(original)));
        when(contractMapper.selectDraftStatusesByOriginalIds(any()))
                .thenReturn(List.of(draft(1L, "解約")));

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertNotEquals(RenewalState.CONFIRMED, res.getItems().get(0).getRenewalState());
    }

    @Test
    void getCalendar_明示フラグはドラフト有無より優先される() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        RenewalCalendarItemDto continueItem = item(1L, LocalDate.of(2026, 7, 21), "CONTINUE");
        RenewalCalendarItemDto endItem = item(2L, LocalDate.of(2026, 7, 22), "END");
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(continueItem, endItem)));
        // 明示フラグ付きでも実装上はドラフト照会が呼ばれるが、判定には影響しない
        when(contractMapper.selectDraftStatusesByOriginalIds(any()))
                .thenReturn(List.of(draft(1L, "準備中"), draft(2L, "稼動中")));

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertEquals(RenewalState.CONTINUE, res.getItems().get(0).getRenewalState());
        assertEquals(RenewalState.END_SCHEDULED, res.getItems().get(1).getRenewalState());
    }

    @Test
    void getCalendar_期間フィルタはリード日数を加味してendDate範囲に変換される() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        service.getCalendar(from, to);

        verify(contractMapper).selectRenewalCalendarCandidates(
                eq(StatusConstants.CONTRACT_ACTIVE),
                eq(LocalDate.of(2026, 7, 31)),  // from + 30
                eq(LocalDate.of(2026, 8, 30)),  // to + 30
                eq(null),
                eq(1001));
    }

    @Test
    void getCalendar_スコープ中は許可契約IDのみ問い合わせる() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(5L, 6L));
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        service.getCalendar(from, to);

        verify(contractMapper).selectRenewalCalendarCandidates(any(), any(), any(),
                argThat(list -> list != null && list.containsAll(Set.of(5L, 6L)) && list.size() == 2), any());
    }

    @Test
    void getCalendar_スコープ中で許可契約が無ければマッパーを呼ばず空を返す() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of());

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertTrue(res.getItems().isEmpty());
        verify(contractMapper, never()).selectRenewalCalendarCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void getCalendar_1000件超は切り詰めてtruncated() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        List<RenewalCalendarItemDto> many = new ArrayList<>();
        for (long i = 1; i <= 1001; i++) {
            many.add(item(i, LocalDate.of(2026, 7, 21), null));
        }
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any())).thenReturn(many);
        when(contractMapper.selectDraftStatusesByOriginalIds(any())).thenReturn(List.of());

        RenewalCalendarResponseDto res = service.getCalendar(from, to);

        assertEquals(1000, res.getItems().size());
        assertTrue(res.isTruncated());
    }
}
