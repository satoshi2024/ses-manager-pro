package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Invoice;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.impl.CustomerServiceImpl;
import com.ses.service.impl.EngineerServiceImpl;
import com.ses.service.impl.InvoiceServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 法人バインド欠落時の Customer/Engineer/Invoice 書込みと Copilot context 生成が fail-closed であることを保証する。
 */
class LegalEntityWriteFailClosedTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneId.of("UTC"));
    private LegalEntityContextService legalEntityContextService;

    @BeforeEach
    void setUp() {
        AttendanceScopeMapper attendanceScopeMapper = mock(AttendanceScopeMapper.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("tenant-a")).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(attendanceScopeMapper.selectAllLegalEntityIds()).thenReturn(List.of());
        when(attendanceScopeMapper.selectLegalEntityIdsByUser(anyLong(), any())).thenReturn(List.of());
        legalEntityContextService = new LegalEntityContextService(
                clock, attendanceScopeMapper, timezoneResolver, new OidcSecurityProperties());
        authenticate("tenant-a", "営業", 10L);
        AccountingTenantContextHolder.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 法人未バインドではCustomer保存できない() {
        CustomerServiceImpl service = new CustomerServiceImpl(
                mock(ProjectMapper.class), mock(ContractMapper.class),
                mock(InvoiceMapper.class), mock(TenantOwnershipResolver.class));
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save(Customer.builder().companyName("A社").build()));
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void 法人未バインドではEngineer保存できない() {
        EngineerServiceImpl service = new EngineerServiceImpl(
                mock(ContractMapper.class), mock(ProposalMapper.class),
                mock(EngineerSalesService.class), mock(EngineerAccountLinkService.class),
                mock(com.ses.mapper.SysUserMapper.class), mock(TenantOwnershipResolver.class));
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save(Engineer.builder().fullName("要員A").build()));
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void 法人未バインドではInvoice保存できない() {
        CustomerMapper customerMapper = mock(CustomerMapper.class);
        Customer customer = Customer.builder().companyName("客").legalEntityId(99L).build();
        customer.setId(1L);
        when(customerMapper.selectById(1L)).thenReturn(customer);

        InvoiceServiceImpl service = new InvoiceServiceImpl();
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        ReflectionTestUtils.setField(service, "customerMapper", customerMapper);

        Invoice invoice = new Invoice();
        invoice.setCustomerId(1L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(invoice));
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void 法人未バインドではCopilotがrecommendation用contextを作れない() {
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        CopilotExecutionContextFactory factory = new CopilotExecutionContextFactory(
                clock, legalEntityContextService, readiness);

        BusinessException ex = assertThrows(BusinessException.class, factory::create);
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    private void authenticate(String tenantId, String role, Long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setRole(role);
        LoginUser principal = new LoginUser(user, List.of(), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
