package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.invoice.InvoiceDeliveryDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
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
    private InvoiceService invoiceService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @SpyBean
    private DigitalInvoiceService digitalInvoiceService;

    @MockBean
    private InvoiceDeliveryDispatcher deliveryDispatcher;

    @Test
    @WithMockUser(roles = "管理者")
    void testPreviewDelivery_PeppolVerified() throws Exception {
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
    void testPreviewDelivery_PeppolUnverified() throws Exception {
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
    void testPreviewDelivery_AlreadySent() throws Exception {
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
    void testGetStatusHistory_AdminCanViewXml() throws Exception {
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
    void testGetStatusHistory_SalesCannotViewXml() throws Exception {
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
    @WithMockUser(roles = "管理者")
    void dispatch_hidesRuntimeExceptionMessage() throws Exception {
        Customer c = customer("Dispatch Leak Co", "PEPPOL");
        Invoice inv = invoice(c, "INV-DISPATCH-LEAK");
        doThrow(new RuntimeException(SECRET))
                .when(deliveryDispatcher).dispatch(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/digital-invoices/dispatch/" + inv.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("error.invoice.dispatchFailed")))
                .andExpect(jsonPath("$.message", not(containsString("secret"))));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void dispatch_rethrowsBusinessExceptionConflict() throws Exception {
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
    void cancel_hidesRuntimeExceptionMessage() throws Exception {
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
