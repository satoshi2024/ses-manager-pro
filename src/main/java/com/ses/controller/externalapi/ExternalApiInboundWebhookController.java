package com.ses.controller.externalapi;

import com.ses.config.integrationhub.ExternalApiCanonicalRequest;
import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.dto.integrationhub.ExternalApiInboundWebhookResponse;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.service.integrationhub.ExternalApiInboundWebhookParser;
import com.ses.service.integrationhub.InboundEventProcessor;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubStates;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/** B2 inbound provider event endpoint。既存HMAC専用chainの認証済みbodyだけを受け取る。 */
@RestController
@RequestMapping("/external-api/v1")
@RequiredArgsConstructor
public class ExternalApiInboundWebhookController {
    private final ExternalApiInboundWebhookParser parser;
    private final InboundEventService inboundEventService;
    private final InboundEventProcessor inboundEventProcessor;
    private final Clock clock;

    @PostMapping(value = "/webhooks/{provider}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalApiInboundWebhookResponse> receive(
            @PathVariable String provider,
            HttpServletRequest request) {
        requireSingleJsonContentType(request);
        ExternalApiCanonicalRequest.Parsed signedRequest = signedRequest(request);
        LocalDateTime receivedAt = nowUtc();
        LocalDateTime signedAt = signedTimestamp(request);
        ExternalApiPrincipal principal = principal(request);
        ExternalApiInboundWebhookParser.Parsed parsed = parser.parse(
                provider, requiredSingleHeader(request, "X-Provider-Event-ID"), signedRequest.rawBody(), receivedAt);
        InboundEventService.Receipt receipt;
        try {
            receipt = inboundEventService.recordReceived(
                    principal.clientId(), parsed.providerName(), parsed.providerEventId(),
                    IntegrationHubDigest.sha256Hex(signedRequest.rawBody()), signedAt, parsed.snapshot(), true, receivedAt);
        } catch (SecurityException e) {
            // subscription/scope binding failure is an external authorization result; do not leak
            // mapper/provider details or let it fall through to the internal error page.
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
        if (receipt.conflict()) {
            throw ExternalApiSecurityException.inboundConflict();
        }
        if (receipt.duplicate()) {
            return response(receipt.event(), true, false, receipt.event().getStatus(), 200);
        }

        InboundEvent claimed = inboundEventService.claim(receipt.event().getId(), receivedAt);
        if (claimed == null) {
            return response(receipt.event(), true, false, IntegrationHubStates.INBOUND_PROCESSING, 202);
        }
        try {
            // claimとterminal CASは別transaction。processorはB2ではlocal no-opのみ。
            inboundEventProcessor.process(claimed);
        } catch (RuntimeException e) {
            if (!inboundEventService.complete(claimed.getId(), claimed.getVersion(),
                    IntegrationHubStates.INBOUND_DLQ, "INBOUND_PROCESSING_FAILED", nowUtc())) {
                throw new IllegalStateException("inbound DLQ terminal CAS failed", e);
            }
            return response(claimed, false, false, IntegrationHubStates.INBOUND_DLQ, 202);
        }
        if (!inboundEventService.complete(claimed.getId(), claimed.getVersion(),
                IntegrationHubStates.INBOUND_PROCESSED, "INBOUND_ACCEPTED", receivedAt)) {
            // CAS競合は別workerの終端結果を上書きしてはいけない。これはprocessor失敗とは異なる
            // 内部整合性エラーとして安全なJSON error boundaryへ渡す。
            throw new IllegalStateException("inbound terminal CAS failed");
        }
        return response(claimed, false, false, IntegrationHubStates.INBOUND_PROCESSED, 202);
    }

    private ResponseEntity<ExternalApiInboundWebhookResponse> response(InboundEvent event, boolean duplicate,
                                                                         boolean conflict, String status,
                                                                         int httpStatus) {
        String resultCode = event == null || event.getResultCode() == null
                ? status : event.getResultCode();
        return ResponseEntity.status(httpStatus)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(new ExternalApiInboundWebhookResponse(status, duplicate, conflict, resultCode));
    }

    private ExternalApiCanonicalRequest.Parsed signedRequest(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiCanonicalRequest.class.getName());
        if (!(value instanceof ExternalApiCanonicalRequest.Parsed parsed)) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        return parsed;
    }

    private ExternalApiPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof ExternalApiPrincipal principal)) {
            throw ExternalApiSecurityException.authentication("EXTERNAL_PRINCIPAL_MISSING");
        }
        return principal;
    }

    private LocalDateTime signedTimestamp(HttpServletRequest request) {
        Object value = request.getAttribute(
                com.ses.config.integrationhub.ExternalApiAuthenticationFilter.SIGNED_TIMESTAMP_ATTRIBUTE);
        if (!(value instanceof LocalDateTime timestamp)) {
            throw ExternalApiSecurityException.authentication("AUTHENTICATION_FAILED");
        }
        return timestamp;
    }

    private void requireSingleJsonContentType(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.CONTENT_TYPE);
        if (values == null || !values.hasMoreElements()) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(value);
            if (!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(mediaType.getType() + "/" + mediaType.getSubtype())
                    || mediaType.getParameters().keySet().stream()
                    .anyMatch(parameter -> !"charset".equalsIgnoreCase(parameter))
                    || (mediaType.getCharset() != null
                    && !StandardCharsets.UTF_8.equals(mediaType.getCharset()))) {
                throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
            }
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
    }

    private String requiredSingleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank() || value.length() > 160) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        return value;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
