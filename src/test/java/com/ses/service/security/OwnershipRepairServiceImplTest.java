package com.ses.service.security;

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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** unresolved ownershipの修復が明示tenant・根拠・管理者監査を要求することを検証する。 */
@ExtendWith(MockitoExtension.class)
class OwnershipRepairServiceImplTest {

    @Mock private OwnershipRepairQueueMapper queueMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private BpAvailabilityMapper bpAvailabilityMapper;
    @Mock private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private SysUserMapper sysUserMapper;
    @InjectMocks private OwnershipRepairServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "repair-admin", "N/A", "ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void resolveはtenant根拠証跡がない場合に拒否する() {
        assertThrows(RuntimeException.class,
                () -> service.resolve(1L, "tenant-b", "運用確認", "ticket-1"));
        assertThrows(RuntimeException.class,
                () -> service.resolve(1L, "tenant-a", "", "ticket-1"));
        verify(queueMapper, never()).selectById(any());
    }

    @Test
    void customerの修復は明示tenantと監査情報を同時に保存する() {
        OwnershipRepairQueue row = new OwnershipRepairQueue();
        row.setId(1L);
        row.setEntityType("CUSTOMER");
        row.setEntityId(10L);
        row.setStatus("PENDING");
        when(queueMapper.selectById(1L)).thenReturn(row);
        when(customerMapper.assignTenantForRepair(10L, "tenant-a")).thenReturn(1);
        when(queueMapper.markResolved(eq(1L), eq("tenant-a"), eq("契約台帳で確認"), eq("ticket-123"),
                any(), any())).thenReturn(1);

        service.resolve(1L, "tenant-a", "契約台帳で確認", "ticket-123");

        verify(customerMapper).assignTenantForRepair(10L, "tenant-a");
        verify(queueMapper).markResolved(eq(1L), eq("tenant-a"), eq("契約台帳で確認"), eq("ticket-123"),
                any(), any());
    }

    @Test
    void 非管理者はrepairの一覧とsummaryを実行できない() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("sales", "N/A", "ROLE_営業"));
        assertThrows(RuntimeException.class, service::listPending);
        assertThrows(RuntimeException.class, service::summary);
    }

    @Test
    void assign先ユーザーも同じtenantでなければ拒否する() {
        when(sysUserMapper.selectByIdAndTenant(99L, "tenant-a")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.assign(1L, 99L));
        verify(queueMapper, never()).assign(any(), any(), any());
    }
}
