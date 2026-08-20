package com.ses.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.result.ApiResult;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/webhooks/digital-invoice")
@RequiredArgsConstructor
public class DigitalInvoiceWebhookApiController {

    private final DigitalInvoiceService digitalInvoiceService;
    private final DigitalInvoiceProvider provider;
    private final ObjectMapper objectMapper;

    @PostMapping("/fastaccounting")
    public ResponseEntity<String> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Signature", defaultValue = "") String signature) {
        
        try {
            boolean isValid = provider.verifyWebhookSignature(rawBody, signature);
            if (!isValid) {
                // P0-02: すべての event type で raw body 署名が false なら状態も受信行も作らない。
                // provider_event_id UNIQUE に無効署名を載せない
                return ResponseEntity.ok("Invalid signature recorded");
            }

            JsonNode root = objectMapper.readTree(rawBody);

            String providerMessageId = root.path("messageId").asText();
            String eventType = root.path("status").asText(); // DELIVERED, REJECTED, RECEIVED etc.
            String eventId = root.path("eventId").asText();
            String eventAtStr = root.path("eventAt").asText();
            LocalDateTime eventAt = eventAtStr.isEmpty() ? LocalDateTime.now() : java.time.OffsetDateTime.parse(eventAtStr).toLocalDateTime();

            // 受信(Inbound)イベントの処理
            if ("RECEIVED".equalsIgnoreCase(eventType)) {
                String xmlContent = root.path("xmlContent").asText();
                String rawPayloadHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(rawBody);
                digitalInvoiceService.processInboundInvoice(providerMessageId, eventId, xmlContent, rawPayloadHash, eventAt);
                return ResponseEntity.ok("Inbound Invoice Received");
            }
            
            // 対象インボイスの検索 (送信分)
            DigitalInvoice di = digitalInvoiceService.lambdaQuery()
                    .eq(DigitalInvoice::getProviderMessageId, providerMessageId)
                    .one();
                    
            if (di == null) {
                // 不明なインボイスの場合は処理せずに 200 を返す（再送防止）
                return ResponseEntity.ok("Unknown messageId");
            }

            DigitalInvoiceEvent event = new DigitalInvoiceEvent();
            event.setDigitalInvoiceId(di.getId());
            event.setProviderEventId(eventId);
            event.setEventType(eventType);
            event.setEventAt(eventAt);
            event.setPayloadHash(org.apache.commons.codec.digest.DigestUtils.sha256Hex(rawBody));
            event.setSignatureValid(isValid);
            
            digitalInvoiceService.processProviderEvent(event);

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            // パースエラー等
            return ResponseEntity.badRequest().body("Bad Request");
        }
    }
}
