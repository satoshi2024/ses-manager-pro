package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.DocumentService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.invoice.InvoiceDeliveryDispatcher;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceApiControllerTest {

    private static final String SECRET = "db password=secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @SpyBean
    private DigitalInvoiceService digitalInvoiceService;

    @MockBean
    private InvoiceDeliveryDispatcher deliveryDispatcher;

    @MockBean
    private DocumentService documentService;

    @BeforeEach
    void tenant付き認証主体を設定する() {
        TenantTestSecurity.bind("default");
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
    }

    @AfterEach
    void tenant付き認証主体を破棄する() {
        TenantTestSecurity.clear();
    }

    @Test
    @WithMockUser(roles = "管理者")
    void Peppol検証済みプレビューを返す() throws Exception {
        Customer c = customer("Test Co", "PEPPOL");
        verifiedParticipant(c, "test-id");
        Invoice inv = invoice(c, "INV-001");

        mockMvc.perform(get("/api/digital-invoices/preview/" + inv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSend", is(true)))
                .andExpect(jsonPath("$.data.deliveryPreference", is("PEPPOL")))
                .andExpect(jsonPath("$.data.peppolStatus", is("VERIFIED")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void Peppol未検証プレビューを返す() throws Exception {
        Customer c = customer("Test Co 2", "PEPPOL");
        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setSchemeId("0192");
        pp.setProvider("FASTACCOUNTING");
        pp.setStatus("PENDING");
        pp.setVerifiedAt(null);
        pp.setParticipantId("test-id-2");
        peppolParticipantService.save(pp);

        Invoice inv = invoice(c, "INV-002");

        mockMvc.perform(get("/api/digital-invoices/preview/" + inv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSend", is(false)))
                .andExpect(jsonPath("$.data.peppolStatus", is("UNVERIFIED")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 送信済みプレビューを送信不可で返す() throws Exception {
        Customer c = customer("Test Co 3", "PEPPOL");
        Invoice inv = invoice(c, "INV-003");

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-ALREADY-" + inv.getId());
        di.setStatus("QUEUED");
        digitalInvoiceService.save(di);

        mockMvc.perform(get("/api/digital-invoices/preview/" + inv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSend", is(false)))
                .andExpect(jsonPath("$.data.alreadySent", is(true)));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者はステータス履歴からXML参照可能() throws Exception {
        Customer c = customer("Admin View XML Co", "PDF");
        Invoice inv = invoice(c, "INV-ADMIN-01");

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-ADMIN-" + inv.getId());
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        mockMvc.perform(get("/api/digital-invoices/" + inv.getId() + "/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canViewXml", is(true)))
                .andExpect(jsonPath("$.data.xmlUrl").exists());

        // xmlDocumentId 未設定のため 404 が正しい（閲覧可否は status-history で検証）
        mockMvc.perform(get("/api/digital-invoices/" + di.getId() + "/xml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業はステータス履歴からXMLを参照できない() throws Exception {
        Customer c = customer("Sales View XML Co", "PDF");
        Invoice inv = invoice(c, "INV-SALES-01");

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-SALES-" + inv.getId());
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        mockMvc.perform(get("/api/digital-invoices/" + inv.getId() + "/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SENT")))
                .andExpect(jsonPath("$.data.canViewXml", is(false)));

        mockMvc.perform(get("/api/digital-invoices/" + di.getId() + "/xml"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void invoiceIdがNULLのscope外受信XMLはDocumentServiceを呼ばず403にする() throws Exception {
        DigitalInvoice inbound = new DigitalInvoice();
        inbound.setDirection("RECEIVE");
        inbound.setProfile("Standard");
        inbound.setSpecificationVersion("1.1.3");
        inbound.setMessageId("MSG-SCOPE-DENIED-NULL-INVOICE");
        inbound.setProviderMessageId("PROVIDER-SCOPE-DENIED");
        inbound.setStatus("PENDING_REVIEW");
        inbound.setXmlDocumentId(8801L);
        digitalInvoiceService.save(inbound);
        doThrow(BusinessException.of(403, "error.accessDenied"))
                .when(digitalInvoiceService).assertInboundAccessAllowed(inbound.getId());

        mockMvc.perform(get("/api/digital-invoices/" + inbound.getId() + "/xml"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));
        verify(documentService, never()).download(anyLong(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者でも別tenant法人のpreview_dispatch_history_cancel_downloadを拒否する() throws Exception {
        Customer foreignCustomer = customer("Foreign Tenant Co", "PDF");
        Invoice foreignInvoice = invoice(foreignCustomer, "INV-FOREIGN-TENANT");
        DigitalInvoice foreignDigital = new DigitalInvoice();
        foreignDigital.setInvoiceId(foreignInvoice.getId());
        foreignDigital.setDirection("SEND");
        foreignDigital.setProfile("Standard");
        foreignDigital.setSpecificationVersion("1.1.3");
        foreignDigital.setMessageId("MSG-FOREIGN-" + foreignInvoice.getId());
        foreignDigital.setStatus("QUEUED");
        foreignDigital.setXmlDocumentId(8899L);
        digitalInvoiceService.save(foreignDigital);

        jdbcTemplate.update("UPDATE m_customer SET tenant_id='foreign-tenant', legal_entity_id=2 WHERE id=?",
                foreignCustomer.getId());
        jdbcTemplate.update("UPDATE t_invoice SET legal_entity_id=2 WHERE id=?", foreignInvoice.getId());
        jdbcTemplate.update("UPDATE t_digital_invoice SET tenant_id='foreign-tenant', legal_entity_id=2 WHERE id=?",
                foreignDigital.getId());

        mockMvc.perform(get("/api/digital-invoices/preview/" + foreignInvoice.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/digital-invoices/dispatch/" + foreignInvoice.getId()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/digital-invoices/" + foreignInvoice.getId() + "/status-history"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/digital-invoices/" + foreignDigital.getId() + "/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("error.invoice.notFound")));
        mockMvc.perform(get("/api/digital-invoices/" + foreignDigital.getId() + "/xml"))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(digitalInvoiceService.getById(foreignDigital.getId()).getStatus())
                .isEqualTo("QUEUED");
        verify(documentService, never()).download(org.mockito.ArgumentMatchers.eq(8899L),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 送信ディスパッチのシステム例外原文を返さない() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DigitalInvoiceApiController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            Customer c = customer("Dispatch Leak Co", "PEPPOL");
            Invoice inv = invoice(c, "INV-DISPATCH-LEAK");
            doThrow(new RuntimeException(SECRET))
                    .when(deliveryDispatcher).dispatch(anyLong(), anyLong(), anyString());

            mockMvc.perform(post("/api/digital-invoices/dispatch/" + inv.getId()).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", is("error.invoice.dispatchFailed")))
                    .andExpect(jsonPath("$.message", not(containsString("secret"))));

            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage()).doesNotContain("secret");
                if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
                    org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy().getMessage()).doesNotContain("secret");
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 送信ディスパッチの競合業務例外を安全に返す() throws Exception {
        Customer c = customer("Dispatch Conflict Co", "PEPPOL");
        Invoice inv = invoice(c, "INV-DISPATCH-409");
        doThrow(new BusinessException(409, "このインボイスはすでに送信されています（または送信キューにあります）。"))
                .when(deliveryDispatcher).dispatch(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/digital-invoices/dispatch/" + inv.getId()).with(csrf()))
                    .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)))
                .andExpect(jsonPath("$.message", containsString("すでに送信")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 取消のシステム例外原文を返さない() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DigitalInvoiceApiController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            Customer c = customer("Cancel Leak Co", "PDF");
            Invoice inv = invoice(c, "INV-CANCEL-LEAK");
            DigitalInvoice di = new DigitalInvoice();
            di.setInvoiceId(inv.getId());
            di.setDirection("SEND");
            di.setProfile("Standard");
            di.setSpecificationVersion("1.1.3");
            di.setMessageId("MSG-CANCEL-LEAK-" + inv.getId());
            di.setStatus("QUEUED");
            digitalInvoiceService.save(di);

            doThrow(new RuntimeException(SECRET)).when(digitalInvoiceService).cancelInvoice(di.getId());

            mockMvc.perform(post("/api/digital-invoices/" + di.getId() + "/cancel").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", is("error.invoice.cancelFailed")))
                    .andExpect(jsonPath("$.message", not(containsString("secret"))));

            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage()).doesNotContain("secret");
                if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
                    org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy().getMessage()).doesNotContain("secret");
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private Customer customer(String name, String preference) {
        Customer c = new Customer();
        c.setCompanyName(name);
        c.setDeliveryPreference(preference);
        customerService.save(c);
        return c;
    }

    private PeppolParticipant verifiedParticipant(Customer c, String participantId) {
        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setSchemeId("0192");
        pp.setProvider("FASTACCOUNTING");
        pp.setStatus("ACTIVE");
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId(participantId);
        peppolParticipantService.save(pp);
        return pp;
    }

    private Invoice invoice(Customer c, String invoiceNo) {
        Invoice inv = new Invoice();
        inv.setInvoiceNo(invoiceNo);
        inv.setCustomerId(c.getId());
        inv.setBillingMonth("2026-08");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("1100"));
        inv.setStatus("未送付");
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);
        return inv;
    }
}
