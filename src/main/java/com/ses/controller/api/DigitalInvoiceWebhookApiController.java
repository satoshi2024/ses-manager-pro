package com.ses.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/digital-invoice")
@RequiredArgsConstructor
public class DigitalInvoiceWebhookApiController {

    private static final int MAX_BODY_LENGTH = 1_048_576;

    private final DigitalInvoiceService digitalInvoiceService;
    private final DigitalInvoiceProvider provider;
    private final ObjectMapper objectMapper;

    @PostMapping("/fastaccounting")
    public ResponseEntity<String> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Signature", defaultValue = "") String signature) {
        CorrelationContext.ensure();
        try {
            if (rawBody == null || rawBody.length() > MAX_BODY_LENGTH) {
                return businessError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Webhookの内容が不正です。");
            }
            boolean isValid = provider.verifyWebhookSignature(rawBody, signature);
            if (!isValid) {
                // S16-P1-01: 署名不正は状態もイベントも作らず、拒否を明示する。
                return businessError(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "署名が不正です。");
            }

            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                return businessError(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID_PAYLOAD", "Webhookの内容が不正です。");
            }

            String providerMessageId = CorrelationContext.safeIdentifier(root.path("messageId").asText(null));
            String eventType = root.path("status").asText(null);
            if (eventType != null) {
                eventType = eventType.toUpperCase(java.util.Locale.ROOT);
            }
            String eventId = CorrelationContext.safeIdentifier(root.path("eventId").asText(null));
            if (providerMessageId == null || eventId == null || eventType == null
                    || !java.util.Set.of("DELIVERED", "REJECTED", "RECEIVED", "CANCELLED", "REVOKED")
                    .contains(eventType)) {
                return businessError(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID_PAYLOAD", "Webhookの内容が不正です。");
            }
            CorrelationContext.put(CorrelationContext.PROVIDER_OPERATION_ID, eventId);
            String eventAtStr = root.path("eventAt").asText();
            LocalDateTime eventAt;
            try {
                eventAt = eventAtStr.isEmpty() ? LocalDateTime.now() : java.time.OffsetDateTime.parse(eventAtStr).toLocalDateTime();
            } catch (java.time.format.DateTimeParseException e) {
                return businessError(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID_PAYLOAD", "Webhookの内容が不正です。");
            }

            // 受信イベントの処理
            if ("RECEIVED".equalsIgnoreCase(eventType)) {
                String xmlContent = root.path("xmlContent").asText();
                String rawPayloadHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(rawBody);
                digitalInvoiceService.processInboundInvoice(providerMessageId, eventId, xmlContent, rawPayloadHash, eventAt);
                DigitalInvoice received = digitalInvoiceService.lambdaQuery()
                        .eq(DigitalInvoice::getProviderMessageId, providerMessageId).one();
                if (received != null) {
                    CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, received.getId());
                }
                return ResponseEntity.ok("受信電子請求書を受け付けました。");
            }
            
            // 送信済みインボイスの検索
            DigitalInvoice di = digitalInvoiceService.lambdaQuery()
                    .eq(DigitalInvoice::getProviderMessageId, providerMessageId)
                    .one();
                    
            if (di == null) {
                // 不明なインボイスは処理せず、再送を防止する。
                return businessError(HttpStatus.OK, "INVOICE_NOT_FOUND", "対象のメッセージが見つかりません。");
            }

            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, di.getId());
            CorrelationContext.put(CorrelationContext.INVOICE_ID, di.getInvoiceId());
            DigitalInvoiceEvent event = new DigitalInvoiceEvent();
            event.setDigitalInvoiceId(di.getId());
            event.setProviderEventId(eventId);
            event.setEventType(eventType);
            event.setEventAt(eventAt);
            event.setPayloadHash(org.apache.commons.codec.digest.DigestUtils.sha256Hex(rawBody));
            event.setSignatureValid(isValid);
            
            digitalInvoiceService.processProviderEvent(event);

            return ResponseEntity.ok("処理を受け付けました。");

        } catch (BusinessException e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "WEBHOOK_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, e.getCode() >= 500 ? "SYSTEM" : "BUSINESS");
            log.warn("デジタルインボイスWebhookの業務処理に失敗: errorCode={} category={} exceptionClass={} detail={} {}",
                    "WEBHOOK_FAILED", e.getCode() >= 500 ? "SYSTEM" : "BUSINESS",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e), webhookContext());
            return ResponseEntity.status(e.getCode() >= 500 ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                    .body("Webhook処理を完了できませんでした。");
        } catch (Exception e) {
            CorrelationContext.put(CorrelationContext.ERROR_CODE, "WEBHOOK_FAILED");
            CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
            log.error("デジタルインボイスWebhookのシステム処理に失敗: errorCode={} category=SYSTEM exceptionClass={} detail={} {}",
                    "WEBHOOK_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e), webhookContext());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook処理を完了できませんでした。");
        }
    }

    private String webhookContext() {
        return "correlationId=" + value(CorrelationContext.CORRELATION_ID)
                + " digitalInvoiceId=" + value(CorrelationContext.DIGITAL_INVOICE_ID)
                + " providerOperationId=" + value(CorrelationContext.PROVIDER_OPERATION_ID);
    }

    private ResponseEntity<String> businessError(HttpStatus status, String errorCode, String message) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, errorCode);
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        return ResponseEntity.status(status).body(message);
    }

    private String value(String key) {
        String value = CorrelationContext.get(key);
        return value == null ? "-" : value;
    }
}
