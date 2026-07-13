package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.EngineerStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContractServiceImplTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerStatusService engineerStatusService;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ContractServiceImpl contractService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        org.springframework.test.util.ReflectionTestUtils.setField(contractService, "baseMapper", contractMapper);
    }

    @Test
    void generateContractNo_success() {
        LocalDate baseDate = LocalDate.of(2026, 7, 1);
        when(contractMapper.selectMaxContractNoIncludingDeleted("C-202607-")).thenReturn("C-202607-0005");

        String contractNo = contractService.generateContractNo(baseDate);

        assertEquals("C-202607-0006", contractNo);
    }

    @Test
    void saveWithBusinessRules_validateDateError() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 10));
        contract.setEndDate(LocalDate.of(2026, 7, 5));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            contractService.saveWithBusinessRules(contract);
        });
        assertEquals("契約終了日は開始日以降の日付を指定してください", exception.getMessage());
    }

    @Test
    void saveWithBusinessRules_validateSettlementHoursError() {
        Contract contract = new Contract();
        contract.setSettlementHoursMin(new BigDecimal("160"));
        contract.setSettlementHoursMax(new BigDecimal("140"));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            contractService.saveWithBusinessRules(contract);
        });
        assertEquals("精算上限は下限以上の値を指定してください", exception.getMessage());
    }

    @Test
    void saveWithBusinessRules_successWithAutoNumbering() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        contract.setStatus("稼動中");
        contract.setEngineerId(100L);

        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString())).thenReturn(null);
        when(contractMapper.insert(contract)).thenReturn(1);

        contractService.saveWithBusinessRules(contract);

        assertEquals("C-202607-0001", contract.getContractNo());
        verify(contractMapper, times(1)).insert(contract);
        verify(engineerStatusService, times(1)).onContractActive(100L);
    }

    @Test
    void saveWithBusinessRules_retryOnDuplicateKeyException() {
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 7, 1));
        
        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString()))
            .thenReturn("C-202607-0001")
            .thenReturn("C-202607-0002");
        when(contractMapper.insert(contract))
            .thenThrow(new DuplicateKeyException("Duplicate"))
            .thenReturn(1);

        contractService.saveWithBusinessRules(contract);

        assertEquals("C-202607-0003", contract.getContractNo());
        verify(contractMapper, times(2)).insert(contract);
    }

    @Test
    void generateContractNo_successAfterLogicalDelete() {
        // (a) C-YYYYMM-0002 を論理削除後の新規作成が一発成功し 0003 が振られる
        LocalDate baseDate = LocalDate.of(2026, 7, 1);
        when(contractMapper.selectMaxContractNoIncludingDeleted("C-202607-")).thenReturn("C-202607-0002");

        String contractNo = contractService.generateContractNo(baseDate);
        assertEquals("C-202607-0003", contractNo);
    }

    @Test
    void generateContractNo_successAfter4LogicalDeletes() {
        // (b) 同月4件削除後でも新規作成が成功する
        LocalDate baseDate = LocalDate.of(2026, 7, 1);
        when(contractMapper.selectMaxContractNoIncludingDeleted("C-202607-")).thenReturn("C-202607-0004");

        String contractNo = contractService.generateContractNo(baseDate);
        assertEquals("C-202607-0005", contractNo);
    }

    @Test
    void generateContractNo_successWhenZeroContracts() {
        // (c) 当月契約ゼロなら 0001
        LocalDate baseDate = LocalDate.of(2026, 7, 1);
        when(contractMapper.selectMaxContractNoIncludingDeleted("C-202607-")).thenReturn(null);

        String contractNo = contractService.generateContractNo(baseDate);
        assertEquals("C-202607-0001", contractNo);
    }

    @Test
    void updateWithBusinessRules_releaseIfIdleWhenStatusEnds() {
        Contract oldContract = new Contract();
        oldContract.setId(1L);
        oldContract.setStatus("稼動中");

        Contract newContract = new Contract();
        newContract.setId(1L);
        newContract.setStatus("終了");
        newContract.setEngineerId(200L);

        when(contractMapper.selectById(1L)).thenReturn(oldContract);
        when(contractMapper.updateById(newContract)).thenReturn(1);

        contractService.updateWithBusinessRules(newContract);

        verify(engineerStatusService, times(1)).releaseIfIdle(200L);
    }

    @Test
    void updateWithBusinessRules_activatesEngineerWhenStatusBecomesActive() {
        Contract oldContract = new Contract();
        oldContract.setId(1L);
        oldContract.setStatus("準備中");

        Contract newContract = new Contract();
        newContract.setId(1L);
        newContract.setStatus("稼動中");
        newContract.setEngineerId(300L);

        when(contractMapper.selectById(1L)).thenReturn(oldContract);
        when(contractMapper.updateById(newContract)).thenReturn(1);

        contractService.updateWithBusinessRules(newContract);

        // 更新経由の 準備中→稼動中 でも要員を稼動中に連動させること
        verify(engineerStatusService, times(1)).onContractActive(300L);
    }

    @Test
    void updateWithBusinessRules_notFoundThrowsBusinessException() {
        Contract newContract = new Contract();
        newContract.setId(999L);
        newContract.setStatus("稼動中");

        when(contractMapper.selectById(999L)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> contractService.updateWithBusinessRules(newContract));
    }

    // ===== WS-G: 成約→契約ドラフト自動生成 =====

    private Proposal proposal(Long id, Long engineerId, Long projectId, BigDecimal unitPrice) {
        Proposal p = new Proposal();
        p.setId(id);
        p.setEngineerId(engineerId);
        p.setProjectId(projectId);
        p.setProposedUnitPrice(unitPrice);
        return p;
    }

    @Test
    void createDraftFromProposal_generatesDraftInPreparingStatus() {
        Proposal p = proposal(50L, 2L, 9L, new BigDecimal("650000"));
        when(contractMapper.selectOne(any())).thenReturn(null);
        Project prj = new Project();
        prj.setId(9L);
        prj.setCustomerId(4L);
        when(projectMapper.selectById(9L)).thenReturn(prj);
        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString())).thenReturn(null);
        when(contractMapper.insert(any(Contract.class))).thenReturn(1);

        Contract draft = contractService.createDraftFromProposal(p);

        assertEquals(50L, draft.getProposalId());
        assertEquals(2L, draft.getEngineerId());
        assertEquals(9L, draft.getProjectId());
        assertEquals(4L, draft.getCustomerId());
        assertEquals("準備中", draft.getStatus());
        assertEquals(0, new BigDecimal("650000").compareTo(draft.getSellingPrice()));
        assertNotNull(draft.getContractNo());
        // 準備中のため要員ステータス連動は発火しない
        verify(engineerStatusService, never()).onContractActive(any());
    }

    @Test
    void createDraftFromProposal_isIdempotent() {
        Proposal p = proposal(50L, 2L, 9L, null);
        Contract existing = new Contract();
        existing.setId(77L);
        existing.setProposalId(50L);
        when(contractMapper.selectOne(any())).thenReturn(existing);

        Contract result = contractService.createDraftFromProposal(p);

        assertEquals(77L, result.getId());
        verify(contractMapper, never()).insert(any(Contract.class));
    }

    @Test
    void createDraftFromProposal_projectMissingThrows() {
        Proposal p = proposal(50L, 2L, 9L, null);
        when(contractMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.createDraftFromProposal(p));
        assertTrue(ex.getMessage().contains("案件が見つかりません"));
    }

    @Test
    void createDraftFromProposal_nullUnitPriceDefaultsToZero() {
        Proposal p = proposal(50L, 2L, 9L, null);
        when(contractMapper.selectOne(any())).thenReturn(null);
        Project prj = new Project();
        prj.setCustomerId(4L);
        when(projectMapper.selectById(9L)).thenReturn(prj);
        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString())).thenReturn(null);
        when(contractMapper.insert(any(Contract.class))).thenReturn(1);

        Contract draft = contractService.createDraftFromProposal(p);

        assertEquals(0, BigDecimal.ZERO.compareTo(draft.getSellingPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(draft.getCostPrice()));
    }
}
