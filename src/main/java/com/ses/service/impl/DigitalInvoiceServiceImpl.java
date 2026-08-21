package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.CanonicalInvoice;
import com.ses.dto.invoice.InboundPurchaseRequest;
import com.ses.entity.Contract;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.entity.Invoice;
import com.ses.mapper.DigitalInvoiceMapper;
import com.ses.service.ContractService;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceEventService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.DocumentService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.integration.IntegrationJobService;
import com.ses.service.invoice.JpPintRenderer;
import com.ses.service.invoice.JpPintValidator;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalInvoiceServiceImpl extends ServiceImpl<DigitalInvoiceMapper, DigitalInvoice> implements DigitalInvoiceService {

    private static final Set<String> WEBHOOK_TERMINAL_STATUSES = Set.of("DELIVERED", "REJECTED", "CANCELLED", "REVOKED");
    private static final String PROFILE_STANDARD = "Standard";
    private static final String PROFILE_CREDIT_NOTE = "CreditNote";
    private static final String JOB_SEND = "DIGITAL_INVOICE_SEND";
    private static final String JOB_CREDIT_NOTE = "DIGITAL_INVOICE_CREDIT_NOTE";

    private final DigitalInvoiceEventService digitalInvoiceEventService;
    private final PeppolParticipantService peppolParticipantService;
    private final JpPintValidator validator;
    private final JpPintRenderer renderer;
    private final DigitalInvoiceProvider provider;
    private final com.ses.mapper.InvoiceItemMapper invoiceItemMapper;
    private final InvoiceService invoiceService;
    private final IntegrationJobService integrationJobService;
    private final DocumentService documentService;
    private final CustomerService customerService;
    private final ContractService contractService;

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
            if (WEBHOOK_TERMINAL_STATUSES.contains(invoice.getStatus())) {
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

            invoice.setStatus(event.getEventType().toUpperCase());
            if (!updateById(invoice)) {
                throw new BusinessException("ステータス更新の競合が発生しました。");
            }
        }
    }

    @Override
    @Transactional
    public DigitalInvoice enqueueInvoiceForSend(Long invoiceId, String specVersion, Long customerId) {
        peppolParticipantService.assertVerified("CUSTOMER", customerId);

        long count = lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .eq(DigitalInvoice::getDirection, "SEND")
                .eq(DigitalInvoice::getProfile, PROFILE_STANDARD)
                .notIn(DigitalInvoice::getStatus, "CANCELLED", "REVOKED")
                .count();
        if (count > 0) {
            throw new BusinessException(409, "このインボイスはすでに送信されています（または送信キューにあります）。");
        }

        long generation = countSendGenerations(invoiceId, PROFILE_STANDARD, specVersion);
        String idempotencyKey = buildSendIdempotencyKey(invoiceId, PROFILE_STANDARD, specVersion, generation);

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(invoiceId);
        di.setDirection("SEND");
        di.setProfile(PROFILE_STANDARD);
        di.setSpecificationVersion(specVersion);
        // Peppol messageId は世代付きで一意。job 冪等キーは invoiceId+profile+spec(+世代) で UUID に依存しない
        di.setMessageId("MSG-SEND-" + invoiceId + "-" + specVersion + "-g" + generation);
        di.setStatus("QUEUED");
        try {
            save(di);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "このインボイスはすでに送信されています（または送信キューにあります）。");
        }

        String payload = "{\"digitalInvoiceId\":" + di.getId() + "}";
        integrationJobService.createJob(
                null,
                JOB_SEND,
                "t_digital_invoice",
                di.getId(),
                idempotencyKey,
                DigestUtils.sha256Hex(payload)
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
            DigitalInvoice di = getById(job.getTargetId());
            if (di == null || !"QUEUED".equals(di.getStatus()) || !"SEND".equals(di.getDirection())) {
                integrationJobService.markFailed(jobId, "INVALID_STATE", "DigitalInvoice not found or not QUEUED.");
                return;
            }
            // R5-P0-01: CreditNote を請求送信ジョブで処理しない
            if (!PROFILE_STANDARD.equals(di.getProfile())) {
                integrationJobService.markFailed(jobId, "WRONG_PROFILE",
                        "DIGITAL_INVOICE_SEND は Standard 専用です。CreditNote は DIGITAL_INVOICE_CREDIT_NOTE を使用してください。");
                return;
            }

            Invoice invoice = invoiceService.getById(di.getInvoiceId());
            if (invoice == null) {
                integrationJobService.markFailed(jobId, "INVOICE_NOT_FOUND", "紐づく元のInvoiceが存在しません。");
                return;
            }

            CanonicalInvoice canonicalInvoice = buildCanonicalFromInvoiceSnapshot(invoice);
            validator.validateAmount(canonicalInvoice);
            String xml = renderer.render(canonicalInvoice, di.getSpecificationVersion());

            if (di.getXmlDocumentId() == null) {
                archiveOutboundXml(di, invoice.getInvoiceNo() + "_peppol.xml", xml, "DIGITAL_INVOICE_SEND:" + di.getId());
            }

            String providerMessageId = di.getProviderMessageId();
            if (providerMessageId == null) {
                providerMessageId = provider.sendInvoice(xml, di.getSpecificationVersion(), di.getMessageId());
                di.setProviderMessageId(providerMessageId);
                di.setStatus("SENT");
                di.setSentAt(LocalDateTime.now());
                if (!updateById(di)) {
                    throw new BusinessException("ステータス更新の競合が発生しました。");
                }
            }

            integrationJobService.markSucceeded(jobId, String.valueOf(di.getId()), providerMessageId, "Invoice sent successfully.");
        } catch (BusinessException e) {
            log.warn("電子請求書送信ジョブの業務エラー jobId={}", jobId, e);
            integrationJobService.markFailed(jobId, "VALIDATION_FAILED", safeJobErrorMessage(e));
        } catch (Exception e) {
            log.warn("電子請求書送信ジョブのシステムエラー jobId={}", jobId, e);
            integrationJobService.markRetryable(jobId, "SEND_ERROR", "error.invoice.dispatchFailed", 300);
        }
    }

    @Override
    public void processCreditNoteJob(Long jobId) {
        com.ses.entity.IntegrationJob job = integrationJobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        try {
            DigitalInvoice cn = getById(job.getTargetId());
            if (cn == null || !"QUEUED".equals(cn.getStatus()) || !"SEND".equals(cn.getDirection())) {
                integrationJobService.markFailed(jobId, "INVALID_STATE", "CreditNote DigitalInvoice not found or not QUEUED.");
                return;
            }
            if (!PROFILE_CREDIT_NOTE.equals(cn.getProfile())) {
                integrationJobService.markFailed(jobId, "WRONG_PROFILE", "DIGITAL_INVOICE_CREDIT_NOTE は CreditNote 専用です。");
                return;
            }

            Invoice invoice = invoiceService.getById(cn.getInvoiceId());
            if (invoice == null) {
                integrationJobService.markFailed(jobId, "INVOICE_NOT_FOUND", "紐づく元のInvoiceが存在しません。");
                return;
            }

            DigitalInvoice revoked = lambdaQuery()
                    .eq(DigitalInvoice::getInvoiceId, cn.getInvoiceId())
                    .eq(DigitalInvoice::getDirection, "SEND")
                    .eq(DigitalInvoice::getProfile, PROFILE_STANDARD)
                    .eq(DigitalInvoice::getStatus, "REVOKED")
                    .orderByDesc(DigitalInvoice::getId)
                    .last("LIMIT 1")
                    .one();
            String billingRef = revoked != null ? revoked.getMessageId() : invoice.getInvoiceNo();

            // 金額検算用に snapshot は読むが、Standard Invoice XML は生成・送信しない
            CanonicalInvoice original = buildCanonicalFromInvoiceSnapshot(invoice);
            String xml = renderer.renderCreditNote(original, cn.getMessageId(), billingRef, cn.getSpecificationVersion());
            if (!xml.contains("<CreditNote") || xml.contains("<Invoice ")) {
                throw new BusinessException("CreditNote XMLの生成に失敗しました（請求Invoiceルートが混入）。");
            }

            if (cn.getXmlDocumentId() == null) {
                archiveOutboundXml(cn, invoice.getInvoiceNo() + "_creditnote.xml", xml, "DIGITAL_INVOICE_CREDIT_NOTE:" + cn.getId());
            }

            String providerMessageId = cn.getProviderMessageId();
            if (providerMessageId == null) {
                providerMessageId = provider.sendInvoice(xml, cn.getSpecificationVersion(), cn.getMessageId());
                cn.setProviderMessageId(providerMessageId);
                cn.setStatus("SENT");
                cn.setSentAt(LocalDateTime.now());
                if (!updateById(cn)) {
                    throw new BusinessException("ステータス更新の競合が発生しました。");
                }
            }

            integrationJobService.markSucceeded(jobId, String.valueOf(cn.getId()), providerMessageId, "Credit note sent successfully.");
        } catch (BusinessException e) {
            log.warn("CreditNote送信ジョブの業務エラー jobId={}", jobId, e);
            integrationJobService.markFailed(jobId, "VALIDATION_FAILED", safeJobErrorMessage(e));
        } catch (Exception e) {
            log.warn("CreditNote送信ジョブのシステムエラー jobId={}", jobId, e);
            integrationJobService.markRetryable(jobId, "SEND_ERROR", "error.invoice.dispatchFailed", 300);
        }
    }

    /**
     * ジョブの errorMessage には i18n キーまたは固定の業務文言のみを残す。
     * SQL/ドライバ/スタック原文など技術詳細は保存しない。
     */
    static String safeJobErrorMessage(BusinessException e) {
        String key = e.getMessageKey();
        if (key != null && key.startsWith("error.")) {
            return key;
        }
        String message = e.getMessage();
        if (message != null && message.startsWith("error.")) {
            return message;
        }
        if (message != null && !message.isBlank()) {
            return message;
        }
        return "error.invoice.dispatchFailed";
    }

    @Override
    @Transactional
    public void cancelInvoice(Long digitalInvoiceId) {
        DigitalInvoice di = getById(digitalInvoiceId);
        if (di == null) {
            throw new BusinessException("Invoice not found");
        }
        if (!"SEND".equals(di.getDirection())) {
            throw new BusinessException("Only SEND can be cancelled");
        }
        if ("CANCELLED".equals(di.getStatus()) || "REVOKED".equals(di.getStatus())) {
            return;
        }

        if ("QUEUED".equals(di.getStatus()) || "FAILED".equals(di.getStatus())) {
            di.setStatus("CANCELLED");
            if (!updateById(di)) {
                throw new BusinessException("Concurrent modification");
            }
            return;
        }

        di.setStatus("REVOKED");
        if (!updateById(di)) {
            throw new BusinessException("Concurrent modification");
        }

        long generation = countSendGenerations(di.getInvoiceId(), PROFILE_CREDIT_NOTE, di.getSpecificationVersion());
        String idempotencyKey = buildSendIdempotencyKey(
                di.getInvoiceId(), PROFILE_CREDIT_NOTE, di.getSpecificationVersion(), generation);

        DigitalInvoice cn = new DigitalInvoice();
        cn.setInvoiceId(di.getInvoiceId());
        cn.setDirection("SEND");
        cn.setProfile(PROFILE_CREDIT_NOTE);
        cn.setSpecificationVersion(di.getSpecificationVersion());
        cn.setMessageId("MSG-CN-" + di.getInvoiceId() + "-" + di.getSpecificationVersion() + "-g" + generation);
        cn.setStatus("QUEUED");
        try {
            save(cn);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "打消し電文はすでに送信キューにあります。");
        }

        String payload = "{\"digitalInvoiceId\":" + cn.getId() + "}";
        integrationJobService.createJob(
                null,
                JOB_CREDIT_NOTE,
                "t_digital_invoice",
                cn.getId(),
                idempotencyKey,
                DigestUtils.sha256Hex(payload)
        );
    }

    /** 同一 invoice×profile×spec の既存 SEND 件数 = 次世代番号（再 Queue 用。ランダム UUID 禁止）。 */
    private long countSendGenerations(Long invoiceId, String profile, String specVersion) {
        return lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .eq(DigitalInvoice::getDirection, "SEND")
                .eq(DigitalInvoice::getProfile, profile)
                .eq(DigitalInvoice::getSpecificationVersion, specVersion)
                .count();
    }

    /** job 冪等キー = invoiceId + profile + spec（+ 世代）。ランダム UUID を含めない。 */
    private static String buildSendIdempotencyKey(Long invoiceId, String profile, String specVersion, long generation) {
        return "digital_invoice_send_" + invoiceId + "_" + profile + "_" + specVersion + "_g" + generation;
    }

    @Override
    public void processInboundInvoice(String providerMessageId, String eventId, String xmlContent,
                                      String rawPayloadHash, LocalDateTime eventAt) {
        if (lambdaQuery().eq(DigitalInvoice::getProviderMessageId, providerMessageId).count() > 0) {
            return;
        }
        if (digitalInvoiceEventService.lambdaQuery().eq(DigitalInvoiceEvent::getPayloadHash, rawPayloadHash).count() > 0) {
            return;
        }

        DigitalInvoice di = new DigitalInvoice();
        di.setDirection("RECEIVE");
        di.setProviderMessageId(providerMessageId);
        di.setSpecificationVersion("1.1.3");
        di.setProfile(PROFILE_STANDARD);

        try {
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("INVOICE")
                    .direction("INCOMING")
                    .sourceType("RECEIVED")
                    .businessKey("DIGITAL_INVOICE:" + providerMessageId)
                    .versionDiscriminator("1")
                    .originalName(providerMessageId + ".xml")
                    .contentType("application/xml")
                    .build();
            com.ses.entity.Document docEntity = documentService.registerReceived(
                    req, new java.io.ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
            di.setXmlDocumentId(docEntity.getId());
        } catch (Exception e) {
            log.error("Failed to archive inbound XML", e);
            throw new BusinessException("XMLのアーカイブに失敗しました。");
        }

        di.setMessageId("MSG-" + UUID.randomUUID());

        try {
            org.w3c.dom.Document doc = renderer.parseSecurely(xmlContent);
            String invoiceNo = firstText(doc, "ID");
            if (invoiceNo == null || invoiceNo.isBlank()) {
                invoiceNo = di.getMessageId();
            }
            di.setMessageId(invoiceNo);

            if (lambdaQuery().eq(DigitalInvoice::getMessageId, invoiceNo).eq(DigitalInvoice::getDirection, "RECEIVE").count() > 0) {
                return;
            }

            applyInboundMatch(di, doc);
        } catch (Exception e) {
            di.setStatus("REJECTED_AUTO");
            di.setMatchStatus("UNMATCHED");
        }

        di.setReceivedAt(eventAt != null ? eventAt : LocalDateTime.now());
        save(di);

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(di.getId());
        event.setProviderEventId(eventId);
        event.setEventType("RECEIVED");
        event.setEventAt(eventAt != null ? eventAt : LocalDateTime.now());
        event.setPayloadHash(rawPayloadHash);
        event.setSignatureValid(true);
        digitalInvoiceEventService.save(event);
    }

    @Override
    @Transactional
    public InboundPurchaseRequest acceptInboundReview(Long digitalInvoiceId) {
        DigitalInvoice di = getById(digitalInvoiceId);
        if (di == null || !"RECEIVE".equals(di.getDirection())) {
            throw new BusinessException("対象が見つかりません。");
        }
        if (!"PENDING_REVIEW".equals(di.getStatus())) {
            throw new BusinessException("レビュー待ちのインボイスではありません。");
        }

        BigDecimal amount = null;
        LocalDate issueDate = null;
        if (di.getXmlDocumentId() != null) {
            try (java.io.InputStream is = documentService.download(di.getXmlDocumentId(), null)) {
                String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                org.w3c.dom.Document doc = renderer.parseSecurely(xml);
                amount = parseDecimal(firstText(doc, "TaxInclusiveAmount"));
                issueDate = parseDate(firstText(doc, "IssueDate"));
            } catch (Exception e) {
                log.warn("ACCEPT時のXML再読取に失敗しました digitalInvoiceId={}", digitalInvoiceId);
            }
        }

        InboundPurchaseRequest request = InboundPurchaseRequest.builder()
                .digitalInvoiceId(di.getId())
                .supplierCompanyId(di.getSupplierCompanyId())
                .amount(amount)
                .issueDate(issueDate)
                .purchaseOrderId(di.getPurchaseOrderId())
                .contractId(di.getContractId())
                .build();

        // accounting canonical へ渡す（自動支払確定はしない）
        handoffInboundPurchaseCandidate(request);

        di.setStatus("ACCEPTED");
        if (!updateById(di)) {
            throw new BusinessException("ステータス更新の競合が発生しました。");
        }
        return request;
    }

    /** 仕入候補の受け渡し境界。支払確定ジョブは起動しない。 */
    void handoffInboundPurchaseCandidate(InboundPurchaseRequest request) {
        log.info("Inbound purchase candidate handed to accounting canonical: digitalInvoiceId={}, supplierCompanyId={}, amount={}, issueDate={}, purchaseOrderId={}, contractId={}",
                request.getDigitalInvoiceId(), request.getSupplierCompanyId(), request.getAmount(),
                request.getIssueDate(), request.getPurchaseOrderId(), request.getContractId());
    }

    private void applyInboundMatch(DigitalInvoice di, org.w3c.dom.Document doc) {
        String participantId = firstText(doc, "EndpointID");
        BigDecimal amount = parseDecimal(firstText(doc, "TaxInclusiveAmount"));
        LocalDate issueDate = parseDate(firstText(doc, "IssueDate"));
        String orderRef = firstNestedText(doc, "OrderReference", "ID");
        String contractRef = firstNestedText(doc, "ContractDocumentReference", "ID");

        Long supplierCompanyId = null;
        if (participantId != null) {
            com.ses.entity.PeppolParticipant pp = peppolParticipantService.lambdaQuery()
                    .eq(com.ses.entity.PeppolParticipant::getParticipantId, participantId)
                    .one();
            if (pp != null) {
                supplierCompanyId = pp.getOwnerId();
            }
        }
        di.setSupplierCompanyId(supplierCompanyId);

        Long purchaseOrderId = resolveOptionalId(orderRef);
        Long contractId = resolveContractId(contractRef);
        di.setPurchaseOrderId(purchaseOrderId);
        di.setContractId(contractId);

        boolean keysOk = supplierCompanyId != null && amount != null && issueDate != null;
        if (orderRef != null && !orderRef.isBlank() && purchaseOrderId == null) {
            keysOk = false;
        }
        if (contractRef != null && !contractRef.isBlank() && contractId == null) {
            keysOk = false;
        }

        if (keysOk) {
            di.setStatus("PENDING_REVIEW");
            di.setMatchStatus("MATCHED");
        } else {
            di.setStatus(supplierCompanyId == null ? "REJECTED_AUTO" : "PENDING_REVIEW");
            di.setMatchStatus("UNMATCHED");
            if (supplierCompanyId == null) {
                di.setStatus("REJECTED_AUTO");
            }
        }
    }

    private Long resolveContractId(String contractRef) {
        if (contractRef == null || contractRef.isBlank()) {
            return null;
        }
        Long asId = resolveOptionalId(contractRef);
        if (asId != null) {
            Contract byId = contractService.getById(asId);
            if (byId != null) {
                return byId.getId();
            }
        }
        Contract byNo = contractService.lambdaQuery()
                .eq(Contract::getContractNo, contractRef)
                .last("LIMIT 1")
                .one();
        return byNo != null ? byNo.getId() : null;
    }

    private Long resolveOptionalId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private CanonicalInvoice buildCanonicalFromInvoiceSnapshot(Invoice invoice) {
        java.util.List<com.ses.entity.InvoiceItem> items = invoiceItemMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.InvoiceItem>()
                        .eq(com.ses.entity.InvoiceItem::getInvoiceId, invoice.getId())
        );

        BigDecimal taxRatePercent = invoice.getTaxRate() != null
                ? invoice.getTaxRate().multiply(new BigDecimal("100"))
                : new BigDecimal("10");
        String taxCategory = "S";

        java.util.List<CanonicalInvoice.CanonicalInvoiceItem> canonicalItems = items.stream().map(item ->
                CanonicalInvoice.CanonicalInvoiceItem.builder()
                        .description(item.getDescription())
                        .lineAmount(item.getAmount())
                        .unitPrice(item.getAmount())
                        .quantity(BigDecimal.ONE)
                        .taxCategory(taxCategory)
                        .taxRate(taxRatePercent)
                        .build()
        ).toList();

        com.ses.entity.Customer customer = customerService.getById(invoice.getCustomerId());
        com.ses.entity.PeppolParticipant pp = peppolParticipantService.lambdaQuery()
                .eq(com.ses.entity.PeppolParticipant::getOwnerType, "CUSTOMER")
                .eq(com.ses.entity.PeppolParticipant::getOwnerId, invoice.getCustomerId())
                .one();
        String peppolId = pp != null ? pp.getParticipantId() : "buyer-peppol-id";

        String orderReference = null;
        String contractReference = null;
        if (invoice.getRemarks() != null && invoice.getRemarks().startsWith("PO:")) {
            orderReference = invoice.getRemarks().substring(3).trim();
        }

        return CanonicalInvoice.builder()
                .invoiceNumber(invoice.getInvoiceNo())
                .issuedDate(invoice.getIssuedDate())
                .dueDate(invoice.getDueDate())
                .currency("JPY")
                .orderReference(orderReference)
                .contractReference(contractReference)
                .supplier(CanonicalInvoice.SupplierInfo.builder()
                        .corporateNumber("T1234567890123")
                        .name("SES Manager Pro Inc.")
                        .build())
                .customer(CanonicalInvoice.CustomerInfo.builder()
                        .peppolParticipantId(peppolId)
                        .name(customer != null ? customer.getCompanyName() : "Unknown Buyer")
                        .build())
                .taxExclusiveAmount(invoice.getSubtotal())
                .taxAmount(invoice.getTax())
                .taxInclusiveAmount(invoice.getTotal())
                .roundingAmount(BigDecimal.ZERO)
                .items(canonicalItems)
                .build();
    }

    private void archiveOutboundXml(DigitalInvoice di, String originalName, String xml, String businessKey) {
        com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                .documentType("INVOICE")
                .direction("OUTGOING")
                .sourceType("GENERATED")
                .businessKey(businessKey)
                .versionDiscriminator("1")
                .originalName(originalName)
                .contentType("application/xml")
                .build();
        try {
            com.ses.entity.Document docEntity = documentService.registerGenerated(
                    req, new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            di.setXmlDocumentId(docEntity.getId());
            if (!updateById(di)) {
                throw new BusinessException("ステータス更新の競合が発生しました。");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to archive outbound XML", e);
            throw new BusinessException("XMLのアーカイブに失敗しました。");
        }
    }

    private static String firstText(org.w3c.dom.Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private static String firstNestedText(org.w3c.dom.Document doc, String parentLocal, String childLocal) {
        NodeList parents = doc.getElementsByTagNameNS("*", parentLocal);
        for (int i = 0; i < parents.getLength(); i++) {
            org.w3c.dom.Node parent = parents.item(i);
            if (!(parent instanceof org.w3c.dom.Element el)) {
                continue;
            }
            NodeList children = el.getElementsByTagNameNS("*", childLocal);
            if (children.getLength() > 0) {
                return children.item(0).getTextContent();
            }
        }
        return null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
