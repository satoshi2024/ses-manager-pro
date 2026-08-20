package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
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

    @Autowired
    private com.ses.mapper.InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private com.ses.service.integration.IntegrationJobService integrationJobService;

    @Test
    void testEnqueueInvoice_Success() {
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

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(10L, "1.1.3", c.getId());
        assertNotNull(di.getId());
        assertEquals("QUEUED", di.getStatus());
        
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        assertNotNull(job);
        assertEquals("PENDING", job.getStatus());
    }

    @Test
    void testEnqueueInvoice_DuplicateThrowsException() {
        Customer c = new Customer();
        c.setCompanyName("Test Co 2");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId("test-id-2");
        peppolParticipantService.save(pp);

        digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId());

        // 二重登録
        assertThrows(BusinessException.class, () -> {
            digitalInvoiceService.enqueueInvoiceForSend(20L, "1.1.3", c.getId());
        });
    }

    @Test
    void testSendDigitalInvoice_ValidationFailure() {
        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-999");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("9999")); // 合計が合わない
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv); com.ses.entity.InvoiceItem item = new com.ses.entity.InvoiceItem(); item.setInvoiceId(inv.getId()); item.setDescription("Test"); item.setAmount(new java.math.BigDecimal("1000")); invoiceItemMapper.insert(item);

        Customer c = new Customer();
        c.setCompanyName("Test Co 3");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId("test-id-3");
        peppolParticipantService.save(pp);

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(inv.getId(), "1.1.3", c.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        
        digitalInvoiceService.processSendJob(job.getId());
        
        // ジョブはFAILEDになる
        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(job.getId());
        assertEquals("FAILED", updatedJob.getStatus());
        assertEquals("VALIDATION_FAILED", updatedJob.getLastErrorCode());
        
        // ステータスはQUEUEDのまま
        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("QUEUED", updated.getStatus());
    }

    @Test
    void testSendDigitalInvoice_Success() {
        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-001");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("1100"));
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv); com.ses.entity.InvoiceItem item = new com.ses.entity.InvoiceItem(); item.setInvoiceId(inv.getId()); item.setDescription("Test"); item.setAmount(new java.math.BigDecimal("1000")); invoiceItemMapper.insert(item);

        Customer c = new Customer();
        c.setCompanyName("Test Co 4");
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId("test-id-4");
        peppolParticipantService.save(pp);

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
}
