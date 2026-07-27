package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 組織scope外の上長を所属へ登録できないことをサービス層で固定する。 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceManagerScopeTest {

    @Mock private OrganizationUnitMapper organizationUnitMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private CostCenterMapper costCenterMapper;
    @Mock private ManagementBudgetMapper managementBudgetMapper;
    @Mock private MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private OrganizationScopeService organizationScopeService;
    @InjectMocks private OrganizationServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", organizationUnitMapper);
        ReflectionTestUtils.setField(service, "organizationScopeService", organizationScopeService);
    }

    @Test
    void scope外の有効な上長を所属へ設定できない() {
        OrganizationUnit organization = OrganizationUnit.builder()
                .code("SCOPE-ORG").name("対象組織").type("部")
                .validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(0).build();
        organization.setId(10L);
        SysUser member = SysUser.builder().username("member").status(1).role("HR").build();
        member.setId(20L);
        SysUser manager = SysUser.builder().username("manager").status(1).role("マネージャー").build();
        manager.setId(30L);
        when(organizationUnitMapper.selectByIdForUpdate(10L)).thenReturn(organization);
        when(sysUserMapper.selectByIdForUpdate(20L)).thenReturn(member);
        when(sysUserMapper.selectById(30L)).thenReturn(manager);
        when(userOrganizationMapper.selectByUserForUpdate(20L)).thenReturn(List.of());
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.isAllowedUser(30L, LocalDate.of(2026, 7, 1))).thenReturn(false);

        UserOrganization assignment = UserOrganization.builder()
                .userId(20L).organizationId(10L).managerUserId(30L)
                .validFrom(LocalDate.of(2026, 7, 1)).primaryFlag(0).build();
        assertThrows(BusinessException.class, () -> service.assignUser(assignment));
    }
}
