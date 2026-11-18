package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P2-3: 個別指定・role・上長を含め、他tenantの名前をsnapshotへ持ち込まない。 */
@ExtendWith(MockitoExtension.class)
class LifecycleAssigneeTenantBoundaryTest {
    @Mock SysUserMapper users;
    @Mock OrganizationUnitMapper organizations;
    @Mock UserOrganizationMapper assignments;
    @Mock EngineerSalesService sales;
    @Mock EngineerAccountLinkService links;
    @InjectMocks LifecycleAssigneeResolver resolver;
    @BeforeEach void bind() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "tenant-boundary");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, SysUser.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, UserOrganization.class);
    }
    @AfterEach void clear() { AccountingTenantContextHolder.clear(); }

    @Test void 別tenantの要員を入力した時点で拒否する() {
        Engineer foreign = engineer(); foreign.setTenantId("tenant-b");
        assertThatThrownBy(() -> resolver.resolve(task("ROLE", "HR"), foreign, null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users, assignments);
    }

    @Test void 個別指定と特定ユーザーはtenant限定で取得し名前を持ち出さない() {
        assertThatThrownBy(() -> resolver.resolve(task("APPLICANT", null), engineer(), null, Map.of("CHECK", 99L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolver.resolve(task("SPECIFIC_USER", "99"), engineer(), null, null))
                .isInstanceOf(BusinessException.class);
        verify(users, times(2)).selectByIdAndTenant(99L, "tenant-a");
        verify(users, never()).selectById(any());
    }

    @Test void role一覧のSQLはtenantを含み同tenantの担当者を返す() {
        SysUser own = SysUser.builder().tenantId("tenant-a").role("HR").status(1).realName("担当").build(); own.setId(10L);
        when(users.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id");
            assertThat(query.getParamNameValuePairs().values()).contains("tenant-a", "HR");
            return List.of(own);
        });
        assertThat(resolver.resolve(task("ROLE", "HR"), engineer(), null, null).getUserId()).isEqualTo(10L);
    }

    @Test void 上長の所属もユーザーもtenantを指定する() {
        UserOrganization link = UserOrganization.builder().tenantId("tenant-a").organizationId(7L).managerUserId(99L).build();
        when(assignments.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOrganization> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id");
            assertThat(query.getParamNameValuePairs().values()).contains("tenant-a");
            return List.of(link);
        });
        assertThatThrownBy(() -> resolver.resolve(task("ORGANIZATION_MANAGER", null), engineer(), null, null))
                .isInstanceOf(BusinessException.class);
        verify(users).selectByIdAndTenant(99L, "tenant-a");
    }

    private Engineer engineer() {
        Engineer row = Engineer.builder().tenantId("tenant-a").organizationId(7L).build(); row.setId(1L); return row;
    }
    private LifecycleTemplateTask task(String rule, String value) {
        return LifecycleTemplateTask.builder().taskCode("CHECK").taskName("確認").assigneeRule(rule)
                .assigneeRuleValue(value).isMandatory(1).build();
    }
}
