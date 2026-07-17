package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
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
    private EngineerSalesService engineerSalesService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SysUserMapper sysUserMapper;

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
        assertEquals("error.contract.endDateInvalid", exception.getMessage());
    }

    @Test
    void saveWithBusinessRules_validateSettlementHoursError() {
        Contract contract = new Contract();
        contract.setSettlementHoursMin(new BigDecimal("160"));
        contract.setSettlementHoursMax(new BigDecimal("140"));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            contractService.saveWithBusinessRules(contract);
        });
        assertEquals("error.contract.unitPriceInvalid", exception.getMessage());
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
    void saveWithBusinessRules_defaultsBlankContractTypeToQuasiMandate() {
        Contract contract = new Contract();
        contract.setContractNo("MANUAL-001");
        contract.setContractType(" ");
        when(contractMapper.insert(contract)).thenReturn(1);

        contractService.saveWithBusinessRules(contract);

        assertEquals("準委任", contract.getContractType());
        verify(contractMapper, times(1)).insert(contract);
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
    void saveWithBusinessRules_rejectsProjectCustomerMismatch() {
        Contract contract = new Contract();
        contract.setProjectId(10L);
        contract.setCustomerId(20L);
        Project project = new Project();
        project.setCustomerId(21L);
        when(projectMapper.selectById(10L)).thenReturn(project);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.saveWithBusinessRules(contract));

        assertEquals("error.contract.projectCustomerMismatch", ex.getMessage());
        verify(contractMapper, never()).insert(any(Contract.class));
    }

    @Test
    void saveWithBusinessRules_rejectsInactiveSalesUser() {
        Contract contract = new Contract();
        contract.setSalesUserId(30L);
        SysUser salesUser = new SysUser();
        salesUser.setRole("営業");
        salesUser.setStatus(0);
        when(sysUserMapper.selectById(30L)).thenReturn(salesUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.saveWithBusinessRules(contract));

        assertEquals("error.contract.salesUserInvalid", ex.getMessage());
        verify(contractMapper, never()).insert(any(Contract.class));
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
        oldContract.setEngineerId(200L);

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
    void updateWithBusinessRules_activeContractEngineerChangeRecalculatesBothEngineers() {
        Contract oldContract = new Contract();
        oldContract.setId(1L);
        oldContract.setStatus("稼動中");
        oldContract.setEngineerId(100L);

        Contract newContract = new Contract();
        newContract.setId(1L);
        newContract.setStatus("稼動中");
        newContract.setEngineerId(200L);

        when(contractMapper.selectById(1L)).thenReturn(oldContract);
        when(contractMapper.updateById(newContract)).thenReturn(1);

        contractService.updateWithBusinessRules(newContract);

        verify(engineerStatusService).releaseIfIdle(100L);
        verify(engineerStatusService).onContractActive(200L);
    }

    @Test
    void updateWithBusinessRules_engineerChangeAndEndReleasesOldEngineerOnly() {
        Contract oldContract = new Contract();
        oldContract.setId(1L);
        oldContract.setStatus("稼動中");
        oldContract.setEngineerId(100L);

        Contract newContract = new Contract();
        newContract.setId(1L);
        newContract.setStatus("終了");
        newContract.setEngineerId(200L);

        when(contractMapper.selectById(1L)).thenReturn(oldContract);
        when(contractMapper.updateById(newContract)).thenReturn(1);

        contractService.updateWithBusinessRules(newContract);

        verify(engineerStatusService).releaseIfIdle(100L);
        verify(engineerStatusService, never()).releaseIfIdle(200L);
        verify(engineerStatusService, never()).onContractActive(any());
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
    void updateWithBusinessRules_退職済み担当のまま更新できる() {
        // 既存契約の担当営業(退職済み)を変更しない更新は、在職チェックを免除して通す。
        Contract old = new Contract();
        old.setId(1L);
        old.setStatus("稼動中");
        old.setEngineerId(100L);
        old.setSalesUserId(99L);

        Contract update = new Contract();
        update.setId(1L);
        update.setStatus("稼動中");
        update.setEngineerId(100L);
        update.setSalesUserId(99L); // 同一(退職済みだが変更なし)

        when(contractMapper.selectById(1L)).thenReturn(old);
        when(contractMapper.updateById(update)).thenReturn(1);

        contractService.updateWithBusinessRules(update);

        // 在職チェック(sysUserMapper参照)は行われない
        verify(sysUserMapper, never()).selectById(any());
        verify(contractMapper, times(1)).updateById(update);
    }

    @Test
    void updateWithBusinessRules_退職済み担当への変更は拒否される() {
        Contract old = new Contract();
        old.setId(1L);
        old.setStatus("稼動中");
        old.setSalesUserId(null); // 元は未設定

        Contract update = new Contract();
        update.setId(1L);
        update.setStatus("稼動中");
        update.setSalesUserId(99L); // 退職済み営業へ変更

        SysUser inactive = new SysUser();
        inactive.setRole("営業");
        inactive.setStatus(0); // 退職(無効)
        when(contractMapper.selectById(1L)).thenReturn(old);
        when(sysUserMapper.selectById(99L)).thenReturn(inactive);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.updateWithBusinessRules(update));
        assertEquals("error.contract.salesUserInvalid", ex.getMessage());
        verify(contractMapper, never()).updateById(any(Contract.class));
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

    // ===== R2: 解約日つき状態遷移 =====

    private Contract activeContract(Long id, LocalDate startDate, LocalDate endDate) {
        Contract c = new Contract();
        c.setId(id);
        c.setStatus("稼動中");
        c.setStartDate(startDate);
        c.setEndDate(endDate);
        c.setEngineerId(500L);
        return c;
    }

    @Test
    void changeStatus_cancelRequiresCancelDate() {
        Contract c = activeContract(1L, LocalDate.of(2026, 4, 1), null);
        when(contractMapper.selectById(1L)).thenReturn(c);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.changeStatus(1L, "解約", null));
        assertEquals("error.contract.cancelDateRequired", ex.getMessage());
        verify(contractMapper, never()).updateById(any(Contract.class));
    }

    @Test
    void changeStatus_cancelDateBeforeStartRejected() {
        Contract c = activeContract(1L, LocalDate.of(2026, 4, 1), null);
        when(contractMapper.selectById(1L)).thenReturn(c);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.changeStatus(1L, "解約", LocalDate.of(2026, 3, 31)));
        assertEquals("error.contract.cancelDateInvalid", ex.getMessage());
        verify(contractMapper, never()).updateById(any(Contract.class));
    }

    @Test
    void changeStatus_cancelOverwritesEndDate() {
        Contract c = activeContract(1L, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 31));
        when(contractMapper.selectById(1L)).thenReturn(c);
        when(contractMapper.updateById(any(Contract.class))).thenReturn(1);

        contractService.changeStatus(1L, "解約", LocalDate.of(2026, 7, 15));

        assertEquals(LocalDate.of(2026, 7, 15), c.getEndDate());
        assertEquals("解約", c.getStatus());
        verify(engineerStatusService, times(1)).releaseIfIdle(500L);
    }

    @Test
    void changeStatus_cancelDateAfterExistingEndDateRejected() {
        Contract c = activeContract(1L, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 31));
        when(contractMapper.selectById(1L)).thenReturn(c);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contractService.changeStatus(1L, "解約", LocalDate.of(2026, 9, 30)));
        assertEquals("error.contract.cancelDateAfterEnd", ex.getMessage());
        verify(contractMapper, never()).updateById(any(Contract.class));
    }

    @Test
    void changeStatus_endDoesNotChangeEndDate() {
        Contract c = activeContract(1L, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 31));
        when(contractMapper.selectById(1L)).thenReturn(c);
        when(contractMapper.updateById(any(Contract.class))).thenReturn(1);

        // 終了遷移では cancelDate を渡しても end_date は不変(自然満了)
        contractService.changeStatus(1L, "終了", LocalDate.of(2026, 7, 15));

        assertEquals(LocalDate.of(2026, 12, 31), c.getEndDate());
        assertEquals("終了", c.getStatus());
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
        when(engineerSalesService.findPrimarySalesUserId(2L)).thenReturn(null);
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
        assertTrue(ex.getMessage().contains("error.contract.proposalProjectNotFound"));
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
        when(engineerSalesService.findPrimarySalesUserId(2L)).thenReturn(null);

        Contract draft = contractService.createDraftFromProposal(p);

        assertEquals(0, BigDecimal.ZERO.compareTo(draft.getSellingPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(draft.getCostPrice()));
        assertNull(draft.getSalesUserId());
    }

    @Test
    void createDraftFromProposal_setsPrimarySalesUserIdIfPresent() {
        Proposal p = proposal(51L, 3L, 10L, new BigDecimal("700000"));
        when(contractMapper.selectOne(any())).thenReturn(null);
        Project prj = new Project();
        prj.setCustomerId(5L);
        when(projectMapper.selectById(10L)).thenReturn(prj);
        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString())).thenReturn(null);
        when(contractMapper.insert(any(Contract.class))).thenReturn(1);
        when(engineerSalesService.findPrimarySalesUserId(3L)).thenReturn(99L);
        when(engineerSalesService.isActiveSalesUser(99L)).thenReturn(true);

        Contract draft = contractService.createDraftFromProposal(p);

        assertEquals(99L, draft.getSalesUserId());
    }

    @Test
    void createDraftFromProposal_退職主担当なら未帰属でドラフト生成する() {
        // S1-1: 主担当が退職済み(isActiveSalesUser=false)なら sales_user_id=NULL でドラフト生成し、
        // validate に落として成約ごとロールバックさせない。
        Proposal p = proposal(52L, 7L, 11L, new BigDecimal("650000"));
        when(contractMapper.selectOne(any())).thenReturn(null);
        Project prj = new Project();
        prj.setCustomerId(6L);
        when(projectMapper.selectById(11L)).thenReturn(prj);
        when(contractMapper.selectMaxContractNoIncludingDeleted(anyString())).thenReturn(null);
        when(contractMapper.insert(any(Contract.class))).thenReturn(1);
        when(engineerSalesService.findPrimarySalesUserId(7L)).thenReturn(88L);
        when(engineerSalesService.isActiveSalesUser(88L)).thenReturn(false); // 退職済み

        Contract draft = contractService.createDraftFromProposal(p);

        assertNull(draft.getSalesUserId(), "退職主担当は引き継がず未帰属になること");
        assertEquals("準備中", draft.getStatus());
    }
}
