package com.ses.service;

import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.entity.Invoice;
import com.ses.entity.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationGenerateServiceTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private ProposalMapper proposalMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SalesActivityMapper salesActivityMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private NotificationGenerateService notificationGenerateService;

    @Test
    void testGenerateAll() {
        notificationGenerateService.generateAll();
        // Just verify it doesn't crash for now or mock the DB calls if implemented.
    }

    @Test
    void testInvoiceOverdue_publishesForOverdueUnpaid() {
        Invoice inv = new Invoice();
        inv.setId(7L);
        inv.setInvoiceNo("INV-202605-0001");
        inv.setCustomerId(3L);
        inv.setStatus("送付済");
        inv.setDueDate(LocalDate.now().minusDays(5));
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        Customer c = new Customer();
        c.setCompanyName("顧客A");
        when(customerMapper.selectById(3L)).thenReturn(c);

        notificationGenerateService.invoiceOverdue();

        // メッセージに請求書番号・顧客名・超過日数(5日)を含み、dedupeKeyが所定形式であること
        verify(notificationService, times(1)).publish(
                eq("INVOICE_OVERDUE"),
                eq("支払期限超過"),
                contains("INV-202605-0001"),
                eq("/invoice"),
                contains("INVOICE_OVERDUE:7:"));
        verify(notificationService, times(1)).publish(
                any(), any(), contains("5日"), any(), any());
    }

    @Test
    void testInvoiceOverdue_noOverdueInvoices_publishesNothing() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        notificationGenerateService.invoiceOverdue();

        verify(notificationService, never()).publish(any(), any(), any(), any(), any());
    }

    // ===== S4: CONTRACT_END と更新ドラフトの連動 =====

    private com.ses.entity.Contract endingContract(Long id) {
        com.ses.entity.Contract c = new com.ses.entity.Contract();
        c.setId(id);
        c.setEngineerId(50L);
        c.setStatus("稼動中");
        c.setEndDate(LocalDate.now().plusDays(10));
        return c;
    }

    private com.ses.entity.Contract renewalDraftOf(Long originalId) {
        com.ses.entity.Contract d = new com.ses.entity.Contract();
        d.setId(999L);
        d.setRenewedFromContractId(originalId);
        return d;
    }

    @Test
    void testContractEnding_更新ドラフト生成済みは通知しない() {
        when(systemConfigService.getInt(eq("notice.contract-end-days"), any(Integer.class))).thenReturn(30);
        // 1回目: 終了間近の契約、2回目: 更新ドラフト(renewed_from=100)
        when(contractMapper.selectList(any()))
                .thenReturn(List.of(endingContract(100L)))
                .thenReturn(List.of(renewalDraftOf(100L)));

        notificationGenerateService.contractEnding();

        verify(notificationService, never()).publish(eq("CONTRACT_END"), any(), any(), any(), any());
    }

    @Test
    void testContractEnding_ドラフト未生成は通知する() {
        when(systemConfigService.getInt(eq("notice.contract-end-days"), any(Integer.class))).thenReturn(30);
        // 1回目: 終了間近の契約、2回目: ドラフトなし
        when(contractMapper.selectList(any()))
                .thenReturn(List.of(endingContract(100L)))
                .thenReturn(List.of());

        notificationGenerateService.contractEnding();

        verify(notificationService, times(1)).publish(
                eq("CONTRACT_END"), any(), any(), eq("/contract/list"), contains("CONTRACT_END:100:"));
    }
}
