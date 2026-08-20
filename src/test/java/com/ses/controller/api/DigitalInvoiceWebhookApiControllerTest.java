package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.service.DigitalInvoiceEventService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceWebhookApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;
    
    @Autowired
    private DigitalInvoiceEventService digitalInvoiceEventService;

    @MockBean
    private DigitalInvoiceProvider provider;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.DocumentService documentService;

    @Test
    void testReceiveWebhook_Success_ValidSignature() throws Exception {
        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(100L);
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-1");
        di.setProviderMessageId("provider-msg-1");
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        String json = "{\"messageId\":\"provider-msg-1\", \"status\":\"DELIVERED\", \"eventId\":\"evt-1\"}";

        when(provider.verifyWebhookSignature(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", "valid-sig")
                .content(json))
                .andExpect(status().isOk());

        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("DELIVERED", updated.getStatus());

        List<DigitalInvoiceEvent> events = digitalInvoiceEventService.lambdaQuery()
                .eq(DigitalInvoiceEvent::getDigitalInvoiceId, di.getId())
                .list();
        assertEquals(1, events.size());
        assertEquals(true, events.get(0).getSignatureValid());
    }

    @Test
    void testReceiveWebhook_InvalidSignature_DoesNotChangeStatus() throws Exception {
        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(101L);
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-2");
        di.setProviderMessageId("provider-msg-2");
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        String json = "{\"messageId\":\"provider-msg-2\", \"status\":\"DELIVERED\", \"eventId\":\"evt-2\"}";

        when(provider.verifyWebhookSignature(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", "invalid-sig")
                .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("Invalid signature"));

        DigitalInvoice updated = digitalInvoiceService.getById(di.getId());
        assertEquals("SENT", updated.getStatus()); // ステータスは変わらない

        List<DigitalInvoiceEvent> events = digitalInvoiceEventService.lambdaQuery()
                .eq(DigitalInvoiceEvent::getDigitalInvoiceId, di.getId())
                .list();
        assertEquals(0, events.size(), "不正イベントは記録されない");
    }

    @Test
    void testReceiveWebhook_MagicValidSigHeader_DoesNotBypassWhenProviderRejects() throws Exception {
        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(102L);
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-3");
        di.setProviderMessageId("provider-msg-3");
        di.setStatus("SENT");
        digitalInvoiceService.save(di);

        String json = "{\"messageId\":\"provider-msg-3\", \"status\":\"DELIVERED\", \"eventId\":\"evt-3\"}";

        // マジック文字列 valid-sig を provider が拒否した場合、状態は変わらない（S16-P1-01）
        when(provider.verifyWebhookSignature(any(), org.mockito.ArgumentMatchers.eq("valid-sig"))).thenReturn(false);

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", "valid-sig")
                .content(json))
                .andExpect(status().isUnauthorized());

        assertEquals("SENT", digitalInvoiceService.getById(di.getId()).getStatus());
        assertEquals(0, digitalInvoiceEventService.lambdaQuery()
                .eq(DigitalInvoiceEvent::getDigitalInvoiceId, di.getId())
                .count());
    }

    @Test
    void testReceiveInboundInvoice() throws Exception {
        when(provider.verifyWebhookSignature(any(), any())).thenReturn(true);
        com.ses.entity.Document archived = new com.ses.entity.Document();
        archived.setId(9001L);
        when(documentService.registerReceived(any(), any())).thenReturn(archived);

        com.ses.entity.PeppolParticipant pp = new com.ses.entity.PeppolParticipant();
        pp.setOwnerType("BP");
        pp.setOwnerId(1L);
        pp.setSchemeId("0188");
        pp.setParticipantId("1234567890123");
        pp.setProvider("FAST_ACCOUNTING");
        pp.setStatus("VERIFIED");
        pp.setVerifiedAt(java.time.LocalDateTime.now());

        org.springframework.context.ApplicationContext ctx =
            org.springframework.web.context.support.WebApplicationContextUtils.getRequiredWebApplicationContext(
                mockMvc.getDispatcherServlet().getServletContext());
        ctx.getBean(com.ses.service.PeppolParticipantService.class).save(pp);

        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice>"
                + "<ID>INV-999</ID>"
                + "<IssueDate>2026-08-01</IssueDate>"
                + "<EndpointID schemeID=\"0188\">1234567890123</EndpointID>"
                + "<LegalMonetaryTotal><TaxInclusiveAmount>1100</TaxInclusiveAmount></LegalMonetaryTotal>"
                + "</Invoice>";
        String payload = "{\"status\": \"RECEIVED\", \"messageId\": \"msg-in-1\", \"eventId\": \"ev-in-1\", \"eventAt\": \"2026-08-20T12:00:00Z\", \"xmlContent\": \""+ xmlContent.replace("\"", "\\\"") +"\"}";

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Signature", "valid-signature"))
                .andExpect(status().isOk());

        DigitalInvoice di = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getProviderMessageId, "msg-in-1")
                .one();

        org.junit.jupiter.api.Assertions.assertNotNull(di);
        assertEquals("RECEIVE", di.getDirection());
        assertEquals("PENDING_REVIEW", di.getStatus());
        assertEquals("INV-999", di.getMessageId());
    }

    @Test
    void testReceiveInboundInvoice_DuplicateMessageId() throws Exception {
        when(provider.verifyWebhookSignature(any(), any())).thenReturn(true);
        com.ses.entity.Document archived = new com.ses.entity.Document();
        archived.setId(9002L);
        when(documentService.registerReceived(any(), any())).thenReturn(archived);

        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice>"
                + "<ID>INV-888</ID>"
                + "<IssueDate>2026-08-01</IssueDate>"
                + "<LegalMonetaryTotal><TaxInclusiveAmount>500</TaxInclusiveAmount></LegalMonetaryTotal>"
                + "</Invoice>";
        String payload = "{\"status\": \"RECEIVED\", \"messageId\": \"msg-dup\", \"eventId\": \"ev-dup\", \"eventAt\": \"2026-08-20T12:00:00Z\", \"xmlContent\": \""+ xmlContent.replace("\"", "\\\"") +"\"}";

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Signature", "valid-signature"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Signature", "valid-signature"))
                .andExpect(status().isOk());

        long count = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getProviderMessageId, "msg-dup")
                .count();

        assertEquals(1, count, "Should not create duplicate DigitalInvoice on duplicate webhook");
    }
}


