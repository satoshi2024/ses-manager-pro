package com.ses.service.invoice;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.PeppolParticipant;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceSendTest {

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private InvoiceService invoiceService;

    @MockBean
    private InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private com.ses.service.integration.IntegrationJobService integrationJobService;

    @Autowired
    private DigitalInvoiceProvider digitalInvoiceProvider;

    @MockBean
    private com.ses.service.DocumentService documentService;

    @Test
    void testEnqueueInvoice_Success() {
        Customer c = newCustomer("Test Co");
        verifiedParticipant(c, "test-id");

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(10L, "1.1.3", c.getId());
        assertNotNull(di.getId());
        assertEquals("QUEUED", di.getStatus());

        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        assertNotNull(job);
        assertEquals("PENDING", job.getStatus());
    }

    @Test
    void testEnqueueInvoice_DuplicateThrowsException() {
        Customer c = newCustomer("Test Co 2");
        verifiedParticipant(c, "test-id-2");

        digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId());

        assertThrows(BusinessException.class, () ->
                digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId()));
    }

    @Test
    void testMockProvider_IdempotencyByMessageId() {
        String first = digitalInvoiceProvider.sendInvoice("<xml/>", "1.1.3", "MSG-IDEMPOTENT-1");
        String second = digitalInvoiceProvider.sendInvoice("<xml/>", "1.1.3", "MSG-IDEMPOTENT-1");
        assertEquals(first, second);
    }

    @Test
    void testCancelSent_CreatesCreditNoteAndAllowsRequeue() {
        Customer c = newCustomer("Cancel Co");
        verifiedParticipant(c, "cancel-id");
        Invoice inv = validInvoice("INV-CANCEL-1", c.getId());

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        di.setStatus("SENT");
        digitalInvoiceService.updateById(di);

        digitalInvoiceService.cancelInvoice(di.getId());

        DigitalInvoice revoked = digitalInvoiceService.getById(di.getId());
        assertEquals("REVOKED", revoked.getStatus());

        long creditNotes = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, inv.getId())
                .eq(DigitalInvoice::getProfile, "CreditNote")
                .count();
        assertEquals(1, creditNotes);

        DigitalInvoice requeued = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        assertEquals("Standard", requeued.getProfile());
        assertEquals("QUEUED", requeued.getStatus());
    }

    @Test
    void testSendDigitalInvoice_ValidationFailure() {
        Customer c = newCustomer("Test Co 3");
        verifiedParticipant(c, "test-id-3");

        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-999");
        inv.setCustomerId(c.getId());
        inv.setBillingMonth("2026-08");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("9999"));
        inv.setStatus("未送付");
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);
        stubItems(inv.getId(), "1000");

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");

        digitalInvoiceService.processSendJob(job.getId());

        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(job.getId());
        assertEquals("FAILED", updatedJob.getStatus());
        assertEquals("VALIDATION_FAILED", updatedJob.getErrorCode());

        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("QUEUED", updated.getStatus());
    }

    @Test
    void testSendDigitalInvoice_Success() {
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(7001L);
            return doc;
        });

        Customer c = newCustomer("Test Co 4");
        verifiedParticipant(c, "test-id-4");
        Invoice inv = validInvoice("INV-001", c.getId());

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");

        digitalInvoiceService.processSendJob(job.getId());

        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("SENT", updated.getStatus());
        assertNotNull(updated.getProviderMessageId());
        assertNotNull(updated.getSentAt());

        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(job.getId());
        assertEquals("SUCCEEDED", updatedJob.getStatus());
    }

    private Customer newCustomer(String name) {
        Customer c = new Customer();
        c.setCompanyName(name);
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);
        return c;
    }

    private PeppolParticipant verifiedParticipant(Customer c, String participantId) {
        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId(participantId);
        pp.setSchemeId("0192");
        pp.setProvider("FASTACCOUNTING");
        pp.setStatus("ACTIVE");
        peppolParticipantService.save(pp);
        return pp;
    }

    private Invoice validInvoice(String invoiceNo, Long customerId) {
        Invoice inv = new Invoice();
        inv.setInvoiceNo(invoiceNo);
        inv.setCustomerId(customerId);
        inv.setBillingMonth("2026-08");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("1100"));
        inv.setStatus("未送付");
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);
        stubItems(inv.getId(), "1000");
        return inv;
    }

    @SuppressWarnings("unchecked")
    private void stubItems(Long invoiceId, String amount) {
        InvoiceItem item = new InvoiceItem();
        item.setInvoiceId(invoiceId);
        item.setWorkRecordId(1L);
        item.setDescription("Test");
        item.setAmount(new BigDecimal(amount));
        when(invoiceItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    }
}
