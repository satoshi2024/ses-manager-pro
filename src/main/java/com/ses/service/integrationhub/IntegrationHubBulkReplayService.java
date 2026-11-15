package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AuditLog;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 条件付きの有界replay。要求監査を先にcommitし、TLSと各generationのtransactionを分離する。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubBulkReplayService {
    public static final int MAX_ITEMS = 20;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntegrationHubBulkReplayService.class);
    private final ApiDeliveryMapper deliveries;
    private final WebhookSubscriptionMapper subscriptions;
    private final IntegrationHubManagementService management;
    private final AuditLogService audit;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    private final ObjectMapper json;
    private java.util.function.LongSupplier nanos = System::nanoTime;

    public Result replay(Command command, Authentication authentication) {
        String tenant = management.requireManagementTenant();
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResourceMap().values().stream()
                        .anyMatch(value -> value instanceof org.springframework.jdbc.datasource.ConnectionHolder))
            throw new IllegalStateException("bulk replayはtransaction外で実行してください");
        if (command == null || !tenant.equals(command.tenantId()) || command.subscriptionId() == null || command.subscriptionId() < 1
                || command.legalEntityId() == null || command.from() == null || command.until() == null
                || !command.from().isBefore(command.until()) || Duration.between(command.from(), command.until()).compareTo(Duration.ofDays(31)) > 0
                || command.failureClass() == null || command.limit() < 1 || command.limit() > MAX_ITEMS
                || command.reasonCode() == null || !command.reasonCode().matches("[A-Z][A-Z0-9_]{0,63}")) invalid();
        var subscription = subscriptions.selectActiveById(command.subscriptionId());
        if (subscription == null || !"OUTBOUND".equals(subscription.getDirection())) invalid();
        management.assertDeliveryScope(subscription.getClientId(), command.legalEntityId());
        var query = new QueryWrapper<ApiDelivery>().eq("tenant_id", tenant).eq("client_id", subscription.getClientId())
                .eq("subscription_id", command.subscriptionId()).eq("status", IntegrationHubStates.DELIVERY_DLQ)
                .ge("created_at", LocalDateTime.ofInstant(command.from(), ZoneOffset.UTC))
                .lt("created_at", LocalDateTime.ofInstant(command.until(), ZoneOffset.UTC))
                .notExists("SELECT 1 FROM t_api_delivery newer WHERE newer.event_id=t_api_delivery.event_id "
                        + "AND newer.subscription_id=t_api_delivery.subscription_id AND newer.delivery_generation>t_api_delivery.delivery_generation")
                .notExists("SELECT 1 FROM t_api_delivery_replay_audit a WHERE a.delivery_id=t_api_delivery.id "
                        + "AND a.replay_generation=t_api_delivery.delivery_generation+1")
                .orderByAsc("id").last("LIMIT " + command.limit());
        if (command.failureClass() == FailureClass.TLS_CERTIFICATE)
            query.in("last_error_code", List.of("TLS_CERTIFICATE_EXPIRED", "TLS_CERTIFICATE_NOT_YET_VALID", "TLS_CERTIFICATE_REVOKED",
                    "TLS_CERTIFICATE_UNTRUSTED", "TLS_CERTIFICATE_INVALID"));
        else query.eq("last_error_code", command.failureClass().name());
        List<ApiDelivery> selected = deliveries.selectList(query);
        if (selected.size() > command.limit()) throw new IllegalStateException("bulk replayの検索上限が一致しません");
        String request = UUID.randomUUID().toString();
        requiredAudit("NF05_BULK_REPLAY_REQUEST", command.subscriptionId(), request,
                Map.of("tenant", tenant, "subscription", command.subscriptionId(), "from", command.from().toString(),
                        "until", command.until().toString(), "failureClass", command.failureClass().name(), "reason", command.reasonCode(),
                        "limit", command.limit(), "sources", selected.stream().map(ApiDelivery::getId).toList()));
        long deadline = nanos.getAsLong() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        List<Item> items = new ArrayList<>();
        for (ApiDelivery row : selected) {
            if (nanos.getAsLong() >= deadline) break;
            if (!tenant.equals(row.getTenantId()) || !subscription.getClientId().equals(row.getClientId())
                    || !command.subscriptionId().equals(row.getSubscriptionId()))
                throw new SecurityException("bulk replayのscopeが一致しません");
            try {
                Long next = management.replay(row.getId(), command.legalEntityId(), command.reasonCode(), authentication);
                items.add(new Item(row.getId(), next, "REPLAYED"));
            } catch (RuntimeException rejected) {
                // 各成功はrequired replay auditと同じtransactionでcommit済み。既成功をrollbackしたように返さない。
                items.add(new Item(row.getId(), null, "REJECTED"));
            }
        }
        int succeeded = (int) items.stream().filter(item -> "REPLAYED".equals(item.outcome())).count();
        int deferred = selected.size() - items.size();
        boolean completionRecorded = true;
        try {
            requiredAudit("NF05_BULK_REPLAY_COMPLETED", command.subscriptionId(), request,
                    Map.of("succeeded", succeeded, "rejected", items.size() - succeeded, "deferred", deferred));
        } catch (RuntimeException auditFailure) {
            completionRecorded = false;
            log.warn("NF05 bulk replayの完了監査を保存できません。要求監査と各replay監査を照合してください");
        }
        return new Result(request, selected.size(), succeeded, items.size() - succeeded, deferred, completionRecorded, List.copyOf(items));
    }

    private void requiredAudit(String code, Long subscription, String request, Map<String, Object> metadata) {
        TransactionTemplate transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
        transactions.executeWithoutResult(tx -> {
            AuditLog entry = new AuditLog();
            entry.setUsername("sys-user:" + SecurityUtils.currentUserId()); entry.setMethod("POST");
            entry.setUri("/api/integration-hub/deliveries/bulk-replay"); entry.setStatus(200);
            entry.setApplicationCode(code); entry.setSuccessFlag(true); entry.setReferenceType("NF05"); entry.setReferenceId(subscription);
            entry.setActorType("HUMAN"); entry.setConfirmationSource("MANUAL_API"); entry.setHumanUserId(SecurityUtils.currentUserId());
            entry.setCorrelationId(request); entry.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0));
            try { entry.setAfterState(json.writeValueAsString(metadata)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("NF05監査を構築できません"); }
            audit.recordDomainEventRequired(entry);
        });
    }
    private static void invalid() { throw BusinessException.of(400, "integration.hub.management.invalid"); }
    public enum FailureClass { STARTUP_REVALIDATION_REQUIRED, TLS_CERTIFICATE, TLS_IDENTITY_REJECTED,
        PEER_REJECTED, DESTINATION_REJECTED, UNSENT_AGE_EXCEEDED, MAX_ATTEMPTS }
    public record Command(String tenantId, Long subscriptionId, Long legalEntityId, Instant from, Instant until,
                          FailureClass failureClass, String reasonCode, int limit) { }
    public record Item(Long sourceDeliveryId, Long replayDeliveryId, String outcome) { }
    public record Result(String requestId, int selected, int succeeded, int rejected, int deferred,
                         boolean completionAuditRecorded, List<Item> items) { }
}
