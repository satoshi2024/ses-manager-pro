package com.ses.service.security;

import com.ses.dto.security.OwnershipRepairRequest;
import com.ses.entity.OwnershipRepairQueue;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OwnershipRepairQueueMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.impl.OwnershipRepairServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnershipRepairServiceImplTest {
    @Mock private OwnershipRepairQueueMapper queueMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private BpAvailabilityMapper bpAvailabilityMapper;
    @Mock private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private RepairAuthorityResolver authorityResolver;
    @InjectMocks private OwnershipRepairServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        when(authorityResolver.requireAuthority()).thenReturn(
                new RepairAuthorityResolver.RepairAuthority("tenant-a", 77L, 10L, 11L));
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void resolveはclaimと構造化evidenceとCASを必須にする() {
        OwnershipRepairQueue row = new OwnershipRepairQueue();
        row.setId(1L);
        row.setEntityType("CUSTOMER");
        row.setEntityId(10L);
        row.setStatus("CLAIMED");
        row.setVersion(2);
        row.setClaimToken("claim");
        row.setConflictingTenantId("tenant-a");
        when(queueMapper.selectForUpdate(1L, "tenant-a")).thenReturn(row);
        OwnershipRepairRequest request = new OwnershipRepairRequest();
        request.setExpectedVersion(2);
        request.setClaimToken("claim");
        request.setReason("台帳照合");
        OwnershipRepairRequest.Evidence evidence = new OwnershipRepairRequest.Evidence();
        evidence.setSourceType("LEDGER");
        evidence.setSourceReference("ticket-123");
        evidence.setStatement("契約台帳と管理者承認を照合した");
        request.setEvidence(evidence);
        when(customerMapper.assignTenantForRepair(10L, "tenant-a")).thenReturn(1);
        when(queueMapper.markResolvedCas(eq(1L), eq("tenant-a"), eq("台帳照合"), anyString(),
                any(), eq(10L), eq(77L), eq("tenant-a"), anyString(), eq(11L), eq("claim"), eq(2)))
                .thenReturn(1);

        service.resolve(1L, request);

        verify(customerMapper).assignTenantForRepair(10L, "tenant-a");
        verify(queueMapper).markResolvedCas(eq(1L), eq("tenant-a"), eq("台帳照合"), anyString(),
                any(), eq(10L), eq(77L), eq("tenant-a"), anyString(), eq(11L), eq("claim"), eq(2));
    }

    @Test
    void authorityが無ければ業務mapperを呼ばない() {
        when(authorityResolver.requireAuthority()).thenThrow(new RuntimeException("denied"));
        assertThrows(RuntimeException.class, service::listPending);
        assertThrows(RuntimeException.class, service::summary);
        verifyNoInteractions(queueMapper);
    }

    @Test
    void assignはversionCASでclaimする() {
        when(sysUserMapper.selectByIdAndTenant(99L, "tenant-a")).thenReturn(new com.ses.entity.SysUser());
        OwnershipRepairQueue row = new OwnershipRepairQueue();
        row.setStatus("PENDING");
        row.setVersion(0);
        row.setConflictingTenantId("tenant-a");
        when(queueMapper.selectForUpdate(1L, "tenant-a")).thenReturn(row);
        when(queueMapper.claim(eq(1L), eq(99L), anyString(), any(), eq(0), eq(77L), eq("tenant-a")))
                .thenReturn(1);
        service.assign(1L, 99L, 0);
        verify(queueMapper).claim(eq(1L), eq(99L), anyString(), any(), eq(0), eq(77L), eq("tenant-a"));
    }

    @Test
    void evidenceが構造化されていなければ業務更新を行わない() {
        OwnershipRepairQueue row = new OwnershipRepairQueue();
        row.setStatus("CLAIMED");
        row.setVersion(1);
        row.setClaimToken("claim");
        row.setConflictingTenantId("tenant-a");
        when(queueMapper.selectForUpdate(1L, "tenant-a")).thenReturn(row);

        OwnershipRepairRequest request = new OwnershipRepairRequest();
        request.setExpectedVersion(1);
        request.setClaimToken("claim");
        request.setReason("確認");
        request.setEvidence(new OwnershipRepairRequest.Evidence());

        com.ses.common.exception.BusinessException ex = assertThrows(
                com.ses.common.exception.BusinessException.class, () -> service.resolve(1L, request));
        assertEquals(400, ex.getCode());
        verifyNoInteractions(customerMapper, engineerMapper, bpAvailabilityMapper, engineerAccountLinkMapper);
        verify(queueMapper, never()).markResolvedCas(anyLong(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void 他tenantの修復行はclaimもresolveもできない() {
        when(sysUserMapper.selectByIdAndTenant(99L, "tenant-a")).thenReturn(new com.ses.entity.SysUser());
        when(queueMapper.selectForUpdate(9L, "tenant-a")).thenReturn(null);

        com.ses.common.exception.BusinessException ex = assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> service.assign(9L, 99L, 0));
        assertEquals(404, ex.getCode());
        verify(queueMapper, never()).claim(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong(), anyString());
    }
}
