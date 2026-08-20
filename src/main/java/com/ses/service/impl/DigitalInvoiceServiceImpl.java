package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.entity.Invoice;
import com.ses.dto.invoice.CanonicalInvoice;
import com.ses.mapper.DigitalInvoiceMapper;
import com.ses.service.DigitalInvoiceEventService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.invoice.JpPintRenderer;
import com.ses.service.invoice.JpPintValidator;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DigitalInvoiceServiceImpl extends ServiceImpl<DigitalInvoiceMapper, DigitalInvoice> implements DigitalInvoiceService {

    private final DigitalInvoiceEventService digitalInvoiceEventService;
    private final PeppolParticipantService peppolParticipantService;
    private final JpPintValidator validator;
    private final JpPintRenderer renderer;
    private final DigitalInvoiceProvider provider;
    private final InvoiceService invoiceService;
    private final com.ses.service.integration.IntegrationJobService integrationJobService;

    private static final Set<String> TERMINAL_STATUSES = Set.of("DELIVERED", "REJECTED", "CANCELLED");

    @Override
    @Transactional
    public void processProviderEvent(DigitalInvoiceEvent event) {
        if (!event.getSignatureValid()) {
            digitalInvoiceEventService.save(event);
            return;
        }

        long count = digitalInvoiceEventService.lambdaQuery()
                .eq(DigitalInvoiceEvent::getProviderEventId, event.getProviderEventId())
                .count();
        if (count > 0) {
            return;
        }

        digitalInvoiceEventService.save(event);

        DigitalInvoice invoice = getById(event.getDigitalInvoiceId());
        if (invoice != null) {
            if (TERMINAL_STATUSES.contains(invoice.getStatus())) {
                return;
            }

            DigitalInvoiceEvent latestEvent = digitalInvoiceEventService.lambdaQuery()
                    .eq(DigitalInvoiceEvent::getDigitalInvoiceId, event.getDigitalInvoiceId())
                    .ne(DigitalInvoiceEvent::getId, event.getId())
                    .orderByDesc(DigitalInvoiceEvent::getEventAt)
                    .last("LIMIT 1")
                    .one();

            if (latestEvent != null && event.getEventAt().isBefore(latestEvent.getEventAt())) {
                return;
            }

            String newStatus = event.getEventType().toUpperCase(); 
            invoice.setStatus(newStatus);
            updateById(invoice);
        }
    }

    @Override
    @Transactional
    public DigitalInvoice enqueueInvoiceForSend(Long invoiceId, String specVersion, Long customerId) {
        // 宛先が検証済みかチェック
        peppolParticipantService.assertVerified("CUSTOMER", customerId);

        // 重複チェック (invoice_id, direction, specification_version)
        long count = lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .eq(DigitalInvoice::getDirection, "SEND")
                .eq(DigitalInvoice::getSpecificationVersion, specVersion)
                .count();
        if (count > 0) {
            throw new BusinessException("このインボイスはすでに送信キューに登録されています。");
        }

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(invoiceId);
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion(specVersion);
        di.setMessageId("MSG-" + UUID.randomUUID().toString()); // 自システム内のMessageID
        di.setStatus("QUEUED");
        save(di);

        // P1-1修正: ジョブ基盤への登録 (Outboxパターン)
        String idempotencyKey = "digital_invoice_send_" + di.getId();
        integrationJobService.createJob(
            null, // connectionId (Sandbox/直接APIキーなので一旦nullまたは不要)
            "DIGITAL_INVOICE_SEND",
            "t_digital_invoice",
            di.getId(),
            idempotencyKey,
            "hash"
        );

        return di;
    }

    @Override
    public void processSendJob(Long jobId) {
        com.ses.entity.IntegrationJob job = integrationJobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        try {
            Long digitalInvoiceId = job.getTargetId();
            DigitalInvoice di = getById(digitalInvoiceId);
            if (di == null || !"QUEUED".equals(di.getStatus()) || !"SEND".equals(di.getDirection())) {
                integrationJobService.markFailed(jobId, "INVALID_STATE", "DigitalInvoice not found or not QUEUED.");
                return;
            }

            Invoice invoice = invoiceService.getById(di.getInvoiceId());
            if (invoice == null) {
                integrationJobService.markFailed(jobId, "INVOICE_NOT_FOUND", "紐づく元のInvoiceが存在しません。");
                return;
            }

            // CanonicalInvoice生成 (簡易マッピング)
            CanonicalInvoice canonicalInvoice = CanonicalInvoice.builder()
                    .invoiceNumber(invoice.getInvoiceNo())
                    .issuedDate(invoice.getIssuedDate())
                    .taxExclusiveAmount(invoice.getSubtotal())
                    .taxAmount(invoice.getTax())
                    .taxInclusiveAmount(invoice.getTotal())
                    .roundingAmount(java.math.BigDecimal.ZERO)
                    .build();

            // 金額の検算
            validator.validateAmount(canonicalInvoice);

            // XML生成
            String xml = renderer.render(canonicalInvoice, di.getSpecificationVersion());

            // プロバイダAPIへ送信
            String providerMessageId = provider.sendInvoice(xml, di.getSpecificationVersion());

            // ステータス更新
            di.setProviderMessageId(providerMessageId);
            di.setStatus("SENT");
            di.setSentAt(LocalDateTime.now());
            updateById(di);

            integrationJobService.markSucceeded(jobId, String.valueOf(di.getId()), providerMessageId, "Invoice sent successfully.");

        } catch (BusinessException e) {
            // バリデーションエラー等の恒久エラー
            integrationJobService.markFailed(jobId, "VALIDATION_FAILED", e.getMessage());
        } catch (Exception e) {
            // 一時障害の可能性（タイムアウト等）は再試行可能
            integrationJobService.markRetryable(jobId, "SEND_ERROR", e.getMessage(), 300);
        }
    }

    @Override
    @Transactional
    public void processInboundInvoice(String providerMessageId, String eventId, String xmlContent, String rawPayloadHash) {
        // 1. 重複チェック: providerMessageId
        long countMsg = lambdaQuery().eq(DigitalInvoice::getProviderMessageId, providerMessageId).count();
        if (countMsg > 0) return;

        // payloadHashチェック
        long countHash = digitalInvoiceEventService.lambdaQuery().eq(DigitalInvoiceEvent::getPayloadHash, rawPayloadHash).count();
        if (countHash > 0) return;

        DigitalInvoice di = new DigitalInvoice();
        di.setDirection("RECEIVE");
        di.setProviderMessageId(providerMessageId);
        di.setSpecificationVersion("1.1.3"); // fallback
        di.setProfile("Standard");
        di.setMessageId("MSG-" + java.util.UUID.randomUUID().toString()); // fallback

        try {
            // セキュアパース
            org.w3c.dom.Document doc = renderer.parseSecurely(xmlContent);
            org.w3c.dom.NodeList idNodes = doc.getElementsByTagName("ID");
            String invoiceNo = idNodes.getLength() > 0 ? idNodes.item(0).getTextContent() : di.getMessageId();
            di.setMessageId(invoiceNo); // invoiceNo を messageId として扱う (R3.4 supplier invoice number重複)
            
            long countSupplierInv = lambdaQuery().eq(DigitalInvoice::getMessageId, invoiceNo).eq(DigitalInvoice::getDirection, "RECEIVE").count();
            if (countSupplierInv > 0) return; // 既存受信インボイスと重複

            // CanonicalInvoiceへの変換（本来は完全なパースが必要だが、ここでは一部抽出）
            com.ses.dto.invoice.CanonicalInvoice canonical = com.ses.dto.invoice.CanonicalInvoice.builder()
                .invoiceNumber(invoiceNo)
                .build();
            // Validator で金額検算などを行う (例外が出たらREJECTED_AUTO)
            // validator.validateAmount(canonical); // 実際のXML値から詰め替えて検証する

            // 宛先PeppolIDの特定
            String participantId = null;
            org.w3c.dom.NodeList endpointNodes = doc.getElementsByTagName("EndpointID");
            if (endpointNodes.getLength() > 0) {
                participantId = endpointNodes.item(0).getTextContent();
            }

            if (participantId != null) {
                com.ses.entity.PeppolParticipant pp = peppolParticipantService.lambdaQuery()
                    .eq(com.ses.entity.PeppolParticipant::getParticipantId, participantId)
                    .one();
                if (pp == null) {
                    di.setStatus("REJECTED_AUTO");
                } else {
                    di.setStatus("PENDING_REVIEW");
                }
            } else {
                di.setStatus("REJECTED_AUTO");
            }

        } catch (Exception e) {
            di.setStatus("REJECTED_AUTO");
        }

        di.setReceivedAt(LocalDateTime.now());
        save(di);

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(di.getId());
        event.setProviderEventId(eventId);
        event.setEventType("RECEIVED");
        event.setEventAt(LocalDateTime.now());
        event.setPayloadHash(rawPayloadHash);
        event.setSignatureValid(true);
        digitalInvoiceEventService.save(event);
    }
}
