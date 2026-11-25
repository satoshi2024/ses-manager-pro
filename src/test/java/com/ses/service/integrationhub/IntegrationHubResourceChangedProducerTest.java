package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.*;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.entity.integrationhub.*;
import com.ses.mapper.*;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationHubResourceChangedProducerTest {
    @AfterEach void clear() { AccountingTenantContextHolder.clear(); }

    @ParameterizedTest
    @CsvSource({"engineer-availability,engineerIds,publicEngineerId,integration.availability.read",
            "project,projectIds,publicProjectId,integration.project.read",
            "contract-status,contractIds,publicContractId,integration.contract-status.read",
            "invoice-status,invoiceIds,publicInvoiceId,integration.invoice-status.read"})
    void 四種類の登録eventはopaqueIDと有限snapshotだけをenqueueする(String type, String dimension,
            String primaryField, String scopeCode) throws Exception {
        var subscriptions = mock(WebhookSubscriptionMapper.class);
        var clients = mock(ApiClientMapper.class);
        var permissions = mock(ApiClientScopeMapper.class);
        var resources = mock(ExternalApiReadMapper.class);
        var deliveries = mock(ApiDeliveryService.class);
        var ownership = mock(IntegrationHubWebhookResourceScopeMapper.class);
        when(ownership.isCurrentOwnership(type, 17L, "tenant-a", 9L)).thenReturn(true);
        var properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true);
        properties.getTopology().setBoundTenantId("tenant-a");
        properties.getPublicApi().setPublicIdKey("synthetic-public-id-key-at-least-32-bytes");
        String scope = "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],\"" + dimension + "\":[\"17\"]}";
        when(subscriptions.selectOutboundForTenant("tenant-a")).thenReturn(List.of(WebhookSubscription.builder()
                .id(5L).clientId("client-a").dataScopeJson(scope).build()));
        when(clients.selectByClientId("client-a")).thenReturn(ApiClient.builder().id(3L).clientId("client-a")
                .tenantId("tenant-a").legalEntityId(9L).dataScopeJson(scope).status("ACTIVE").build());
        when(permissions.selectActive(3L, scopeCode, "integration.webhook.deliver"))
                .thenReturn(ApiClientScope.builder().dataScopeJson(scope).build());
        ExternalApiReadRow row = new ExternalApiReadRow(); row.setId(17L); row.setStatus("OPEN");
        when(resources.selectEngineers(any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectProjects(any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectContracts(any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectInvoices(any(), any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        var producer = new IntegrationHubResourceChangedProducer(subscriptions, clients, permissions, resources,
                new ExternalApiPublicIdCodec(properties), properties, deliveries, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC), ownership);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        producer.changed(type, 17L);
        var snapshot = ArgumentCaptor.forClass(ExternalDtoSnapshot.class);
        verify(deliveries).enqueue(anyString(), eq(5L), eq(1), eq("client-a"), eq(scopeCode), eq("tenant-a"),
                eq(type), eq(17L), eq("resource.changed"), eq("v1"), anyString(), snapshot.capture(), any());
        var root = new ObjectMapper().readTree(snapshot.getValue().json());
        assertThat(root.get("payload").get(primaryField).asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(root.get("publicResourceId").asText()).isEqualTo(root.get("payload").get(primaryField).asText());
        assertThat(root.get("createdAt").asText()).isEqualTo("2026-11-14T00:00:00Z");
        ExternalDtoSnapshot.requireAllowList(snapshot.getValue(), ExternalDtoSnapshot.OUTBOUND_FIELDS);
        clearInvocations(deliveries);
        when(ownership.isCurrentOwnership(type, 17L, "tenant-a", 9L)).thenReturn(false);
        producer.changed(type, 17L);
        verifyNoInteractions(deliveries);
        when(ownership.isCurrentOwnership(type, 17L, "tenant-a", 9L)).thenReturn(true);
        when(permissions.selectActive(3L, scopeCode, "integration.webhook.deliver")).thenReturn(null);
        producer.changed(type, 17L);
        verifyNoInteractions(deliveries);
        clearInvocations(subscriptions, clients, resources, permissions, deliveries, ownership);
        properties.getExternalTransport().setEnabled(false);
        producer.changed(type, 17L);
        verifyNoInteractions(subscriptions, clients, resources, permissions, deliveries, ownership);
        properties.getExternalTransport().setEnabled(true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        org.springframework.test.util.ReflectionTestUtils.setField(producer, "registries", provider);
        AccountingTenantContextHolder.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext();
        producer.changed(type, 17L);
        assertThat(registry.get("integration_hub_producer_skipped_total").tag("reason", "NO_TENANT").counter().count()).isEqualTo(1);
        verifyNoInteractions(subscriptions, clients, resources, permissions, deliveries, ownership);
        AccountingTenantContextHolder.setTenantId("wrong-tenant");
        producer.changed(type, 17L);
        assertThat(registry.get("integration_hub_producer_skipped_total").tag("reason", "TENANT_MISMATCH").counter().count()).isEqualTo(1);
        verifyNoInteractions(subscriptions, clients, resources, permissions, deliveries, ownership);
        AccountingTenantContextHolder.setTenantId("tenant-a");
        when(subscriptions.selectOutboundForTenant("tenant-a")).thenReturn(java.util.Collections.nCopies(257, WebhookSubscription.builder().build()));
        producer.changed(type, 17L);
        assertThat(registry.get("integration_hub_producer_skipped_total").tag("reason", "SUBSCRIPTION_LIMIT").counter().count()).isEqualTo(1);
        verifyNoInteractions(clients, resources, permissions, deliveries, ownership);

        // FE-D03（design §9.3）: 一時の閉鎖の間も outbox へ積み（runtime switch の producer の判定が開）、確定の閉鎖では積まない
        when(subscriptions.selectOutboundForTenant("tenant-a")).thenReturn(List.of(WebhookSubscription.builder()
                .id(5L).clientId("client-a").dataScopeJson(scope).build()));
        when(permissions.selectActive(3L, scopeCode, "integration.webhook.deliver"))
                .thenReturn(ApiClientScope.builder().dataScopeJson(scope).build());
        var runtime = mock(com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.class);
        org.springframework.test.util.ReflectionTestUtils.setField(producer, "runtimeSwitch", runtime);
        when(runtime.outboundProducerOpen()).thenReturn(true);
        clearInvocations(deliveries);
        producer.changed(type, 17L);
        verify(deliveries).enqueue(anyString(), eq(5L), eq(1), eq("client-a"), eq(scopeCode), eq("tenant-a"),
                eq(type), eq(17L), eq("resource.changed"), eq("v1"), anyString(), any(), any());
        when(runtime.outboundProducerOpen()).thenReturn(false);
        clearInvocations(subscriptions, deliveries);
        producer.changed(type, 17L);
        verifyNoInteractions(subscriptions, deliveries);
    }

    /**
     * codecのopaque IDはpaddingなしbase64urlのため、約1/32の資源で先頭が「-」または「_」になる。保存境界がそれを
     * 不正形式として拒むと、enqueue開始後の例外として業務更新ごと巻き戻り、その資源は購読がある限り更新できない。
     * clientIdを固定し、先頭が「-」「_」になる資源IDを決定的に選んで実producerを通す。
     */
    @ParameterizedTest
    @CsvSource({"engineer-availability,engineerIds,publicEngineerId,integration.availability.read,-",
            "engineer-availability,engineerIds,publicEngineerId,integration.availability.read,_",
            "project,projectIds,publicProjectId,integration.project.read,-",
            "project,projectIds,publicProjectId,integration.project.read,_",
            "contract-status,contractIds,publicContractId,integration.contract-status.read,-",
            "contract-status,contractIds,publicContractId,integration.contract-status.read,_",
            "invoice-status,invoiceIds,publicInvoiceId,integration.invoice-status.read,-",
            "invoice-status,invoiceIds,publicInvoiceId,integration.invoice-status.read,_"})
    void 先頭が記号のopaqueIDでもenvelopeを保存境界へ通しenqueueする(String type, String dimension,
            String primaryField, String scopeCode, char leading) throws Exception {
        var properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true);
        properties.getTopology().setBoundTenantId("tenant-a");
        properties.getPublicApi().setPublicIdKey("synthetic-public-id-key-at-least-32-bytes");
        var codec = new ExternalApiPublicIdCodec(properties);
        var principal = new ExternalApiPrincipal("client-a", 3L, "tenant-a", 9L, "{}", 1, "producer", null);
        long id = java.util.stream.LongStream.rangeClosed(1, 10_000)
                .filter(candidate -> codec.encode(principal, type, candidate).charAt(0) == leading)
                .findFirst().orElseThrow();
        String expected = codec.encode(principal, type, id);

        var subscriptions = mock(WebhookSubscriptionMapper.class);
        var clients = mock(ApiClientMapper.class);
        var permissions = mock(ApiClientScopeMapper.class);
        var resources = mock(ExternalApiReadMapper.class);
        var deliveries = mock(ApiDeliveryService.class);
        var ownership = mock(IntegrationHubWebhookResourceScopeMapper.class);
        when(ownership.isCurrentOwnership(type, id, "tenant-a", 9L)).thenReturn(true);
        String scope = "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"9\"],\"" + dimension + "\":[\"" + id + "\"]}";
        when(subscriptions.selectOutboundForTenant("tenant-a")).thenReturn(List.of(WebhookSubscription.builder()
                .id(5L).clientId("client-a").dataScopeJson(scope).build()));
        when(clients.selectByClientId("client-a")).thenReturn(ApiClient.builder().id(3L).clientId("client-a")
                .tenantId("tenant-a").legalEntityId(9L).dataScopeJson(scope).status("ACTIVE").build());
        when(permissions.selectActive(3L, scopeCode, "integration.webhook.deliver"))
                .thenReturn(ApiClientScope.builder().dataScopeJson(scope).build());
        ExternalApiReadRow row = new ExternalApiReadRow(); row.setId(id); row.setStatus("OPEN");
        when(resources.selectEngineers(any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectProjects(any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectContracts(any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        when(resources.selectInvoices(any(), any(), any(), any(), anyInt(), eq(9L))).thenReturn(List.of(row));
        var producer = new IntegrationHubResourceChangedProducer(subscriptions, clients, permissions, resources,
                codec, properties, deliveries, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC), ownership);
        AccountingTenantContextHolder.setTenantId("tenant-a");

        producer.changed(type, id);

        var snapshot = ArgumentCaptor.forClass(ExternalDtoSnapshot.class);
        verify(deliveries).enqueue(anyString(), eq(5L), eq(1), eq("client-a"), eq(scopeCode), eq("tenant-a"),
                eq(type), eq(id), eq("resource.changed"), eq("v1"), anyString(), snapshot.capture(), any());
        var root = new ObjectMapper().readTree(snapshot.getValue().json());
        assertThat(root.get("publicResourceId").asText()).isEqualTo(expected).startsWith(String.valueOf(leading));
        assertThat(root.get("payload").get(primaryField).asText()).isEqualTo(expected);
        ExternalDtoSnapshot.requireAllowList(snapshot.getValue(), ExternalDtoSnapshot.OUTBOUND_FIELDS);
        ExternalDtoSnapshot.requirePrimaryResourceBinding(snapshot.getValue(), type, expected);
        // 送信workerはDB往復後のJSONから保存境界を組み直す。同じ検証を再度通ること。
        assertThat(ExternalDtoSnapshot.of(snapshot.getValue().json()).payloadHash()).isEqualTo(snapshot.getValue().payloadHash());
    }

    /** 追加で通すのはcodecの発行形式（43文字のbase64url）だけで、public resource ID以外のfieldの規則は変えない。 */
    @org.junit.jupiter.api.Test
    void 保存境界はpublicResourceIDに限りcodec形式の先頭記号を通しそれ以外は従来どおり拒否する() {
        String codecShaped = "-" + "A".repeat(42);
        String underscoreShaped = "_" + "b".repeat(42);
        for (String field : List.of("publicResourceId", "publicEngineerId", "publicProjectId", "publicCustomerId",
                "publicContractId", "publicInvoiceId")) {
            assertThatCode(() -> ExternalDtoSnapshot.of("{\"" + field + "\":\"" + codecShaped + "\"}")).doesNotThrowAnyException();
            assertThatCode(() -> ExternalDtoSnapshot.of("{\"" + field + "\":\"" + underscoreShaped + "\"}")).doesNotThrowAnyException();
            // 先頭記号で長さがcodec形式と違う値、codecの字母にない文字を含む値は拒否する。
            for (String invalid : List.of("-" + "A".repeat(41), "-" + "A".repeat(43), "-abc", "_" + "A".repeat(41) + ".",
                    "-" + "A".repeat(41) + ":")) {
                assertThatThrownBy(() -> ExternalDtoSnapshot.of("{\"" + field + "\":\"" + invalid + "\"}"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("external DTO " + field + " has invalid format");
            }
        }
        // payload内のpublic IDも同じ規則で検証する。
        assertThatCode(() -> ExternalDtoSnapshot.of("{\"payload\":{\"publicCustomerId\":\"" + underscoreShaped + "\"}}"))
                .doesNotThrowAnyException();
        // eventId / providerEventId はcodecが発行しないため、先頭記号は従来どおり拒否する。
        for (String field : List.of("eventId", "providerEventId")) {
            assertThatThrownBy(() -> ExternalDtoSnapshot.of("{\"" + field + "\":\"" + codecShaped + "\"}"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("external DTO " + field + " has invalid format");
        }
    }
}
