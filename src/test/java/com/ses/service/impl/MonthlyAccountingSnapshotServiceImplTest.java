package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.UserOrganization;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 月次締めsnapshotが所属異動で書き換わらないことを検証する。 */
@ExtendWith(MockitoExtension.class)
class MonthlyAccountingSnapshotServiceImplTest {

    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private CostCenterMapper costCenterMapper;
    @Mock private MonthlyAccountingDimensionMapper dimensionMapper;
    @InjectMocks private MonthlyAccountingSnapshotServiceImpl service;

    @Test
    void snapshotMonth_usesAssignmentAtMonthAndDoesNotOverwriteExistingSnapshot() {
        WorkRecord record = new WorkRecord();
        record.setId(501L);
        record.setContractId(601L);
        record.setWorkMonth("2026-06");
        record.setStatus("確定");
        record.setBillingAmount(new BigDecimal("100000"));
        record.setPaymentAmount(new BigDecimal("70000"));
        Contract contract = new Contract();
        contract.setEngineerId(701L);
        contract.setSalesUserId(801L);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setSysUserId(901L);
        UserOrganization beforeTransfer = new UserOrganization();
        beforeTransfer.setOrganizationId(1001L);
        beforeTransfer.setPrimaryFlag(1);
        beforeTransfer.setValidFrom(LocalDate.of(2026, 1, 1));
        MonthlyAccountingDimension existing = MonthlyAccountingDimension.builder()
                .workMonth(LocalDate.of(2026, 6, 1)).sourceType("work-record").sourceId(501L)
                .organizationId(1001L).build();

        when(workRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of(existing));
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(null);
        when(engineerAccountLinkMapper.selectByEngineerId(701L)).thenReturn(link);
        when(userOrganizationMapper.selectList(any())).thenReturn(null).thenReturn(null);
        when(userOrganizationMapper.selectOne(any())).thenReturn(beforeTransfer);
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));
        assertEquals(0, service.snapshotMonth("2026-06"));
        verify(dimensionMapper).insert(any(MonthlyAccountingDimension.class));
        verify(userOrganizationMapper, times(2)).selectOne(any());
    }

    /**
     * アカウント連携は要員セルフサービスを使う要員にしか存在しない。連携を必須にすると
     * 大半の実績が組織NULL＝未配賦になり、部門損益が成立しない。要員自身の所属組織を正とする。
     */
    @Test
    void 過去月で直接所属履歴が無い要員は現在組織へ推測付替しない() {
        WorkRecord record = new WorkRecord();
        record.setId(502L);
        record.setContractId(602L);
        record.setWorkMonth("2026-06");
        record.setStatus("確定");
        record.setBillingAmount(new BigDecimal("200000"));
        record.setPaymentAmount(new BigDecimal("120000"));
        Contract contract = new Contract();
        contract.setId(602L);
        contract.setEngineerId(702L);
        com.ses.entity.Engineer engineer = new com.ses.entity.Engineer();
        engineer.setId(702L);
        engineer.setOrganizationId(2002L);

        when(workRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerMapper.selectBatchIds(anyList())).thenReturn(List.of(engineer));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(List.of());
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));

        org.mockito.ArgumentCaptor<MonthlyAccountingDimension> captor =
                org.mockito.ArgumentCaptor.forClass(MonthlyAccountingDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getOrganizationId());
        // 連携ユーザーの所属を引きに行かない（連携が無いので参照しても意味がない）。
        verify(userOrganizationMapper, never()).selectOne(any());
    }

    @Test
    void snapshotMonth_usesFrozenWorkRecordAtConfirmationInsteadOfCurrentEngineerMaster() {
        WorkRecord record = new WorkRecord();
        record.setId(503L);
        record.setContractId(603L);
        record.setWorkMonth("2026-06");
        record.setStatus("確定");
        record.setOrganizationId(3003L);
        record.setCostCenterId(4004L);
        record.setBillingAmount(new BigDecimal("100000"));
        record.setPaymentAmount(new BigDecimal("70000"));

        Contract contract = new Contract();
        contract.setId(603L);
        contract.setEngineerId(703L);
        contract.setCostCenterId(9999L); // 7月の契約変更後の値
        com.ses.entity.Engineer movedEngineer = new com.ses.entity.Engineer();
        movedEngineer.setId(703L);
        movedEngineer.setOrganizationId(9009L); // 7月異動後の組織
        movedEngineer.setCostCenterId(9999L);
        com.ses.entity.CostCenter frozenCenter = new com.ses.entity.CostCenter();
        frozenCenter.setId(4004L);
        frozenCenter.setOrganizationId(3003L);

        when(workRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerMapper.selectBatchIds(anyList())).thenReturn(List.of(movedEngineer));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(List.of());
        when(costCenterMapper.selectById(4004L)).thenReturn(frozenCenter);
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));

        org.mockito.ArgumentCaptor<MonthlyAccountingDimension> captor =
                org.mockito.ArgumentCaptor.forClass(MonthlyAccountingDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        assertEquals(3003L, captor.getValue().getOrganizationId());
        assertEquals(4004L, captor.getValue().getCostCenterId());
        verify(userOrganizationMapper, never()).selectOne(any());
    }

    @Test
    void snapshotMonth_待機要員も要員単位でsnapshotする() {
        com.ses.dto.accounting.AccountingWaitCostSnapshotRow wait =
                new com.ses.dto.accounting.AccountingWaitCostSnapshotRow();
        wait.setEngineerId(901L);
        wait.setOrganizationId(1001L);
        wait.setCostCenterId(1002L);
        wait.setWaitCost(new BigDecimal("350000"));

        when(workRecordMapper.selectList(any())).thenReturn(List.of());
        when(engineerMapper.selectAccountingWaitCostByEngineer(any(), any())).thenReturn(List.of(wait));
        when(costCenterMapper.selectById(1002L)).thenReturn(null);
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));

        org.mockito.ArgumentCaptor<MonthlyAccountingDimension> captor =
                org.mockito.ArgumentCaptor.forClass(MonthlyAccountingDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        assertEquals("bench-engineer", captor.getValue().getSourceType());
        assertEquals(901L, captor.getValue().getSourceId());
        assertEquals(1001L, captor.getValue().getOrganizationId());
        assertEquals(new BigDecimal("350000"), captor.getValue().getCost());
    }

    @Test
    void 明示的に凍結したNULLは後日の要員マスタで補完しない() {
        WorkRecord record = new WorkRecord();
        record.setId(504L);
        record.setContractId(604L);
        record.setWorkMonth("2026-06");
        record.setStatus("確定");
        record.setAccountingDimensionFrozen(1);
        record.setBillingAmount(new BigDecimal("100000"));
        record.setPaymentAmount(new BigDecimal("70000"));

        Contract contract = new Contract();
        contract.setId(604L);
        contract.setEngineerId(704L);
        contract.setCostCenterId(4004L);
        com.ses.entity.Engineer movedEngineer = new com.ses.entity.Engineer();
        movedEngineer.setId(704L);
        movedEngineer.setOrganizationId(9009L);
        movedEngineer.setCostCenterId(9999L);

        when(workRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerMapper.selectBatchIds(anyList())).thenReturn(List.of(movedEngineer));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(List.of());
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));

        org.mockito.ArgumentCaptor<MonthlyAccountingDimension> captor =
                org.mockito.ArgumentCaptor.forClass(MonthlyAccountingDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        assertEquals("work-record", captor.getValue().getSourceType());
        assertEquals(null, captor.getValue().getOrganizationId());
        assertEquals(null, captor.getValue().getCostCenterId());
    }

    @Test
    void invalidMonth_isRejectedBeforeReadingRecords() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.snapshotMonth("2026/06"));
        verify(workRecordMapper, never()).selectList(any());
    }
}
