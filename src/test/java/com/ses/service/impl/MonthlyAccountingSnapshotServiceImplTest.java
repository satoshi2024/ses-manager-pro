package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(), List.of(existing));
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(null);
        when(engineerAccountLinkMapper.selectByEngineerId(701L)).thenReturn(link);
        when(userOrganizationMapper.selectList(any())).thenReturn(null, null);
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
    void アカウント連携が無くても要員の所属組織で帰属する() {
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

        when(workRecordMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(record));
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectBatchIds(anyList())).thenReturn(List.of(contract));
        when(engineerMapper.selectBatchIds(anyList())).thenReturn(List.of(engineer));
        when(engineerAccountLinkMapper.selectByEngineerIds(anyList())).thenReturn(List.of());
        when(dimensionMapper.insert(any(MonthlyAccountingDimension.class))).thenReturn(1);

        assertEquals(1, service.snapshotMonth("2026-06"));

        org.mockito.ArgumentCaptor<MonthlyAccountingDimension> captor =
                org.mockito.ArgumentCaptor.forClass(MonthlyAccountingDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        assertEquals(2002L, captor.getValue().getOrganizationId());
        // 連携ユーザーの所属を引きに行かない（連携が無いので参照しても意味がない）。
        verify(userOrganizationMapper, never()).selectOne(any());
    }

    @Test
    void invalidMonth_isRejectedBeforeReadingRecords() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.snapshotMonth("2026/06"));
        verify(workRecordMapper, never()).selectList(any(QueryWrapper.class));
    }
}
