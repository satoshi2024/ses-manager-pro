package com.ses.service.portal;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.portal.PortalBpAvailabilityRequest;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpCompany;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.BpCompanyService;
import com.ses.service.BpTermsResolver;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import com.ses.service.portal.impl.PortalBpServiceImpl;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalBpServiceImplCreateAvailabilityTest {

    @Mock private BpAvailabilityMapper availabilityMapper;
    @Mock private BpPaymentMapper paymentMapper;
    @Mock private BpCompanyMapper bpCompanyMapper;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private BpCompanyService bpCompanyService;
    @Mock private BpTermsResolver bpTermsResolver;
    @Mock private DocumentService documentService;
    @Mock private ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;
    @Mock private LegalEntityContextService legalEntityContextService;

    private PortalBpServiceImpl service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @BeforeEach
    void setUp() {
        service = new PortalBpServiceImpl(
                availabilityMapper, paymentMapper, bpCompanyMapper, documentLinkMapper,
                documentVersionMapper, documentMapper, bpCompanyService, bpTermsResolver,
                documentService, approvalTargetAdapterRegistry, clock);
        AccountingTenantContextHolder.setTenantId("default");
        when(bpCompanyMapper.selectByIdForTenant(eq(10L), any())).thenReturn(
                BpCompany.builder().legalName("BP社").build());
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAvailability_beanNullは503で1Lを刻印しない() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createAvailability(10L, request()));
        assertEquals(503, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
        verify(availabilityMapper, never()).insert(any(BpAvailability.class));
    }

    @Test
    void createAvailability_法人未バインドは書き込み失敗() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        when(legalEntityContextService.requireCurrentLegalEntityId())
                .thenThrow(BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createAvailability(10L, request()));
        assertEquals(403, ex.getCode());
        verify(availabilityMapper, never()).insert(any(BpAvailability.class));
    }

    @Test
    void createAvailability_多法人は書き込み失敗() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        when(legalEntityContextService.requireCurrentLegalEntityId())
                .thenThrow(BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED"));

        assertThrows(BusinessException.class, () -> service.createAvailability(10L, request()));
        verify(availabilityMapper, never()).insert(any(BpAvailability.class));
    }

    @Test
    void createAvailability_合法PortalLoginUserと唯一法人は成功する() {
        AttendanceScopeMapper attendance = mock(AttendanceScopeMapper.class);
        AccountingTimezoneResolver tz = mock(AccountingTimezoneResolver.class);
        when(tz.resolve("default")).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(attendance.selectAllLegalEntityIds()).thenReturn(List.of(42L));
        LegalEntityContextService realCtx = new LegalEntityContextService(
                clock, attendance, tz, new OidcSecurityProperties());
        ReflectionTestUtils.setField(service, "legalEntityContextService", realCtx);

        PortalLoginUser portal = PortalLoginUser.builder()
                .portalUserId(1L).portalOrgId(2L).orgType("BP").bpCompanyId(10L)
                .tenantId("default").email("bp@example.com").userStatus("ACTIVE")
                .orgStatus("ACTIVE").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(portal, null, portal.getAuthorities()));

        service.createAvailability(10L, request());

        ArgumentCaptor<BpAvailability> captor = ArgumentCaptor.forClass(BpAvailability.class);
        verify(availabilityMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getLegalEntityId());
        assertNotEquals(1L, captor.getValue().getLegalEntityId());
        assertEquals("default", captor.getValue().getTenantId());
    }

    @Test
    void createAvailability_権威法人を刻印し1Lを推測しない() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(42L);

        service.createAvailability(10L, request());

        ArgumentCaptor<BpAvailability> captor = ArgumentCaptor.forClass(BpAvailability.class);
        verify(availabilityMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getLegalEntityId());
        assertNotEquals(Long.valueOf(1L), captor.getValue().getLegalEntityId());
    }

    private static PortalBpAvailabilityRequest request() {
        PortalBpAvailabilityRequest req = new PortalBpAvailabilityRequest();
        req.setInitialName("A.T");
        req.setUnitPrice(new BigDecimal("700000"));
        return req;
    }
}
