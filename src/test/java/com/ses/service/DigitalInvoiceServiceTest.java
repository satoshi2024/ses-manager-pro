package com.ses.service;

import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.exception.BusinessException;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceServiceTest {

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private DigitalInvoiceEventService digitalInvoiceEventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpTenantScope() {
        TenantTestSecurity.bindAs(1L, "digital-invoice-test", "default", "営業");
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
    }

    @AfterEach
    void clearTenantScope() {
        TenantTestSecurity.clear();
    }

    @Test
    void testProcessProviderEvent_UpdatesStatus() {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setDirection("SEND");
        invoice.setProfile("Standard");
        invoice.setSpecificationVersion("1.1.3");
        invoice.setMessageId("msg-1");
        invoice.setStatus("QUEUED");
        digitalInvoiceService.save(invoice);

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(invoice.getId());
        event.setProviderEventId("evt-1");
        event.setEventType("SENT");
        event.setEventAt(LocalDateTime.now());
        event.setPayloadHash("hash");
        event.setSignatureValid(true);

        processProviderEventAsVerified(event);

        DigitalInvoice updated = digitalInvoiceService.getById(invoice.getId());
        assertEquals("SENT", updated.getStatus());
        assertEquals(1, digitalInvoiceEventService.count());
    }

    @Test
    void testProcessProviderEvent_SignatureInvalid_DoesNotUpdateStatus() {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setDirection("SEND");
        invoice.setProfile("Standard");
        invoice.setSpecificationVersion("1.1.3");
        invoice.setMessageId("msg-2");
        invoice.setStatus("QUEUED");
        digitalInvoiceService.save(invoice);

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(invoice.getId());
        event.setProviderEventId("evt-2");
        event.setEventType("DELIVERED");
        event.setEventAt(LocalDateTime.now());
        event.setPayloadHash("hash");
        event.setSignatureValid(false);

        processProviderEventAsVerified(event);

        DigitalInvoice updated = digitalInvoiceService.getById(invoice.getId());
        assertEquals("QUEUED", updated.getStatus());
        assertEquals(1, digitalInvoiceEventService.count()); // イベント自体は保存される
    }

    @Test
    void testProcessProviderEvent_DoesNotRewindTerminalStatus() {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setDirection("SEND");
        invoice.setProfile("Standard");
        invoice.setSpecificationVersion("1.1.3");
        invoice.setMessageId("msg-3");
        invoice.setStatus("DELIVERED"); // 終端ステータス
        digitalInvoiceService.save(invoice);

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(invoice.getId());
        event.setProviderEventId("evt-3");
        event.setEventType("SENT"); // 古いステータスを遅れて受信
        event.setEventAt(LocalDateTime.now().minusMinutes(5));
        event.setPayloadHash("hash");
        event.setSignatureValid(true);

        processProviderEventAsVerified(event);

        DigitalInvoice updated = digitalInvoiceService.getById(invoice.getId());
        assertEquals("DELIVERED", updated.getStatus()); // 巻き戻らない
        assertEquals(1, digitalInvoiceEventService.count());
    }

    @Test
    void testProcessProviderEvent_DoesNotRewindNonTerminalStatus() {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setDirection("SEND");
        invoice.setProfile("Standard");
        invoice.setSpecificationVersion("1.1.3");
        invoice.setMessageId("msg-4");
        invoice.setStatus("SENT"); // 非終端ステータス
        digitalInvoiceService.save(invoice);

        // 新しいイベント(現在時刻)
        DigitalInvoiceEvent currentEvent = new DigitalInvoiceEvent();
        currentEvent.setDigitalInvoiceId(invoice.getId());
        currentEvent.setTenantId("default");
        currentEvent.setLegalEntityId(1L);
        currentEvent.setProviderEventId("evt-4-new");
        currentEvent.setEventType("SENT");
        currentEvent.setEventAt(LocalDateTime.now());
        currentEvent.setPayloadHash("hash");
        currentEvent.setSignatureValid(true);
        currentEvent.setActorType("PROVIDER");
        currentEvent.setConfirmationSource("PROVIDER_CALLBACK");
        digitalInvoiceEventService.save(currentEvent);

        // 古いイベント(過去時刻)を後から受信
        DigitalInvoiceEvent olderEvent = new DigitalInvoiceEvent();
        olderEvent.setDigitalInvoiceId(invoice.getId());
        olderEvent.setProviderEventId("evt-4-old");
        olderEvent.setEventType("QUEUED");
        olderEvent.setEventAt(LocalDateTime.now().minusMinutes(5));
        olderEvent.setPayloadHash("hash");
        olderEvent.setSignatureValid(true);

        processProviderEventAsVerified(olderEvent);

        DigitalInvoice updated = digitalInvoiceService.getById(invoice.getId());
        assertEquals("SENT", updated.getStatus()); // 巻き戻らない
        assertEquals(2, digitalInvoiceEventService.count());
    }

    @Test
    void 受信一覧とID参照は現在tenant法人だけに限定する() {
        DigitalInvoice current = inbound("msg-current", "provider-current");
        DigitalInvoice foreign = inbound("msg-foreign", "provider-foreign");
        digitalInvoiceService.save(current);
        digitalInvoiceService.save(foreign);
        jdbcTemplate.update(
                "UPDATE t_digital_invoice SET tenant_id='foreign-tenant', legal_entity_id=2 WHERE id=?",
                foreign.getId());

        var page = digitalInvoiceService.searchInboundInvoices(1, 10);

        assertEquals(1, page.getTotal());
        assertEquals(current.getId(), page.getRecords().get(0).getId());
        assertNull(digitalInvoiceService.getScopedById(foreign.getId()));
    }

    @Test
    void saveは事前設定された別scopeを拒否する() {
        DigitalInvoice foreign = inbound("msg-forged-scope", "provider-forged-scope");
        foreign.setTenantId("foreign-tenant");
        foreign.setLegalEntityId(2L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> digitalInvoiceService.save(foreign));

        assertEquals(403, error.getCode());
        assertEquals("TENANT_CONTEXT_MISMATCH", error.getMessageKey());
    }

    @Test
    void providerEventは明示的なprovider主体が無い直呼びを拒否する() {
        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(1L);
        event.setProviderEventId("evt-no-provider-context");

        BusinessException error = assertThrows(BusinessException.class,
                () -> digitalInvoiceService.processProviderEvent(event));

        assertEquals(403, error.getCode());
        assertEquals("PROVIDER_ACTOR_CONTEXT_REQUIRED", error.getMessageKey());
    }

    @Test
    void provider送信statusイベントは受信インボイスを更新しない() {
        DigitalInvoice invoice = inbound("msg-inbound-status", "provider-inbound-status");
        digitalInvoiceService.save(invoice);
        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(invoice.getId());
        event.setProviderEventId("evt-inbound-delivered");
        event.setEventType("DELIVERED");
        event.setEventAt(LocalDateTime.now());
        event.setPayloadHash("hash-inbound-delivered");
        event.setSignatureValid(true);

        BusinessException error = assertThrows(
                BusinessException.class, () -> processProviderEventAsVerified(event));

        assertEquals(400, error.getCode());
        assertEquals("PENDING_REVIEW", digitalInvoiceService.getById(invoice.getId()).getStatus());
        assertEquals(0, digitalInvoiceEventService.count());
    }

    private void processProviderEventAsVerified(DigitalInvoiceEvent event) {
        ExecutionActorContext.runAsProviderCallback(
                "test-provider-callback", event.getProviderEventId(),
                () -> digitalInvoiceService.processProviderEvent(event));
    }

    private DigitalInvoice inbound(String messageId, String providerMessageId) {
        DigitalInvoice invoice = new DigitalInvoice();
        invoice.setDirection("RECEIVE");
        invoice.setProfile("Standard");
        invoice.setSpecificationVersion("1.1.3");
        invoice.setMessageId(messageId);
        invoice.setProviderMessageId(providerMessageId);
        invoice.setStatus("PENDING_REVIEW");
        return invoice;
    }
}
