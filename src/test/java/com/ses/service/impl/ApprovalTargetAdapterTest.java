package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.Invoice;
import com.ses.entity.Quotation;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ContractService;
import com.ses.service.InvoiceService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.QuotationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalTargetAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void quotationAdapterは承認後に既存の状態変更とdraft化へ委譲する() throws Exception {
        QuotationService service = mock(QuotationService.class);
        QuotationApprovalAdapter adapter = new QuotationApprovalAdapter(mock(QuotationMapper.class), service, objectMapper);
        ApprovalRequest request = request(10L, Map.of("status", "受注", "createDraft", true));

        adapter.applyApproved(request);

        verify(service).changeStatus(10L, "受注");
        verify(service).createDraftFromQuotation(10L);
    }

    @Test
    void contractAdapterは稼動化を既存状態機械へ一度だけ委譲する() throws Exception {
        ContractService service = mock(ContractService.class);
        ContractApprovalAdapter adapter = new ContractApprovalAdapter(mock(ContractMapper.class), service, objectMapper);
        ApprovalRequest request = request(11L, Map.of("operation", "status", "status", "稼動中", "cancelDate", ""));

        adapter.applyApproved(request);

        verify(service).changeStatus(11L, "稼動中", null);
    }

    @Test
    void contractAdapterは単価改定を既存の単価改定へ一度だけ委譲する() throws Exception {
        ContractService service = mock(ContractService.class);
        ContractApprovalAdapter adapter = new ContractApprovalAdapter(mock(ContractMapper.class), service, objectMapper);
        ApprovalRequest request = request(12L, Map.of("operation", "revisePrice", "applyFromMonth", "2026-08",
                "sellingPrice", 900000, "costPrice", 600000, "reason", "改定"));

        adapter.applyApproved(request);

        verify(service).revisePrice(12L, "2026-08", new BigDecimal("900000"),
                new BigDecimal("600000"), "改定");
    }

    @Test
    void invoiceAdapterは送付と取消を既存serviceへ委譲する() throws Exception {
        InvoiceService service = mock(InvoiceService.class);
        InvoiceApprovalAdapter adapter = new InvoiceApprovalAdapter(mock(InvoiceMapper.class), service, objectMapper);

        adapter.applyApproved(request(13L, Map.of("operation", "send", "status", "送付済", "paidDate", "2026-08-01")));
        verify(service).changeStatus(13L, "送付済", LocalDate.of(2026, 8, 1));

        adapter.applyApproved(request(14L, Map.of("operation", "void", "status", "", "paidDate", "")));
        verify(service).voidInvoice(14L);
    }

    @Test
    void bpPaymentAdapterは支払確定を既存serviceへ委譲する() throws Exception {
        InvoiceService service = mock(InvoiceService.class);
        BpPaymentApprovalAdapter adapter = new BpPaymentApprovalAdapter(mock(BpPaymentMapper.class), service, objectMapper);
        ApprovalRequest request = request(15L, Map.of("status", "支払済", "paidDate", "2026-08-02"));

        adapter.applyApproved(request);

        verify(service).changeBpPaymentStatus(15L, "支払済", LocalDate.of(2026, 8, 2));
    }

    @Test
    void monthlyClosingAdapterは申請者ではなく最終承認者を監査主体へ渡す() throws Exception {
        MonthlyClosingService service = mock(MonthlyClosingService.class);
        ApprovalActionMapper actionMapper = mock(ApprovalActionMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        MonthlyClosingApprovalAdapter adapter = new MonthlyClosingApprovalAdapter(service, objectMapper, actionMapper, userMapper);
        ApprovalRequest request = request(16L, Map.of("operation", "confirm", "month", "2026-08"));
        request.setCurrentStep(1);
        when(actionMapper.selectList(any())).thenReturn(List.of(ApprovalAction.builder()
                .requestId(16L).stepNo(1).approverUserId(99L).action("APPROVE").build()));
        SysUser approver = SysUser.builder().role("管理者").build();
        approver.setId(99L);
        when(userMapper.selectById(99L)).thenReturn(approver);

        adapter.applyApproved(request);

        verify(service).confirmClosing("2026-08", 99L, "管理者");
    }

    @Test
    void registryは同一申請入力から決定的なidempotencyKeyを生成する() {
        ApprovalTargetAdapter adapter = mock(ApprovalTargetAdapter.class);
        ApprovalEngineService engine = mock(ApprovalEngineService.class);
        when(adapter.supportedRequestTypes()).thenReturn(java.util.Set.of("quotation.submit"));
        when(adapter.snapshot(any(), any())).thenReturn(new ApprovalSnapshot(1L, BigDecimal.TEN, null, Map.of(), Map.of()));
        when(engine.request(any())).thenReturn(new ApprovalRequest());
        ApprovalTargetAdapterRegistry registry = new ApprovalTargetAdapterRegistry(List.of(adapter), engine, objectMapper);
        Map<String, Object> command = Map.of("operation", "status", "status", "提出済");

        registry.request("quotation.submit", "QUOTATION", 17L, command);
        registry.request("quotation.submit", "QUOTATION", 17L, command);

        ArgumentCaptor<ApprovalRequestCommand> captor = ArgumentCaptor.forClass(ApprovalRequestCommand.class);
        verify(engine, org.mockito.Mockito.times(2)).request(captor.capture());
        assertNotNull(captor.getAllValues().get(0).idempotencyKey());
        assertEquals(captor.getAllValues().get(0).idempotencyKey(), captor.getAllValues().get(1).idempotencyKey());
    }

    private ApprovalRequest request(Long targetId, Map<String, Object> payload) throws Exception {
        ApprovalRequest request = new ApprovalRequest();
        request.setId(targetId);
        request.setTargetId(targetId);
        request.setPayloadJson(objectMapper.writeValueAsString(payload));
        return request;
    }
}
