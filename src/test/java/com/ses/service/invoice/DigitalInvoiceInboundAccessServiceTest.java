package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.entity.DigitalInvoice;
import com.ses.mapper.DigitalInvoiceMapper;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.security.DataScopeService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** inbound reviewのDataScope境界をcontrollerを経由せずservice呼出しで確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceInboundAccessServiceTest {

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private DigitalInvoiceMapper digitalInvoiceMapper;

    @MockBean
    private DataScopeService dataScopeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(99L));
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of());
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of());
    }

    @AfterEach
    void tearDown() {
        TenantTestSecurity.clear();
    }

    @Test
    void service直接呼出しでもscope外のreviewを拒否する() {
        DigitalInvoice invoice = insertPendingReview(100L);
        TenantTestSecurity.bindAs("default", "営業");

        assertThatThrownBy(() -> digitalInvoiceService.acceptInboundReview(invoice.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.accessDenied");
    }

    @Test
    void service直接呼出しでscope内reviewを許可する() {
        DigitalInvoice invoice = insertPendingReview(99L);
        TenantTestSecurity.bindAs("default", "営業");

        digitalInvoiceService.acceptInboundReview(invoice.getId());
    }

    @Test
    void 管理者はscope設定にかかわらずservice直接呼出しを許可する() {
        DigitalInvoice invoice = insertPendingReview(100L);
        TenantTestSecurity.bindAs("default", "管理者");

        digitalInvoiceService.acceptInboundReview(invoice.getId());
    }

    private DigitalInvoice insertPendingReview(Long contractId) {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setTenantId("default");
        invoice.setLegalEntityId(1L);
        invoice.setDirection("RECEIVE");
        invoice.setProviderMessageId("nf09-access-" + System.nanoTime());
        invoice.setSpecificationVersion("1.1.3");
        invoice.setProfile("Standard");
        invoice.setMessageId("NF09-ACCESS-" + System.nanoTime());
        invoice.setStatus("PENDING_REVIEW");
        invoice.setContractId(contractId);
        invoice.setActorType("PROVIDER");
        invoice.setConfirmationSource("PROVIDER_CALLBACK");
        invoice.setHumanUserId(null);
        digitalInvoiceMapper.insert(invoice);
        return invoice;
    }

}
