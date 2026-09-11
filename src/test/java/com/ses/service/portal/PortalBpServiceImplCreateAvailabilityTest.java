package com.ses.service.portal;

import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalBpAvailabilityRequest;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpCompany;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpTermsResolver;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
        service = new PortalBpServiceImpl(
                availabilityMapper, paymentMapper, bpCompanyMapper, documentLinkMapper,
                documentVersionMapper, documentMapper, bpCompanyService, bpTermsResolver,
                documentService, approvalTargetAdapterRegistry,
                Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneId.of("Asia/Tokyo")));
        AccountingTenantContextHolder.setTenantId("default");
        when(bpCompanyMapper.selectByIdForTenant(eq(10L), any())).thenReturn(
                BpCompany.builder().legalName("BP社").build());
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void createAvailability_beanNullは503で1Lを刻印しない() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", null);
        PortalBpAvailabilityRequest req = new PortalBpAvailabilityRequest();
        req.setInitialName("A.T");
        req.setUnitPrice(new BigDecimal("700000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createAvailability(10L, req));
        assertEquals(503, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void createAvailability_権威法人を刻印する() {
        ReflectionTestUtils.setField(service, "legalEntityContextService", legalEntityContextService);
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(42L);
        PortalBpAvailabilityRequest req = new PortalBpAvailabilityRequest();
        req.setInitialName("A.T");
        req.setUnitPrice(new BigDecimal("700000"));

        service.createAvailability(10L, req);

        ArgumentCaptor<BpAvailability> captor = ArgumentCaptor.forClass(BpAvailability.class);
        verify(availabilityMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getLegalEntityId());
        assertEquals("default", captor.getValue().getTenantId());
    }
}
