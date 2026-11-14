package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.*;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.entity.integrationhub.*;
import com.ses.mapper.*;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 登録済みresource.changedを業務更新と同じtransactionでenqueueする。自由文・内部IDはpayloadに出さない。 */
@Service
@RequiredArgsConstructor
public class IntegrationHubResourceChangedProducer {
    private final WebhookSubscriptionMapper subscriptions;
    private final ApiClientMapper clients;
    private final ApiClientScopeMapper permissions;
    private final ExternalApiReadMapper resources;
    private final ExternalApiPublicIdCodec publicIds;
    private final IntegrationHubExternalApiProperties properties;
    private final ApiDeliveryService deliveries;
    private final ObjectMapper json;
    private final Clock clock;
    private final IntegrationHubWebhookResourceScopeMapper ownership;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void changed(String type, Long id) {
        if (id == null) throw new IllegalArgumentException("resource IDが必要です");
        String tenant = AccountingTenantContextHolder.requireTenantContext();
        properties.assertTenantBound(tenant);
        List<WebhookSubscription> targets = subscriptions.selectOutboundForTenant(tenant);
        if (targets.size() > 256) throw new IllegalStateException("同一更新のsubscription数が上限を超えています");
        String dimension = switch (type) {
            case "engineer-availability" -> "engineerIds";
            case "project" -> "projectIds";
            case "contract-status" -> "contractIds";
            case "invoice-status" -> "invoiceIds";
            default -> throw new IllegalArgumentException("未登録のresourceです");
        };
        String scopeCode = switch (type) {
            case "engineer-availability" -> ExternalApiRouteCatalog.ENGINEER_AVAILABILITY_SCOPE;
            case "project" -> ExternalApiRouteCatalog.PROJECT_SCOPE;
            case "contract-status" -> ExternalApiRouteCatalog.CONTRACT_STATUS_SCOPE;
            default -> ExternalApiRouteCatalog.INVOICE_STATUS_SCOPE;
        };
        for (WebhookSubscription subscription : targets) {
            ApiClient client = clients.selectByClientId(subscription.getClientId());
            if (client == null || !"ACTIVE".equals(client.getStatus()) || client.getRevokedAt() != null || client.getLegalEntityId() == null || !tenant.equals(client.getTenantId())) continue;
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!ownership.isCurrentOwnership(type, id, tenant, client.getLegalEntityId())) continue;
            if (client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now)) continue;
            ApiClientScope permission = permissions.selectActive(client.getId(), scopeCode, "integration.webhook.deliver");
            if (permission == null) continue;
            ExternalApiDataScope scope = ExternalApiDataScope.parse(client.getDataScopeJson(), json)
                    .intersect(ExternalApiDataScope.parse(permission.getDataScopeJson(), json))
                    .intersect(ExternalApiDataScope.parse(subscription.getDataScopeJson(), json));
            Set<String> declared = new HashSet<>(ExternalApiDataScope.parse(client.getDataScopeJson(), json).values().keySet());
            declared.addAll(ExternalApiDataScope.parse(permission.getDataScopeJson(), json).values().keySet());
            declared.addAll(ExternalApiDataScope.parse(subscription.getDataScopeJson(), json).values().keySet());
            if (!scope.values().keySet().containsAll(declared)
                    || scope.values().values().stream().anyMatch(Set::isEmpty)) continue;
            if (!Set.of(tenant).equals(scope.values().get("tenantIds"))
                    || !Set.of(client.getLegalEntityId().toString()).equals(scope.values().get("legalEntityIds"))
                    || !scope.values().getOrDefault(dimension, Set.of()).contains(id.toString())) continue;
            List<Long> customerIds = numbers(scope.values().get("customerIds"));
            List<Long> contractIds = numbers(scope.values().get("contractIds"));
            List<ExternalApiReadRow> rows = switch (type) {
                case "engineer-availability" -> resources.selectEngineers(List.of(id), null, 1, client.getLegalEntityId());
                case "project" -> resources.selectProjects(List.of(id), customerIds, null, 1, client.getLegalEntityId());
                case "contract-status" -> resources.selectContracts(List.of(id), numbers(scope.values().get("projectIds")), null, 1, client.getLegalEntityId());
                case "invoice-status" -> resources.selectInvoices(List.of(id), contractIds, customerIds,
                        null, 1, client.getLegalEntityId());
                default -> List.of();
            };
            if (rows.size() != 1) continue;
            ExternalApiPrincipal principal = new ExternalApiPrincipal(client.getClientId(), client.getId(), tenant,
                    client.getLegalEntityId(), client.getDataScopeJson(), 1, "producer", client.getClientTier());
            String opaque = publicIds.encode(principal, type, id);
            ExternalApiReadRow row = rows.get(0);
            Map<String, Object> payload = new LinkedHashMap<>();
            String field = switch (type) {
                case "engineer-availability" -> "publicEngineerId";
                case "project" -> "publicProjectId";
                case "contract-status" -> "publicContractId";
                default -> "publicInvoiceId";
            };
            payload.put(field, opaque);
            if ("engineer-availability".equals(type)) {
                payload.put("availabilityStatus", ("Bench".equals(row.getStatus()) || "提案中".equals(row.getStatus())) ? "AVAILABLE"
                        : ("稼動中".equals(row.getStatus()) || "退場予定".equals(row.getStatus())) ? "UNAVAILABLE" : "UNKNOWN");
                if (row.getAvailableDate() != null) payload.put("availableFrom", row.getAvailableDate().toString());
            } else {
                String status = switch (type) {
                    case "project" -> ExternalApiProjectStatusMapper.toExternalStatus(row.getStatus());
                    case "contract-status" -> ExternalApiContractStatusMapper.toExternalStatus(row.getStatus());
                    default -> ExternalApiInvoiceStatusMapper.toExternalStatus(row.getStatus());
                };
                payload.put("status", status);
            }
            if (row.getCustomerId() != null) payload.put("publicCustomerId", publicIds.encode(principal, "customer", row.getCustomerId()));
            if (row.getProjectId() != null) payload.put("publicProjectId", publicIds.encode(principal, "project", row.getProjectId()));
            if (row.getContractId() != null && Long.valueOf(1).equals(row.getContractCount()))
                payload.put("publicContractId", publicIds.encode(principal, "contract-status", row.getContractId()));
            Map<String, Object> envelope = new LinkedHashMap<>();
            String event = UUID.randomUUID().toString(), correlation = UUID.randomUUID().toString();
            envelope.put("eventId", event);
            envelope.put("eventType", "resource.changed");
            envelope.put("schemaVersion", "v1");
            envelope.put("createdAt", now.atOffset(ZoneOffset.UTC).toString());
            envelope.put("publicResourceId", opaque);
            envelope.put("changedFieldNames", payload.keySet().stream().filter(key -> !key.equals(field)).toList());
            envelope.put("payload", payload);
            envelope.put("correlationId", correlation);
            try {
                deliveries.enqueue(event, subscription.getId(), 1, client.getClientId(), scopeCode, tenant, type, id,
                        "resource.changed", "v1", correlation, ExternalDtoSnapshot.of(json.writeValueAsString(envelope)), now);
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("外部DTOを構築できません");
            }
        }
    }

    private static List<Long> numbers(Set<String> values) {
        if (values == null) return null;
        return values.stream().map(Long::valueOf).toList();
    }
}
