package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import com.ses.service.ContractRenewalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 契約自動更新ドラフト生成サービスの単体テスト（P8フォローアップ・提案14）。
 * 対象条件の絞り込み・重複生成防止・ドラフト内容の引き継ぎを検証する。
 */
class ContractRenewalServiceImplTest {

    private ContractMapper contractMapper;
    private ContractService contractService;
    private SystemConfigService systemConfigService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<NotificationService> notificationServiceProvider = mock(ObjectProvider.class);
    private com.ses.service.EngineerSalesService engineerSalesService;
    private ApplicationContext applicationContext;
    private ContractRenewalServiceImpl service;

    @BeforeEach
    void setUp() {
        contractMapper = mock(ContractMapper.class);
        contractService = mock(ContractService.class);
        systemConfigService = mock(SystemConfigService.class);
        engineerSalesService = mock(com.ses.service.EngineerSalesService.class);
        applicationContext = mock(ApplicationContext.class);
        service = new ContractRenewalServiceImpl(contractMapper, contractService, systemConfigService, notificationServiceProvider, engineerSalesService, applicationContext);
        when(applicationContext.getBean(ContractRenewalService.class)).thenReturn(service);
    }

    private Contract sourceContract(Long id, LocalDate endDate) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractNo("C-202607-000" + id);
        c.setEngineerId(10L);
        c.setProjectId(20L);
        c.setCustomerId(30L);
        c.setContractType("準委任");
        c.setStartDate(endDate.minusMonths(6));
        c.setEndDate(endDate);
        c.setSellingPrice(new BigDecimal("80"));
        c.setCostPrice(new BigDecimal("60"));
        c.setAutoRenew(1);
        c.setStatus("稼動中");
        return c;
    }

    @Test
    void generateRenewalDrafts_未生成なら新規ドラフトを作成する() {
        when(systemConfigService.getInt("notice.contract-end-days", 30)).thenReturn(30);
        Contract original = sourceContract(1L, LocalDate.now().plusDays(10));
        when(contractMapper.selectList(any())).thenReturn(List.of(original));
        when(contractMapper.selectCount(any())).thenReturn(0L);

        int created = service.generateRenewalDrafts();

        assertEquals(1, created);
        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractService, times(1)).saveWithBusinessRules(captor.capture());
        Contract draft = captor.getValue();
        assertEquals(original.getEngineerId(), draft.getEngineerId());
        assertEquals(original.getProjectId(), draft.getProjectId());
        assertEquals(original.getEndDate().plusDays(1), draft.getStartDate());
        assertEquals("準備中", draft.getStatus());
        assertEquals(original.getId(), draft.getRenewedFromContractId());
    }

    @Test
    void generateRenewalDrafts_既にドラフトがあれば重複生成しない() {
        when(systemConfigService.getInt("notice.contract-end-days", 30)).thenReturn(30);
        Contract original = sourceContract(1L, LocalDate.now().plusDays(10));
        when(contractMapper.selectList(any())).thenReturn(List.of(original));
        when(contractMapper.countRenewedDraftsIncludingDeleted(1L)).thenReturn(1); // 既にドラフト有り

        int created = service.generateRenewalDrafts();

        assertEquals(0, created);
        verify(contractService, never()).saveWithBusinessRules(any());
    }

    @Test
    void generateRenewalDrafts_対象が無ければ何もしない() {
        when(systemConfigService.getInt("notice.contract-end-days", 30)).thenReturn(30);
        when(contractMapper.selectList(any())).thenReturn(List.of());

        int created = service.generateRenewalDrafts();

        assertEquals(0, created);
        verify(contractService, never()).saveWithBusinessRules(any());
    }
}
