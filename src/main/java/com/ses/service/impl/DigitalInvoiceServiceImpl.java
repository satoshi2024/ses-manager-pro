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
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class DigitalInvoiceServiceImpl extends ServiceImpl<DigitalInvoiceMapper, DigitalInvoice> implements DigitalInvoiceService {

    private final DigitalInvoiceEventService digitalInvoiceEventService;
    private final PeppolParticipantService peppolParticipantService;
    private final JpPintValidator validator;
    private final JpPintRenderer renderer;
    private final DigitalInvoiceProvider provider;
    private final com.ses.mapper.InvoiceItemMapper invoiceItemMapper;
    private final InvoiceService invoiceService;
    private final com.ses.service.integration.IntegrationJobService integrationJobService;
    private final com.ses.service.DocumentService documentService;
    private final com.ses.service.CustomerService customerService;

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
            if (!updateById(invoice)) { throw new com.ses.common.exception.BusinessException("ステータス更新の競合が発生しました。"); }
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

        String payload = "{\"digitalInvoiceId\":" + di.getId() + "}";
        String payloadHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(payload);
        String idempotencyKey = "digital_invoice_send_" + di.getMessageId(); // 業務キー(message_id)に基づく冪等性
        integrationJobService.createJob(
            null, // connectionId はV107でNULL可に変更
            "DIGITAL_INVOICE_SEND",
            "t_digital_invoice",
            di.getId(),
            idempotencyKey,
            payloadHash
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

            // P1-01: InvoiceItem/税 snapshot を写像
            java.util.List<com.ses.entity.InvoiceItem> items = invoiceItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.InvoiceItem>()
                    .eq(com.ses.entity.InvoiceItem::getInvoiceId, invoice.getId())
            );
            
            java.util.List<CanonicalInvoice.CanonicalInvoiceItem> canonicalItems = items.stream().map(item -> {
                CanonicalInvoice.CanonicalInvoiceItem line = CanonicalInvoice.CanonicalInvoiceItem.builder().build();
                line.setDescription(item.getDescription());
                line.setLineAmount(item.getAmount());
                return line;
            }).toList();

            // CanonicalInvoice生成 (簡易マッピング)
            com.ses.entity.Customer customer = customerService.getById(invoice.getCustomerId());
            String peppolId = customer != null ? customer.getCompanyCode() : "buyer-peppol-id"; // Dummy since we didn't fetch participant

            CanonicalInvoice.CustomerInfo customerInfo = CanonicalInvoice.CustomerInfo.builder()
                .peppolParticipantId(peppolId)
                .name(customer != null ? customer.getCompanyName() : "Unknown Buyer")
                .build();
            CanonicalInvoice.SupplierInfo supplierInfo = CanonicalInvoice.SupplierInfo.builder()
                .corporateNumber("T1234567890123")
                .name("SES Manager Pro Inc.")
                .build();

            CanonicalInvoice canonicalInvoice = CanonicalInvoice.builder()
                    .invoiceNumber(invoice.getInvoiceNo())
                    .issuedDate(invoice.getIssuedDate())
                    .dueDate(invoice.getIssuedDate() != null ? invoice.getIssuedDate().plusDays(30) : null)
                    .supplier(supplierInfo)
                    .customer(customerInfo)
                    .taxExclusiveAmount(invoice.getSubtotal())
                    .taxAmount(invoice.getTax())
                    .taxInclusiveAmount(invoice.getTotal())
                    .roundingAmount(java.math.BigDecimal.ZERO)
                    .items(canonicalItems)
                    .build();

            // 金額の検算
            validator.validateAmount(canonicalInvoice);

            // XML生成
            String xml = renderer.render(canonicalInvoice, di.getSpecificationVersion());

            // プロバイダAPIへ送信
            String providerMessageId = provider.sendInvoice(xml, di.getSpecificationVersion());
            
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                .documentType("INVOICE")
                .direction("OUTGOING")
                .sourceType("GENERATED")
                .businessKey("DIGITAL_INVOICE_SEND:" + di.getId())
                .versionDiscriminator("1")
                .originalName(invoice.getInvoiceNo() + "_peppol.xml")
                .contentType("application/xml")
                .build();
            try {
                com.ses.entity.Document docEntity = documentService.registerGenerated(req, new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                di.setXmlDocumentId(docEntity.getId());
            } catch (Exception e) {
                log.error("Failed to archive outbound XML", e); throw new com.ses.common.exception.BusinessException("XMLのアーカイブに失敗しました。");
            }

            // ステータス更新
            di.setProviderMessageId(providerMessageId);
            di.setStatus("SENT");
            di.setSentAt(LocalDateTime.now());
            if (!updateById(di)) { throw new com.ses.common.exception.BusinessException("ステータス更新の競合が発生しました。"); }

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
    public void processInboundInvoice(String providerMessageId, String eventId, String xmlContent, String rawPayloadHash, java.time.LocalDateTime eventAt) {
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
        
        com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
            .documentType("INVOICE")
            .direction("INCOMING")
            .sourceType("RECEIVED")
            .businessKey("DIGITAL_INVOICE:" + providerMessageId)
            .versionDiscriminator("1")
            .originalName(providerMessageId + ".xml")
            .contentType("application/xml")
            .build();
        try {
            com.ses.entity.Document docEntity = documentService.registerReceived(req, new java.io.ByteArrayInputStream(xmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            di.setXmlDocumentId(docEntity.getId());
        } catch (Exception e) {
            log.error("Failed to archive inbound XML", e); throw new com.ses.common.exception.BusinessException("XMLのアーカイブに失敗しました。");
        }
        di.setMessageId("MSG-" + java.util.UUID.randomUUID().toString()); // fallback

        try {
            // セキュアパース
            org.w3c.dom.Document doc = renderer.parseSecurely(xmlContent);
            org.w3c.dom.NodeList idNodes = doc.getElementsByTagNameNS("*", "ID");
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
            org.w3c.dom.NodeList endpointNodes = doc.getElementsByTagNameNS("*", "EndpointID");
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

        di.setReceivedAt(eventAt != null ? eventAt : LocalDateTime.now());
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
