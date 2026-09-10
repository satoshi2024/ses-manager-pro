package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.common.exception.SafeErrorPolicy;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.dto.invoice.CanonicalInvoice;
import com.ses.dto.invoice.InboundPurchaseRequest;
import com.ses.entity.Contract;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.entity.Invoice;
import com.ses.mapper.DigitalInvoiceMapper;
import com.ses.mapper.DigitalInvoiceEventMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.mapper.EngineerBpAffiliationMapper;
import com.ses.service.security.DataScopeService;
import com.ses.common.util.SecurityUtils;
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
import com.ses.service.invoice.provider.DigitalInvoiceProviderException;
import com.ses.service.invoice.provider.DigitalInvoiceProviderResponse;
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
import java.util.List;
import java.util.Collections;
import java.util.Objects;

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
    private final DigitalInvoiceEventMapper digitalInvoiceEventMapper;
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
    private final SalesOrderMapper salesOrderMapper;
    private final EngineerBpAffiliationMapper engineerBpAffiliationMapper;
    private final DataScopeService dataScopeService;

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<DigitalInvoice> searchInboundInvoices(long current, long size) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DigitalInvoice> requested =
                com.ses.common.util.PageUtils.safePage(current, size);
        LambdaQueryWrapper<DigitalInvoice> query = new LambdaQueryWrapper<DigitalInvoice>()
                .eq(DigitalInvoice::getDirection, "RECEIVE")
                .orderByDesc(DigitalInvoice::getReceivedAt);
        if (!dataScopeService.isScoped() || "管理者".equals(SecurityUtils.currentRole())) {
            return lambdaQuery().eq(DigitalInvoice::getDirection, "RECEIVE")
                    .orderByDesc(DigitalInvoice::getReceivedAt).page(requested);
        }
        // 関連先が複数テーブルに分散しているため、まず対象集合を取得してから
        // 同一のaccess predicateで絞り込み、ページ母集団自体を漏らさない。
        List<DigitalInvoice> visible = list(query).stream()
                .filter(this::isInboundAccessAllowed)
                .toList();
        long from = Math.min((requested.getCurrent() - 1) * requested.getSize(), visible.size());
        long to = Math.min(from + requested.getSize(), visible.size());
        requested.setTotal(visible.size());
        requested.setRecords(from >= to ? Collections.emptyList() : visible.subList((int) from, (int) to));
        return requested;
    }

    @Override
    public void assertInboundAccessAllowed(Long digitalInvoiceId) {
        DigitalInvoice invoice = digitalInvoiceId == null ? null : getById(digitalInvoiceId);
        if (invoice == null || !"RECEIVE".equals(invoice.getDirection())) {
            throw BusinessException.of(404, "error.invoice.notFound");
        }
        if ("管理者".equals(SecurityUtils.currentRole()) || !dataScopeService.isScoped()) {
            return;
        }
        if (!isInboundAccessAllowed(invoice)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    private boolean isInboundAccessAllowed(DigitalInvoice invoice) {
        if (invoice == null || !"RECEIVE".equals(invoice.getDirection())) {
            return false;
        }
        boolean hasResolvedOwner = false;
        boolean allowed = true;
        if (invoice.getContractId() != null) {
            hasResolvedOwner = true;
            allowed &= dataScopeService.allowedContractIds().contains(invoice.getContractId());
        }
        if (invoice.getInvoiceId() != null) {
            Invoice linked = invoiceService.getById(invoice.getInvoiceId());
            if (linked == null || linked.getCustomerId() == null) return false;
            hasResolvedOwner = true;
            allowed &= dataScopeService.allowedCustomerIds().contains(linked.getCustomerId());
        }
        if (invoice.getPurchaseOrderId() != null) {
            com.ses.entity.SalesOrder order = salesOrderMapper.selectById(invoice.getPurchaseOrderId());
            if (order == null || order.getCustomerId() == null) return false;
            hasResolvedOwner = true;
            allowed &= dataScopeService.allowedCustomerIds().contains(order.getCustomerId());
        }
        if (invoice.getSupplierCompanyId() != null) {
            List<com.ses.entity.EngineerBpAffiliation> affiliations = engineerBpAffiliationMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.EngineerBpAffiliation>()
                            .eq(com.ses.entity.EngineerBpAffiliation::getBpCompanyId, invoice.getSupplierCompanyId())
                            .isNull(com.ses.entity.EngineerBpAffiliation::getValidTo));
            if (affiliations.isEmpty()) return false;
            hasResolvedOwner = true;
            allowed &= affiliations.stream().map(com.ses.entity.EngineerBpAffiliation::getEngineerId)
                    .allMatch(dataScopeService.allowedEngineerIds()::contains);
        }
        // scope根拠が一つもない行は、supplier/PO/contractの未解決状態を含めて拒否する。
        return hasResolvedOwner && allowed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processProviderEvent(DigitalInvoiceEvent event) {
        if (ExecutionActorContext.current() == null
                || com.ses.common.audit.ActorType.HUMAN.equals(ExecutionActorContext.current().actorType())) {
            ExecutionActorContext.runAsProviderCallback(
                    CorrelationContext.get(CorrelationContext.CORRELATION_ID),
                    event == null ? null : event.getProviderEventId(),
                    () -> processProviderEvent(event));
            return;
        }
        if (event == null || event.getDigitalInvoiceId() == null) {
            throw new BusinessException(400, "error.invoice.webhookFailed");
        }
        String safeEventId = CorrelationContext.safeIdentifier(event.getProviderEventId());
        String eventType = event.getEventType() == null ? null : event.getEventType().toUpperCase(java.util.Locale.ROOT);
        if (safeEventId == null || eventType == null
                || !Set.of("QUEUED", "SENT", "DELIVERED", "REJECTED", "RECEIVED", "CANCELLED", "REVOKED")
                .contains(eventType)) {
            throw new BusinessException(400, "error.invoice.webhookFailed");
        }
        event.setProviderEventId(safeEventId);
        event.setEventType(eventType);
        event.setEventAt(event.getEventAt() == null ? LocalDateTime.now() : event.getEventAt());
        event.setPayloadHash(safePayloadHash(event.getPayloadHash()));
        applyActor(event);
        CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, event.getDigitalInvoiceId());
        CorrelationContext.put(CorrelationContext.PROVIDER_OPERATION_ID, safeEventId);
        if (!Boolean.TRUE.equals(event.getSignatureValid())) {
            digitalInvoiceEventService.save(event);
            return;
        }

        DigitalInvoiceEvent existingEvent = digitalInvoiceEventService.lambdaQuery()
                .eq(DigitalInvoiceEvent::getProviderEventId, event.getProviderEventId())
                .one();
        if (existingEvent != null) {
            if (!event.getDigitalInvoiceId().equals(existingEvent.getDigitalInvoiceId())
                    || !safePayloadHash(event.getPayloadHash()).equals(existingEvent.getPayloadHash())) {
                throw new BusinessException(409, "Webhookイベントの内容が既存イベントと一致しません。");
            }
            return;
        }
        try {
            digitalInvoiceEventService.save(event);
        } catch (DuplicateKeyException duplicate) {
            // UNIQUE(provider_event_id)を正本とし、同時再送は既存イベントへ収束する。
            DigitalInvoiceEvent winner = digitalInvoiceEventService.lambdaQuery()
                    .eq(DigitalInvoiceEvent::getProviderEventId, event.getProviderEventId()).one();
            if (winner != null && safePayloadHash(event.getPayloadHash()).equals(winner.getPayloadHash())) {
                return;
            }
            throw new BusinessException(409, "Webhookイベントの内容が既存イベントと一致しません。");
        }

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
    @Transactional(rollbackFor = Exception.class)
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
        ExecutionActorContext.runAsSystem(CorrelationContext.get(CorrelationContext.CORRELATION_ID),
                String.valueOf(jobId), () -> processSendJobInternal(jobId));
    }

    private void processSendJobInternal(Long jobId) {
        com.ses.entity.IntegrationJob job = integrationJobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        ExecutionActorContext.set(ActorAttribution.schedulerPoll(job.getCorrelationId(), String.valueOf(jobId)));
        CorrelationContext.beginJob(jobId, job.getCorrelationId());
        DigitalInvoice di = null;
        try {
            di = getById(job.getTargetId());
            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, job.getTargetId());
            if (di == null || !"QUEUED".equals(di.getStatus()) || !"SEND".equals(di.getDirection())) {
                integrationJobService.markFailed(jobId, "INVALID_STATE", "error.invoice.notFound");
                return;
            }
            // R5-P0-01: CreditNote を請求送信ジョブで処理しない
            if (!PROFILE_STANDARD.equals(di.getProfile())) {
                integrationJobService.markFailed(jobId, "WRONG_PROFILE", "error.invoice.dispatchFailed");
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

            String providerMessageId = CorrelationContext.safeIdentifier(di.getProviderMessageId());
            if (providerMessageId == null) {
                providerMessageId = CorrelationContext.safeIdentifier(job.getExternalId());
            }
            String providerRequestId = job.getProviderRequestId();
            String providerOperationId = job.getProviderOperationId();
            if (providerMessageId == null) {
                DigitalInvoiceProviderResponse response = sendToProvider(xml, di);
                providerMessageId = response.providerMessageId();
                providerRequestId = response.providerRequestId();
                providerOperationId = response.providerOperationId();
                CorrelationContext.put(CorrelationContext.PROVIDER_OPERATION_ID, providerOperationId);
                integrationJobService.recordProviderMetadata(jobId, providerMessageId, providerRequestId,
                        providerOperationId);
            }
            if (!providerMessageId.equals(CorrelationContext.safeIdentifier(di.getProviderMessageId()))
                    || !"SENT".equals(di.getStatus())) {
                di.setProviderMessageId(providerMessageId);
                di.setStatus("SENT");
                di.setSentAt(LocalDateTime.now());
                if (!updateById(di)) {
                    throw new BusinessException(500, "error.invoice.localStateUpdateFailed");
                }
            }

            integrationJobService.markSucceededWithProviderMetadata(jobId, providerMessageId, providerRequestId,
                    providerOperationId, "デジタルインボイスを送信しました。");
        } catch (DigitalInvoiceProviderException e) {
            // provider_message_idが得られていない障害ではexternal_idへ対象IDを代入しない。
            // 対象IDを外部IDと誤認すると、次回リトライが送信済みと誤判定される。
            integrationJobService.recordProviderMetadata(jobId, null,
                    e.getProviderRequestId(), e.getProviderOperationId());
            log.warn("電子請求書プロバイダ障害: jobId={} digitalInvoiceId={} category=SYSTEM errorCode={} httpStatus={} providerCode={} providerRequestId={} providerOperationId={} exceptionClass={}",
                    jobId, job.getTargetId(), "PROVIDER_UNAVAILABLE", e.getHttpStatus(), e.getProviderCode(),
                    e.getProviderRequestId(), e.getProviderOperationId(), LogRedaction.exceptionType(e));
            integrationJobService.markRetryable(jobId, "PROVIDER_UNAVAILABLE", "error.invoice.dispatchFailed", 300);
        } catch (BusinessException e) {
            String errorCode = e.getCode() >= 500 ? "LOCAL_STATE_UPDATE_FAILED" : "VALIDATION_FAILED";
            log.warn("電子請求書送信ジョブのエラー: jobId={} digitalInvoiceId={} invoiceId={} category={} errorCode={} exceptionClass={} detail={}",
                    jobId, job.getTargetId(), (di != null ? di.getInvoiceId() : null),
                    e.getCode() >= 500 ? "SYSTEM" : "BUSINESS", errorCode,
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            if (e.getCode() >= 500) {
                integrationJobService.markRetryable(jobId, "LOCAL_STATE_UPDATE_FAILED", "error.invoice.localStateUpdateFailed", 300);
            } else {
                integrationJobService.markFailed(jobId, "VALIDATION_FAILED", safeJobErrorMessage(e));
            }
        } catch (Exception e) {
            log.warn("電子請求書送信ジョブのシステムエラー: jobId={} digitalInvoiceId={} invoiceId={} category=SYSTEM errorCode={} exceptionClass={} detail={}",
                    jobId, job.getTargetId(), (di != null ? di.getInvoiceId() : null), "SEND_ERROR",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            integrationJobService.markRetryable(jobId, "SEND_ERROR", "error.invoice.dispatchFailed", 300);
        } finally {
            CorrelationContext.clear();
        }
    }

    @Override
    public void processCreditNoteJob(Long jobId) {
        ExecutionActorContext.runAsSystem(CorrelationContext.get(CorrelationContext.CORRELATION_ID),
                String.valueOf(jobId), () -> processCreditNoteJobInternal(jobId));
    }

    private void processCreditNoteJobInternal(Long jobId) {
        com.ses.entity.IntegrationJob job = integrationJobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        ExecutionActorContext.set(ActorAttribution.schedulerPoll(job.getCorrelationId(), String.valueOf(jobId)));
        CorrelationContext.beginJob(jobId, job.getCorrelationId());
        DigitalInvoice cn = null;
        try {
            cn = getById(job.getTargetId());
            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, job.getTargetId());
            if (cn == null || !"QUEUED".equals(cn.getStatus()) || !"SEND".equals(cn.getDirection())) {
                integrationJobService.markFailed(jobId, "INVALID_STATE", "error.invoice.notFound");
                return;
            }
            if (!PROFILE_CREDIT_NOTE.equals(cn.getProfile())) {
                integrationJobService.markFailed(jobId, "WRONG_PROFILE", "error.invoice.dispatchFailed");
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
                throw new BusinessException("CreditNote XMLの生成に失敗しました。");
            }

            if (cn.getXmlDocumentId() == null) {
                archiveOutboundXml(cn, invoice.getInvoiceNo() + "_creditnote.xml", xml, "DIGITAL_INVOICE_CREDIT_NOTE:" + cn.getId());
            }

            String providerMessageId = CorrelationContext.safeIdentifier(cn.getProviderMessageId());
            if (providerMessageId == null) {
                providerMessageId = CorrelationContext.safeIdentifier(job.getExternalId());
            }
            String providerRequestId = job.getProviderRequestId();
            String providerOperationId = job.getProviderOperationId();
            if (providerMessageId == null) {
                DigitalInvoiceProviderResponse response = sendToProvider(xml, cn);
                providerMessageId = response.providerMessageId();
                providerRequestId = response.providerRequestId();
                providerOperationId = response.providerOperationId();
                CorrelationContext.put(CorrelationContext.PROVIDER_OPERATION_ID, providerOperationId);
                integrationJobService.recordProviderMetadata(jobId, providerMessageId, providerRequestId,
                        providerOperationId);
            }
            if (!providerMessageId.equals(CorrelationContext.safeIdentifier(cn.getProviderMessageId()))
                    || !"SENT".equals(cn.getStatus())) {
                cn.setProviderMessageId(providerMessageId);
                cn.setStatus("SENT");
                cn.setSentAt(LocalDateTime.now());
                if (!updateById(cn)) {
                    throw new BusinessException(500, "error.invoice.localStateUpdateFailed");
                }
            }

            integrationJobService.markSucceededWithProviderMetadata(jobId, providerMessageId, providerRequestId,
                    providerOperationId, "打消し電文を送信しました。");
        } catch (DigitalInvoiceProviderException e) {
            // provider_message_idが得られていない障害ではexternal_idへ対象IDを代入しない。
            integrationJobService.recordProviderMetadata(jobId, null,
                    e.getProviderRequestId(), e.getProviderOperationId());
            log.warn("CreditNoteプロバイダ障害: jobId={} digitalInvoiceId={} category=SYSTEM errorCode={} httpStatus={} providerCode={} providerRequestId={} providerOperationId={} exceptionClass={}",
                    jobId, job.getTargetId(), "PROVIDER_UNAVAILABLE", e.getHttpStatus(), e.getProviderCode(),
                    e.getProviderRequestId(), e.getProviderOperationId(), LogRedaction.exceptionType(e));
            integrationJobService.markRetryable(jobId, "PROVIDER_UNAVAILABLE", "error.invoice.dispatchFailed", 300);
        } catch (BusinessException e) {
            String errorCode = e.getCode() >= 500 ? "LOCAL_STATE_UPDATE_FAILED" : "VALIDATION_FAILED";
            log.warn("CreditNote送信ジョブのエラー: jobId={} digitalInvoiceId={} invoiceId={} category={} errorCode={} exceptionClass={} detail={}",
                    jobId, job.getTargetId(), (cn != null ? cn.getInvoiceId() : null),
                    e.getCode() >= 500 ? "SYSTEM" : "BUSINESS", errorCode,
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            if (e.getCode() >= 500) {
                integrationJobService.markRetryable(jobId, "LOCAL_STATE_UPDATE_FAILED", "error.invoice.localStateUpdateFailed", 300);
            } else {
                integrationJobService.markFailed(jobId, "VALIDATION_FAILED", safeJobErrorMessage(e));
            }
        } catch (Exception e) {
            log.warn("CreditNote送信ジョブのシステムエラー: jobId={} digitalInvoiceId={} invoiceId={} category=SYSTEM errorCode={} exceptionClass={} detail={}",
                    jobId, job.getTargetId(), (cn != null ? cn.getInvoiceId() : null), "SEND_ERROR",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            integrationJobService.markRetryable(jobId, "SEND_ERROR", "error.invoice.dispatchFailed", 300);
        } finally {
            CorrelationContext.clear();
        }
    }

    /**
     * ジョブの errorMessage には i18n キーまたは固定の業務文言のみを残す。
     * パスワード/トークン/メール/SQL/ドライバ/スタック原文など技術詳細・機密は保存しない。
     */
    private DigitalInvoiceProviderResponse sendToProvider(String xml, DigitalInvoice invoice) {
        DigitalInvoiceProviderResponse response;
        try {
            response = provider.sendInvoiceWithMetadata(
                    xml, invoice.getSpecificationVersion(), invoice.getMessageId());
            if (response == null) {
                response = DigitalInvoiceProviderResponse.success(
                        provider.sendInvoice(xml, invoice.getSpecificationVersion(), invoice.getMessageId()));
            }
        } catch (DigitalInvoiceProviderException e) {
            throw e;
        } catch (BusinessException e) {
            Integer httpStatus = e.getCode() >= 100 && e.getCode() <= 599 ? e.getCode() : null;
            throw new DigitalInvoiceProviderException(httpStatus, null, null, null);
        } catch (Exception e) {
            throw new DigitalInvoiceProviderException(null, null, null, null);
        }
        String providerMessageId = CorrelationContext.safeIdentifier(response.providerMessageId());
        String providerRequestId = CorrelationContext.safeIdentifier(response.providerRequestId());
        String providerOperationId = CorrelationContext.safeIdentifier(response.providerOperationId());
        String providerCode = safeProviderCode(response.providerCode());
        Integer httpStatus = response.httpStatus() != null && response.httpStatus() >= 100 && response.httpStatus() <= 599
                ? response.httpStatus() : null;
        log.info("電子請求書プロバイダ応答: digitalInvoiceId={} httpStatus={} providerCode={} providerRequestId={} providerOperationId={}",
                invoice.getId(), httpStatus, providerCode, providerRequestId, providerOperationId);
        if (providerMessageId == null) {
            throw new DigitalInvoiceProviderException(httpStatus, providerCode, providerRequestId, providerOperationId);
        }
        return new DigitalInvoiceProviderResponse(providerMessageId, providerOperationId, providerRequestId,
                httpStatus, providerCode);
    }

    private String safeProviderCode(String value) {
        return value != null && value.length() <= 64
                && value.matches("[A-Za-z0-9._:-]{1,64}") ? value : null;
    }

    private static String safePayloadHash(String value) {
        if (value != null && value.matches("[0-9A-Fa-f]{64}")) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        if (value == null || value.length() > 8192) {
            return DigestUtils.sha256Hex("invalid-payload-hash");
        }
        return DigestUtils.sha256Hex(value);
    }

    public static String safeJobErrorMessage(BusinessException e) {
        return SafeErrorPolicy.safeBusinessJobMessage(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelInvoice(Long digitalInvoiceId) {
        DigitalInvoice di = getById(digitalInvoiceId);
        if (di == null) {
            throw new BusinessException("対象のインボイスが見つかりません。");
        }
        if (!"SEND".equals(di.getDirection())) {
            throw new BusinessException("error.invoice.cancelFailed");
        }
        if ("CANCELLED".equals(di.getStatus()) || "REVOKED".equals(di.getStatus())) {
            return;
        }

        if ("QUEUED".equals(di.getStatus()) || "FAILED".equals(di.getStatus())) {
            di.setStatus("CANCELLED");
            if (!updateById(di)) {
                throw new BusinessException("ステータス更新の競合が発生しました。");
            }
            return;
        }

        di.setStatus("REVOKED");
        if (!updateById(di)) {
            throw new BusinessException("ステータス更新の競合が発生しました。");
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
    @Transactional(rollbackFor = Exception.class)
    public void processInboundInvoice(String providerMessageId, String eventId, String xmlContent,
                                      String rawPayloadHash, LocalDateTime eventAt) {
        if (ExecutionActorContext.current() == null
                || com.ses.common.audit.ActorType.HUMAN.equals(ExecutionActorContext.current().actorType())) {
            ExecutionActorContext.runAsProviderCallback(
                    CorrelationContext.get(CorrelationContext.CORRELATION_ID), eventId,
                    () -> processInboundInvoice(providerMessageId, eventId, xmlContent, rawPayloadHash, eventAt));
            return;
        }
        String safeProviderMessageId = CorrelationContext.safeIdentifier(providerMessageId);
        String safeEventId = CorrelationContext.safeIdentifier(eventId);
        if (safeProviderMessageId == null || safeEventId == null) {
            throw new BusinessException(400, "error.invoice.webhookFailed");
        }
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new BusinessException(400, "error.invoice.webhookFailed");
        }
        String safePayloadHash = safePayloadHash(rawPayloadHash);
        CorrelationContext.put(CorrelationContext.PROVIDER_OPERATION_ID, safeEventId);

        // 受信XMLは保存・冪等判定より先に実際のDOMへ変換し、正規化シリアライズのhashを固定する。
        org.w3c.dom.Document parsed;
        String canonicalPayloadHash;
        String invoiceNo;
        LocalDate issueDate;
        try {
            parsed = renderer.parseSecurely(xmlContent);
            canonicalPayloadHash = DigestUtils.sha256Hex(renderer.canonicalize(parsed));
            invoiceNo = firstText(parsed, "ID");
            issueDate = parseDate(firstText(parsed, "IssueDate"));
            if (invoiceNo == null || invoiceNo.isBlank()) {
                invoiceNo = "MSG-" + safeProviderMessageId;
            }
        } catch (Exception e) {
            log.warn("受信XMLの正規化・照合に失敗: providerMessageId={} eventId={} category=BUSINESS errorCode={} exceptionClass={} detail={}",
                    safeProviderMessageId, safeEventId, "INBOUND_PARSE_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            throw new BusinessException(400, "error.invoice.webhookFailed", e);
        }

        DigitalInvoice existingMessage = lambdaQuery()
                .eq(DigitalInvoice::getProviderMessageId, safeProviderMessageId).one();
        if (existingMessage != null) {
            assertInboundReplayCompatible(existingMessage, safeEventId, safePayloadHash, canonicalPayloadHash);
            return;
        }

        // 別providerMessageIdでも同一messageIdを再送した場合は、実XMLのhashが一致するときだけ収束させる。
        DigitalInvoice existingMessageId = lambdaQuery()
                .eq(DigitalInvoice::getMessageId, invoiceNo)
                .eq(DigitalInvoice::getDirection, "RECEIVE").one();
        if (existingMessageId != null) {
            DigitalInvoiceEvent existingEvent = findLatestInboundEvent(existingMessageId.getId());
            if (existingEvent == null || !safePayloadHash.equals(existingEvent.getPayloadHash())
                    || !canonicalPayloadHash.equals(existingEvent.getCanonicalPayloadHash())) {
                throw new BusinessException(409, "受信電文の内容が既存電文と一致しません。");
            }
            return;
        }

        DigitalInvoice di = new DigitalInvoice();
        di.setDirection("RECEIVE");
        di.setProviderMessageId(safeProviderMessageId);
        di.setSpecificationVersion("1.1.3");
        di.setProfile(PROFILE_STANDARD);
        di.setMessageId(invoiceNo);
        applyActor(di);

        if (parsed != null) {
            try {
                applyInboundMatch(di, parsed);
            } catch (Exception e) {
                di.setStatus("REJECTED_AUTO");
                di.setMatchStatus("UNMATCHED");
            }
        }

        di.setReceivedAt(eventAt != null ? eventAt : LocalDateTime.now());
        try {
            // 業務行を先にinsertする。後続のarchive/eventが失敗すれば同一TXで
            // DigitalInvoiceとDocumentをまとめてrollbackし、孤児archiveを残さない。
            save(di);
        } catch (DuplicateKeyException duplicate) {
            // provider_message_id/message_idのDB UNIQUEを最終的な同時実行判定にする。
            DigitalInvoice winner = baseMapper.selectByProviderMessageIdForUpdate(safeProviderMessageId);
            if (winner != null) {
                assertInboundReplayCompatible(winner, safeEventId, safePayloadHash, canonicalPayloadHash);
                return;
            }
            DigitalInvoice messageWinner = baseMapper.selectInboundByMessageIdForUpdate(invoiceNo);
            if (messageWinner != null && sameInboundPayload(messageWinner, safePayloadHash, canonicalPayloadHash)) {
                return;
            }
            throw new BusinessException(409, "受信電文の一意制約競合を検証できません。", duplicate);
        }

        try {
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("INVOICE_IN")
                    .direction("INCOMING")
                    .sourceType("RECEIVED")
                    .businessKey("DIGITAL_INVOICE:" + safeProviderMessageId)
                    .versionDiscriminator("1")
                    .originalName(safeProviderMessageId + ".xml")
                    .contentType("application/xml")
                    .transactionDate(issueDate)
                    .targetType(inboundDocumentTargetType(di))
                    .targetId(inboundDocumentTargetId(di))
                    .actorType(com.ses.common.audit.ActorType.PROVIDER)
                    .confirmationSource(com.ses.common.audit.ConfirmationSource.PROVIDER_CALLBACK)
                    .correlationId(CorrelationContext.get(CorrelationContext.CORRELATION_ID))
                    .idempotencyKey(safeEventId)
                    .build();
            com.ses.entity.Document docEntity = documentService.registerReceived(
                    req, new java.io.ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
            di.setXmlDocumentId(docEntity.getId());
        } catch (Exception e) {
            log.error("受信XMLのアーカイブに失敗: providerMessageId={} eventId={} category=SYSTEM errorCode={} exceptionClass={} detail={}",
                    safeProviderMessageId, safeEventId, "ARCHIVE_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            throw new BusinessException(500, "XMLのアーカイブに失敗しました。", e);
        }
        if (!updateById(di)) {
            throw new BusinessException(409, "受信電子請求書の更新競合が発生しました。");
        }

        DigitalInvoiceEvent event = new DigitalInvoiceEvent();
        event.setDigitalInvoiceId(di.getId());
        event.setProviderEventId(safeEventId);
        event.setEventType("RECEIVED");
        event.setEventAt(eventAt != null ? eventAt : LocalDateTime.now());
        event.setPayloadHash(safePayloadHash);
        event.setCanonicalPayloadHash(canonicalPayloadHash);
        event.setSignatureValid(true);
        applyActor(event);
        try {
            digitalInvoiceEventService.save(event);
        } catch (DuplicateKeyException duplicate) {
            DigitalInvoiceEvent existing = digitalInvoiceEventService.lambdaQuery()
                    .eq(DigitalInvoiceEvent::getProviderEventId, safeEventId).one();
            if (existing == null || !di.getId().equals(existing.getDigitalInvoiceId())
                    || !safePayloadHash.equals(existing.getPayloadHash())
                    || !canonicalPayloadHash.equals(existing.getCanonicalPayloadHash())) {
                throw new BusinessException(409, "Webhookイベントの内容が既存イベントと一致しません。", duplicate);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundPurchaseRequest acceptInboundReview(Long digitalInvoiceId) {
        assertInboundAccessAllowed(digitalInvoiceId);
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
                log.warn("ACCEPT時のXML再読取に失敗: digitalInvoiceId={} xmlDocumentId={} category=SYSTEM errorCode={} exceptionClass={} detail={}",
                        digitalInvoiceId, di.getXmlDocumentId(), "ACCEPT_XML_READ_FAILED",
                        LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
                throw new BusinessException(500, "error.invoice.acceptFailed");
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

    private void assertInboundReplayCompatible(DigitalInvoice invoice, String eventId,
                                                String payloadHash, String canonicalPayloadHash) {
        if (invoice == null || !"RECEIVE".equals(invoice.getDirection())) {
            throw new BusinessException(409, "受信電文の方向が既存電文と一致しません。");
        }
        // 先行するconsistent readで作られたREPEATABLE READのsnapshotに閉じ込めず、
        // 一意制約競合後の勝者イベントを現在値として読み取る。
        DigitalInvoiceEvent existing = digitalInvoiceEventMapper
                .selectByInvoiceIdAndProviderEventIdForUpdate(invoice.getId(), eventId);
        if (!sameInboundEvent(existing, payloadHash, canonicalPayloadHash)) {
            log.warn("受信再送の整合性検証失敗: digitalInvoiceId={} providerEventId={} existingEventId={} "
                            + "existingEventType={} payloadHashMatch={} canonicalPayloadHashMatch={} category=BUSINESS errorCode=INBOUND_REPLAY_CONFLICT",
                    invoice.getId(), CorrelationContext.safeIdentifier(eventId), existing == null ? null : existing.getId(),
                    existing == null ? null : existing.getEventType(),
                    existing != null && Objects.equals(payloadHash, existing.getPayloadHash()),
                    existing != null && Objects.equals(canonicalPayloadHash, existing.getCanonicalPayloadHash()));
            throw new BusinessException(409, "受信電文のイベントIDまたは内容が既存電文と一致しません。");
        }
    }

    private boolean sameInboundPayload(DigitalInvoice invoice, String payloadHash, String canonicalPayloadHash) {
        DigitalInvoiceEvent existing = findLatestInboundEvent(invoice == null ? null : invoice.getId());
        return sameInboundEvent(existing, payloadHash, canonicalPayloadHash);
    }

    private boolean sameInboundEvent(DigitalInvoiceEvent event, String payloadHash, String canonicalPayloadHash) {
        return event != null
                && "RECEIVED".equals(event.getEventType())
                && Objects.equals(payloadHash, event.getPayloadHash())
                && Objects.equals(canonicalPayloadHash, event.getCanonicalPayloadHash());
    }

    private DigitalInvoiceEvent findLatestInboundEvent(Long digitalInvoiceId) {
        if (digitalInvoiceId == null) {
            return null;
        }
        return digitalInvoiceEventMapper.selectLatestInboundForUpdate(digitalInvoiceId);
    }

    /** 仕入候補の受け渡し境界。支払確定ジョブは起動しない。 */
    void handoffInboundPurchaseCandidate(InboundPurchaseRequest request) {
        log.info("受信仕入候補を会計正規処理へ引き渡し: digitalInvoiceId={} supplierCompanyId={} amount={} issueDate={} purchaseOrderId={} contractId={}",
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

    private String inboundDocumentTargetType(DigitalInvoice invoice) {
        if (invoice.getContractId() != null) return "CONTRACT";
        if (invoice.getPurchaseOrderId() != null) return "SALES_ORDER";
        if (invoice.getInvoiceId() != null) return "CUSTOMER";
        if (invoice.getSupplierCompanyId() != null) {
            List<com.ses.entity.EngineerBpAffiliation> affiliations = engineerBpAffiliationMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.EngineerBpAffiliation>()
                            .eq(com.ses.entity.EngineerBpAffiliation::getBpCompanyId, invoice.getSupplierCompanyId())
                            .isNull(com.ses.entity.EngineerBpAffiliation::getValidTo));
            if (!affiliations.isEmpty()) return "ENGINEER";
        }
        return null;
    }

    private Long inboundDocumentTargetId(DigitalInvoice invoice) {
        if (invoice.getContractId() != null) return invoice.getContractId();
        if (invoice.getPurchaseOrderId() != null) return invoice.getPurchaseOrderId();
        if (invoice.getInvoiceId() != null) {
            Invoice linked = invoiceService.getById(invoice.getInvoiceId());
            return linked == null ? null : linked.getCustomerId();
        }
        if (invoice.getSupplierCompanyId() != null) {
            com.ses.entity.EngineerBpAffiliation affiliation = engineerBpAffiliationMapper.selectOne(
                    new LambdaQueryWrapper<com.ses.entity.EngineerBpAffiliation>()
                            .eq(com.ses.entity.EngineerBpAffiliation::getBpCompanyId, invoice.getSupplierCompanyId())
                            .isNull(com.ses.entity.EngineerBpAffiliation::getValidTo)
                            .last("LIMIT 1"));
            return affiliation == null ? null : affiliation.getEngineerId();
        }
        return null;
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
                .invoiceId(invoice.getId())
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
        Invoice invoice = di.getInvoiceId() == null ? null : invoiceService.getById(di.getInvoiceId());
        ActorAttribution actor = ExecutionActorContext.resolve();
        com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                .documentType("INVOICE_OUT")
                .direction("OUTGOING")
                .sourceType("GENERATED")
                .businessKey(businessKey)
                .versionDiscriminator("1")
                .originalName(originalName)
                .contentType("application/xml")
                .transactionDate(invoice == null ? null : invoice.getIssuedDate())
                .actorType(actor.actorType())
                .confirmationSource(actor.confirmationSource())
                .humanUserId(actor.humanUserId())
                .createdBy(actor.humanUserId())
                .correlationId(actor.correlationId())
                .idempotencyKey(businessKey)
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
            log.error("送信XMLのアーカイブに失敗: digitalInvoiceId={} invoiceId={} category=SYSTEM errorCode={} exceptionClass={} detail={}",
                    di.getId(), di.getInvoiceId(), "ARCHIVE_FAILED", LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            throw new BusinessException(500, "XMLのアーカイブに失敗しました。");
        }
    }

    private void applyActor(DigitalInvoice invoice) {
        ActorAttribution actor = ExecutionActorContext.resolve();
        invoice.setActorType(actor.actorType().name());
        invoice.setConfirmationSource(actor.confirmationSource().name());
        invoice.setHumanUserId(actor.humanUserId());
        invoice.setCorrelationId(actor.correlationId());
        invoice.setIdempotencyKey(actor.idempotencyKey());
    }

    private void applyActor(DigitalInvoiceEvent event) {
        ActorAttribution actor = ExecutionActorContext.resolve();
        event.setActorType(actor.actorType().name());
        event.setConfirmationSource(actor.confirmationSource().name());
        event.setHumanUserId(actor.humanUserId());
        event.setCorrelationId(actor.correlationId());
        event.setIdempotencyKey(actor.idempotencyKey());
    }

    private static String firstText(org.w3c.dom.Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName(localName);
        }
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
