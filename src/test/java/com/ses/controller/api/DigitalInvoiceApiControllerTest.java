package com.ses.controller.api;

import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DigitalInvoiceApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Test
    @WithMockUser(roles = "管理者")
    void testPreviewDelivery_PeppolVerified() throws Exception {
        Customer c = new Customer();
        c.setCompanyName("Test Co");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId("test-id");
        peppolParticipantService.save(pp);

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-001");
        inv.setCustomerId(c.getId());
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);

        mockMvc.perform(get("/api/digital-invoices/preview/" + inv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSend", is(true)))
                .andExpect(jsonPath("$.data.deliveryPreference", is("PEPPOL")))
                .andExpect(jsonPath("$.data.peppolStatus", is("VERIFIED")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPreviewDelivery_PeppolUnverified() throws Exception {
        Customer c = new Customer();
        c.setCompanyName("Test Co 2");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(null); // 未検証
        pp.setParticipantId("test-id-2");
        peppolParticipantService.save(pp);

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-002");
        inv.setCustomerId(c.getId());
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);

        mockMvc.perform(get("/api/digital-invoices/preview/" + inv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSend", is(false)))
                .andExpect(jsonPath("$.data.peppolStatus", is("UNVERIFIED")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testPreviewDelivery_AlreadySent() throws Exception {
        Customer c = new Customer();
        c.setCompanyName("Test Co 3");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-003");
        inv.setCustomerId(c.getId());
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setDirection("SEND");
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
        Customer c = new Customer();
        c.setCompanyName("Admin View XML Co");
        customerService.save(c);

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-ADMIN-01");
        inv.setCustomerId(c.getId());
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        mockMvc.perform(get("/api/digital-invoices/" + inv.getId() + "/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canViewXml", is(true)))
                .andExpect(jsonPath("$.data.xmlUrl").exists());

        mockMvc.perform(get("/api/digital-invoices/" + di.getId() + "/xml"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "営業")
    void testGetStatusHistory_SalesCannotViewXml() throws Exception {
        Customer c = new Customer();
        c.setCompanyName("Sales View XML Co");
        customerService.save(c);

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-SALES-01");
        inv.setCustomerId(c.getId());
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(inv.getId());
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        // 営業はStatusは見れるが、XMLはリンクがない
        mockMvc.perform(get("/api/digital-invoices/" + inv.getId() + "/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SENT")))
                .andExpect(jsonPath("$.data.canViewXml", is(false)));

        // 営業はXMLのダウンロードエンドポイントにアクセスすると403
        mockMvc.perform(get("/api/digital-invoices/" + di.getId() + "/xml"))
                .andExpect(status().isForbidden());
    }
}
