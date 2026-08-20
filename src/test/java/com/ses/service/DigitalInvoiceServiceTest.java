package com.ses.service;

import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DigitalInvoiceServiceTest {

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private DigitalInvoiceEventService digitalInvoiceEventService;

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

        digitalInvoiceService.processProviderEvent(event);

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

        digitalInvoiceService.processProviderEvent(event);

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

        digitalInvoiceService.processProviderEvent(event);

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
        currentEvent.setProviderEventId("evt-4-new");
        currentEvent.setEventType("SENT");
        currentEvent.setEventAt(LocalDateTime.now());
        currentEvent.setPayloadHash("hash");
        currentEvent.setSignatureValid(true);
        digitalInvoiceEventService.save(currentEvent);

        // 古いイベント(過去時刻)を後から受信
        DigitalInvoiceEvent olderEvent = new DigitalInvoiceEvent();
        olderEvent.setDigitalInvoiceId(invoice.getId());
        olderEvent.setProviderEventId("evt-4-old");
        olderEvent.setEventType("QUEUED");
        olderEvent.setEventAt(LocalDateTime.now().minusMinutes(5));
        olderEvent.setPayloadHash("hash");
        olderEvent.setSignatureValid(true);

        digitalInvoiceService.processProviderEvent(olderEvent);

        DigitalInvoice updated = digitalInvoiceService.getById(invoice.getId());
        assertEquals("SENT", updated.getStatus()); // 巻き戻らない
        assertEquals(2, digitalInvoiceEventService.count());
    }
}
