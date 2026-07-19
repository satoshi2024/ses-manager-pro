package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class QuotationServiceImplTest {

    @Mock
    private QuotationMapper quotationMapper;
    @Mock
    private CustomerMapper customerMapper;
    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private QuotationServiceImpl quotationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quotationService, "baseMapper", quotationMapper);
    }

    private Quotation valid() {
        Quotation q = new Quotation();
        q.setCustomerId(1L);
        q.setTitle("金融システム開発");
        q.setUnitPrice(new BigDecimal("700000"));
        return q;
    }

    // ===== 採番 =====

    @Test
    void generateQuotationNo_first() {
        when(quotationMapper.selectMaxQuotationNoIncludingDeleted(anyString())).thenReturn(null);
        assertEquals("Q-" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "-0001",
                quotationService.generateQuotationNo(LocalDate.now()));
    }

    @Test
    void generateQuotationNo_sequential() {
        String prefix = "Q-" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        when(quotationMapper.selectMaxQuotationNoIncludingDeleted(anyString())).thenReturn(prefix + "0007");
        assertEquals(prefix + "0008", quotationService.generateQuotationNo(LocalDate.now()));
    }

    @Test
    void saveWithBusinessRules_retriesOnDuplicate() {
        when(customerMapper.selectById(1L)).thenReturn(new Customer());
        when(quotationMapper.selectMaxQuotationNoIncludingDeleted(anyString())).thenReturn(null);
        when(quotationMapper.insert(any(Quotation.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"))
                .thenReturn(1);

        Quotation q = valid();
        quotationService.saveWithBusinessRules(q);
        assertEquals("下書き", q.getStatus());
        verify(quotationMapper, times(2)).insert(any(Quotation.class));
    }

    @Test
    void saveWithBusinessRules_failsAfterMaxRetries() {
        when(customerMapper.selectById(1L)).thenReturn(new Customer());
        when(quotationMapper.selectMaxQuotationNoIncludingDeleted(anyString())).thenReturn(null);
        when(quotationMapper.insert(any(Quotation.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveWithBusinessRules(valid()));
        assertTrue(ex.getMessage().contains("error.quotation.numberGenerateFailed"));
        verify(quotationMapper, times(3)).insert(any(Quotation.class));
    }

    // ===== 検証 =====

    @Test
    void save_customerNotFound() {
        when(customerMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveWithBusinessRules(valid()));
        assertTrue(ex.getMessage().contains("error.quotation.customerNotFound"));
    }

    @Test
    void save_projectCustomerMismatch() {
        when(customerMapper.selectById(1L)).thenReturn(new Customer());
        Project p = new Project();
        p.setCustomerId(999L);
        when(projectMapper.selectById(5L)).thenReturn(p);
        Quotation q = valid();
        q.setProjectId(5L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveWithBusinessRules(q));
        assertTrue(ex.getMessage().contains("error.quotation.projectCustomerMismatch"));
    }

    @Test
    void save_settlementRangeInvalid() {
        when(customerMapper.selectById(1L)).thenReturn(new Customer());
        Quotation q = valid();
        q.setSettlementHoursMin(new BigDecimal("180"));
        q.setSettlementHoursMax(new BigDecimal("140"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveWithBusinessRules(q));
        assertTrue(ex.getMessage().contains("error.quotation.settlementRangeInvalid"));
    }

    // ===== 状態機械 =====

    @Test
    void changeStatus_allowed() {
        Quotation q = new Quotation();
        q.setId(1L);
        q.setStatus("下書き");
        // R3R-25: 状態遷移は行ロック(selectByIdForUpdate)経由。
        when(quotationMapper.selectByIdForUpdate(1L)).thenReturn(q);
        when(quotationMapper.updateById(any(Quotation.class))).thenReturn(1);
        quotationService.changeStatus(1L, "提出済");
        verify(quotationMapper).updateById(any(Quotation.class));
    }

    @Test
    void changeStatus_invalid() {
        Quotation q = new Quotation();
        q.setId(1L);
        q.setStatus("下書き");
        when(quotationMapper.selectByIdForUpdate(1L)).thenReturn(q);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.changeStatus(1L, "受注"));
        assertTrue(ex.getMessage().contains("error.quotation.statusTransitionInvalid"));
    }

    @Test
    void update_afterClosedRejected() {
        // R3R-24: 受注/失注後は通常updateを拒否する（備考追記は専用APIへ）。
        Quotation current = new Quotation();
        current.setId(1L);
        current.setStatus("受注");
        when(quotationMapper.selectByIdForUpdate(1L)).thenReturn(current);

        Quotation edit = valid();
        edit.setId(1L);
        edit.setRemarks("追記");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.updateWithBusinessRules(edit));
        assertTrue(ex.getMessage().contains("error.quotation.terminalUpdate"));
        verify(quotationMapper, org.mockito.Mockito.never()).updateById(any(Quotation.class));
    }

    @Test
    void appendRemark_locksAndAppends() {
        // R3R-24: 行ロック内で最新値へ追記する。
        Quotation current = new Quotation();
        current.setId(1L);
        current.setStatus("受注");
        current.setRemarks("既存");
        when(quotationMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(quotationMapper.updateById(any(Quotation.class))).thenReturn(1);

        quotationService.appendRemark(1L, "追記");

        org.mockito.ArgumentCaptor<Quotation> cap = org.mockito.ArgumentCaptor.forClass(Quotation.class);
        verify(quotationMapper).updateById(cap.capture());
        assertEquals("既存\n追記", cap.getValue().getRemarks());
    }

    @Test
    void removeById_nonDraftRejected() {
        Quotation q = new Quotation();
        q.setId(1L);
        q.setStatus("提出済");
        when(quotationMapper.selectById(1L)).thenReturn(q);
        BusinessException ex = assertThrows(BusinessException.class, () -> quotationService.removeById(1L));
        assertTrue(ex.getMessage().contains("error.quotation.deleteNonDraft"));
    }
}
