package com.ses.integration;

import com.ses.BaseIntegrationTest;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.impl.EngineerServiceImpl;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.security.TenantOwnershipResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 要員削除/状態更新の tenant + 法人境界。管理者でも他tenantを越えず、法人不一致は403。
 */
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
class EngineerTenantLegalEntityIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired private EngineerServiceImpl engineerService;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private TenantOwnershipResolver tenantOwnershipResolver;
    @MockBean private LegalEntityContextService legalEntityContextService;

    private Fixture tenantA;
    private Fixture tenantB;

    @BeforeEach
    void setUp() {
        reset(legalEntityContextService);
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(100L);
        tenantA = fixture("tenant-a", 100L, "A");
        tenantB = fixture("tenant-b", 200L, "B");
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(engineerService, "legalEntityContextService", legalEntityContextService);
    }

    @Test
    void 他tenantの要員は削除も状態更新も404() {
        authenticate(tenantA);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(100L);

        assertThat(tenantOwnershipResolver.selectEngineer("tenant-a", tenantB.engineerId())).isNull();
        assertThat(engineerService.removeById(tenantB.engineerId(), 0)).isFalse();

        Engineer patch = Engineer.builder().fullName("改ざん").legalEntityId(100L).build();
        patch.setId(tenantB.engineerId());
        patch.setVersion(0);
        assertThatThrownBy(() -> engineerService.updateWithStatusGuard(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.scope.notFound");
    }

    @Test
    void 法人不一致は403() {
        authenticate(tenantA);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        doThrow(BusinessException.of(403, "LEGAL_ENTITY_MISMATCH"))
                .when(legalEntityContextService).assertCurrent(100L);

        assertThatThrownBy(() -> engineerService.removeById(tenantA.engineerId(), 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    void 法人コンテキスト欠落は403() {
        authenticate(tenantA);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        ReflectionTestUtils.setField(engineerService, "legalEntityContextService", null);

        assertThatThrownBy(() -> engineerService.removeById(tenantA.engineerId(), 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LEGAL_ENTITY_CONTEXT_REQUIRED");
    }

    @Test
    void 合法tenantと法人なら削除できる() {
        authenticate(tenantA);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(100L);

        assertThat(engineerService.removeById(tenantA.engineerId(), 0)).isTrue();
        verify(legalEntityContextService).assertCurrent(100L);
        assertThat(tenantOwnershipResolver.selectEngineer("tenant-a", tenantA.engineerId())).isNull();
        // 他tenantの行は残る
        AccountingTenantContextHolder.setTenantId("tenant-b");
        assertThat(tenantOwnershipResolver.selectEngineer("tenant-b", tenantB.engineerId())).isNotNull();
    }

    @Test
    void 管理者でもtenantOwnershipは越えられない() {
        authenticate(tenantA);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        assertThat(engineerMapper.selectByIdForTenant(tenantB.engineerId(), "tenant-a")).isNull();
        assertThat(tenantOwnershipResolver.resolveEngineerIds("tenant-a"))
                .contains(tenantA.engineerId())
                .doesNotContain(tenantB.engineerId());
    }

    private Fixture fixture(String tenantId, Long legalEntityId, String label) {
        SysUser user = new SysUser();
        user.setUsername("eng-" + tenantId);
        user.setPassword("password");
        user.setRealName("管理者" + label);
        user.setRole("管理者");
        user.setTenantId(tenantId);
        user.setStatus(1);
        sysUserMapper.insert(user);

        Engineer engineer = Engineer.builder()
                .fullName("要員" + label)
                .employmentType("正社員")
                .status("Bench")
                .tenantId(tenantId)
                .legalEntityId(legalEntityId)
                .build();
        engineerMapper.insert(engineer);
        return new Fixture(tenantId, user, engineer.getId(), legalEntityId);
    }

    private void authenticate(Fixture fixture) {
        SysUser u = fixture.user();
        LoginUser principal = new LoginUser(u, List.of(new SimpleGrantedAuthority("ROLE_管理者")), fixture.tenantId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private record Fixture(String tenantId, SysUser user, Long engineerId, Long legalEntityId) {}
}
