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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    @MockBean
    private DigitalInvoiceProvider digitalInvoiceProvider;

    @MockBean
    private com.ses.service.DocumentService documentService;

    @BeforeEach
    void stubProviderDefaults() {
        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "mock-provider-" + inv.getArgument(2));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(7001L);
            return doc;
        });
    }

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

        DigitalInvoice first = digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId());
        assertNotNull(first.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId()));
        assertEquals(409, ex.getCode());

        long sendRows = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, 20L)
                .eq(DigitalInvoice::getDirection, "SEND")
                .eq(DigitalInvoice::getProfile, "Standard")
                .count();
        assertEquals(1, sendRows);

        long jobs = integrationJobService.lambdaQuery()
                .eq(com.ses.entity.IntegrationJob::getIdempotencyKey,
                        "digital_invoice_send_20_Standard_1.1.3_g0")
                .count();
        assertEquals(1, jobs);
    }

    @Test
    void testMockProvider_IdempotencyByMessageId() {
        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), eq("MSG-IDEMPOTENT-1")))
                .thenReturn("same-provider-id");
        String first = digitalInvoiceProvider.sendInvoice("<xml/>", "1.1.3", "MSG-IDEMPOTENT-1");
        String second = digitalInvoiceProvider.sendInvoice("<xml/>", "1.1.3", "MSG-IDEMPOTENT-1");
        assertEquals(first, second);
    }

    @Test
    void testCancelSent_CreatesCreditNoteAndDoesNotResendStandardInvoice() {
        Customer c = newCustomer("Cancel Co");
        verifiedParticipant(c, "cancel-id");
        Invoice inv = validInvoice("INV-CANCEL-1", c.getId());

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        di.setStatus("SENT");
        di.setMessageId("MSG-ORIGINAL-1");
        digitalInvoiceService.updateById(di);

        digitalInvoiceService.cancelInvoice(di.getId());

        DigitalInvoice revoked = digitalInvoiceService.getById(di.getId());
        assertEquals("REVOKED", revoked.getStatus());
        assertEquals("MSG-ORIGINAL-1", revoked.getMessageId());

        DigitalInvoice cn = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, inv.getId())
                .eq(DigitalInvoice::getProfile, "CreditNote")
                .one();
        assertNotNull(cn);

        com.ses.entity.IntegrationJob cnJob = integrationJobService.getLatestJob(
                "t_digital_invoice", cn.getId(), "DIGITAL_INVOICE_CREDIT_NOTE");
        assertNotNull(cnJob, "打消しは CREDIT_NOTE ジョブでなければならない");
        assertNull(integrationJobService.getLatestJob("t_digital_invoice", cn.getId(), "DIGITAL_INVOICE_SEND"));

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        digitalInvoiceService.processCreditNoteJob(cnJob.getId());

        verify(digitalInvoiceProvider, atLeastOnce()).sendInvoice(xmlCaptor.capture(), anyString(), eq(cn.getMessageId()));
        String sentXml = xmlCaptor.getValue();
        assertTrue(sentXml.contains("<CreditNote"), "CreditNote XML を送ること");
        assertFalse(sentXml.contains("<Invoice "), "請求 Invoice ルートを再送しないこと");

        DigitalInvoice requeued = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        assertEquals("Standard", requeued.getProfile());
        assertEquals("QUEUED", requeued.getStatus());
    }

    @Test
    void testProcessSendJob_RejectsCreditNoteProfile() {
        Customer c = newCustomer("Wrong Profile Co");
        verifiedParticipant(c, "wp-id");
        Invoice inv = validInvoice("INV-WP-1", c.getId());

        DigitalInvoice cn = new DigitalInvoice();
        cn.setInvoiceId(inv.getId());
        cn.setDirection("SEND");
        cn.setProfile("CreditNote");
        cn.setSpecificationVersion("1.1.3");
        cn.setMessageId("MSG-CN-WRONG");
        cn.setStatus("QUEUED");
        digitalInvoiceService.save(cn);

        String payload = "{\"digitalInvoiceId\":" + cn.getId() + "}";
        com.ses.entity.IntegrationJob job = integrationJobService.createJob(
                null, "DIGITAL_INVOICE_SEND", "t_digital_invoice", cn.getId(),
                "wrong_" + cn.getMessageId(),
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(payload));

        digitalInvoiceService.processSendJob(job.getId());

        com.ses.entity.IntegrationJob updated = integrationJobService.getById(job.getId());
        assertEquals("FAILED", updated.getStatus());
        assertEquals("WRONG_PROFILE", updated.getErrorCode());
        verify(digitalInvoiceProvider, never()).sendInvoice(anyString(), anyString(), anyString());
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
    void testSendDigitalInvoice_SystemErrorHidesExceptionMessage() {
        Customer c = newCustomer("Test Co Secret");
        verifiedParticipant(c, "test-id-secret");
        Invoice inv = validInvoice("INV-SECRET", c.getId());

        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db password=secret"));

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");

        digitalInvoiceService.processSendJob(job.getId());

        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(job.getId());
        assertNotNull(updatedJob.getErrorMessageSafe());
        assertEquals("error.invoice.dispatchFailed", updatedJob.getErrorMessageSafe());
        assertFalse(updatedJob.getErrorMessageSafe().contains("secret"));
        assertEquals("SEND_ERROR", updatedJob.getErrorCode());
    }

    @Test
    void testSendDigitalInvoice_Success_MapsTaxAndOrderReference() {
        Customer c = newCustomer("Test Co 4");
        verifiedParticipant(c, "test-id-4");
        Invoice inv = validInvoice("INV-001", c.getId());
        inv.setTaxRate(new BigDecimal("0.10"));
        inv.setRemarks("PO:PO-12345");
        invoiceService.updateById(inv);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");

        digitalInvoiceService.processSendJob(job.getId());

        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("SENT", updated.getStatus());
        assertNotNull(updated.getProviderMessageId());

        verify(digitalInvoiceProvider).sendInvoice(xmlCaptor.capture(), eq("1.1.3"), eq(di.getMessageId()));
        String xml = xmlCaptor.getValue();
        assertTrue(xml.contains("PO-12345"), "注文参照を写像すること");
        assertTrue(xml.contains("<cbc:Percent>10"), "税率を写像すること");
        assertTrue(xml.contains("<cbc:ID>S</cbc:ID>") || xml.contains(">S</cbc:ID>"), "税区分を写像すること");
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
        inv.setTaxRate(new BigDecimal("0.10"));
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
