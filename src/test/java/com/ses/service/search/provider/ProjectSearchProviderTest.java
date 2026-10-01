package com.ses.service.search.provider;

import com.ses.common.exception.BusinessException;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.TenantOwnershipResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectSearchProviderTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private TenantOwnershipResolver tenantOwnershipResolver;
    @InjectMocks private ProjectSearchProvider provider;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 管理者でもtenant所有案件以外は検索母集合に入れない() {
        when(tenantOwnershipResolver.resolveProjectIds("tenant-a")).thenReturn(Set.of(1L));
        when(dataScopeService.isScoped()).thenReturn(false);
        Project owned = Project.builder().projectName("A案件").build();
        owned.setId(1L);
        when(projectMapper.selectByIdsForTenant(eq("tenant-a"), eq(Set.of(1L)))).thenReturn(List.of(owned));

        List<GlobalSearchResultDTO> results = provider.search("案件", 10);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getId());
        verify(tenantOwnershipResolver).resolveProjectIds("tenant-a");
        verify(projectMapper).selectByIdsForTenant(eq("tenant-a"), eq(Set.of(1L)));
        verify(projectMapper, never()).selectPage(any(), any());
    }

    @Test
    void tenant所有が空なら検索せずemptyを返す() {
        when(tenantOwnershipResolver.resolveProjectIds("tenant-a")).thenReturn(Set.of());

        List<GlobalSearchResultDTO> results = provider.search("案件", 10);

        assertTrue(results.isEmpty());
        verify(projectMapper, never()).selectByIdsForTenant(any(), any());
        verify(projectMapper, never()).selectPage(any(), any());
    }

    @Test
    void scoped時はownershipとdataScopeの交差のみ() {
        when(tenantOwnershipResolver.resolveProjectIds("tenant-a")).thenReturn(Set.of(1L, 2L));
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedProjectIds()).thenReturn(Set.of(2L, 99L));
        Project owned = Project.builder().projectName("交差案件").build();
        owned.setId(2L);
        when(projectMapper.selectByIdsForTenant(eq("tenant-a"), eq(Set.of(2L)))).thenReturn(List.of(owned));

        List<GlobalSearchResultDTO> results = provider.search("交差", 10);

        assertEquals(1, results.size());
        assertEquals(2L, results.get(0).getId());
    }

    @Test
    void tenant欠落はfailClosed() {
        AccountingTenantContextHolder.clear();
        assertThrows(BusinessException.class, () -> provider.search("案件", 10));
        verify(projectMapper, never()).selectByIdsForTenant(any(), any());
        verify(projectMapper, never()).selectPage(any(), any());
    }

    @Test
    void fullAccessでもselectPage全表は使わない() {
        when(tenantOwnershipResolver.resolveProjectIds("tenant-a")).thenReturn(Set.of(7L));
        when(dataScopeService.isScoped()).thenReturn(false);
        when(projectMapper.selectByIdsForTenant(eq("tenant-a"), eq(Set.of(7L)))).thenReturn(List.of());

        provider.search("x", 5);

        verify(projectMapper, never()).selectPage(any(), any());
        verify(projectMapper).selectByIdsForTenant(eq("tenant-a"), eq(Set.of(7L)));
    }
}
