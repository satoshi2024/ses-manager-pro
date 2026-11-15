package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.LoginUser;
import com.ses.entity.AuditLog;
import com.ses.entity.SysUser;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationHubBulkReplayServiceTest {
    private ApiDeliveryMapper deliveries;
    private WebhookSubscriptionMapper subscriptions;
    private IntegrationHubManagementService management;
    private AuditLogService audit;
    private IntegrationHubBulkReplayService service;
    private Authentication authentication;
    @BeforeEach void setup() {
        deliveries=mock(ApiDeliveryMapper.class); subscriptions=mock(WebhookSubscriptionMapper.class);
        management=mock(IntegrationHubManagementService.class); audit=mock(AuditLogService.class);
        service=new IntegrationHubBulkReplayService(deliveries, subscriptions, management, audit, new LocalTransactions(),
                Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"),ZoneOffset.UTC),new ObjectMapper());
        var user=SysUser.builder().username("synthetic-admin").role("管理者").tenantId("tenant-a").status(1).build();
        user.setId(7L);
        var principal=new LoginUser(user,List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        authentication=new UsernamePasswordAuthenticationToken(principal,"unused",principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(management.requireManagementTenant()).thenReturn("tenant-a");
        when(subscriptions.selectActiveById(8L)).thenReturn(WebhookSubscription.builder().id(8L).clientId("client-a").direction("OUTBOUND").status("ACTIVE").build());
        doAnswer(call->{ assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            AuditLog entry=call.getArgument(0);
            new com.ses.common.audit.ActorAttribution(com.ses.common.audit.ActorType.valueOf(entry.getActorType()),
                    com.ses.common.audit.ConfirmationSource.valueOf(entry.getConfirmationSource()),entry.getHumanUserId(),entry.getCorrelationId(),entry.getIdempotencyKey());
            return null; }).when(audit).recordDomainEventRequired(any());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void 非管理者と不正条件は検索とTLSより前に拒否する() {
        when(management.requireManagementTenant()).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(()->service.replay(command(2),authentication)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(deliveries,subscriptions,audit);
        doReturn("tenant-a").when(management).requireManagementTenant();
        assertThatThrownBy(()->service.replay(command(21),authentication)).isInstanceOf(com.ses.common.exception.BusinessException.class);
        var wrong=new IntegrationHubBulkReplayService.Command("tenant-other",8L,9L,Instant.parse("2026-11-14T00:00:00Z"),Instant.parse("2026-11-16T00:00:00Z"),IntegrationHubBulkReplayService.FailureClass.STARTUP_REVALIDATION_REQUIRED,"ENVIRONMENT_FIXED",2);
        assertThatThrownBy(()->service.replay(wrong,authentication)).isInstanceOf(com.ses.common.exception.BusinessException.class);
        verifyNoInteractions(deliveries,subscriptions,audit);
    }
    @Test void 候補は現tenantとsubscriptionと期間と分類および最新generationに限定する() {
        when(deliveries.selectList(any())).thenReturn(List.of(row(1),row(2)));
        when(management.replay(anyLong(),eq(9L),eq("ENVIRONMENT_FIXED"),eq(authentication))).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return 10L+(Long)call.getArgument(0); });
        var result=service.replay(command(2),authentication);
        assertThat(result.succeeded()).isEqualTo(2); assertThat(result.completionAuditRecorded()).isTrue();
        var query=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(deliveries).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "subscription_id", "created_at", "last_error_code", "newer.delivery_generation>", "replay_generation=t_api_delivery.delivery_generation+1", "LIMIT 2");
        var entries=org.mockito.ArgumentCaptor.forClass(AuditLog.class); verify(audit,times(2)).recordDomainEventRequired(entries.capture());
        assertThat(entries.getAllValues().get(0).getAfterState()).contains("STARTUP_REVALIDATION_REQUIRED","ENVIRONMENT_FIXED","sources");
        assertThat(entries.getAllValues().get(1).getCorrelationId()).isEqualTo(entries.getAllValues().get(0).getCorrelationId());
    }
    @Test void 一項のTLS拒否や並行replay競合は他項をrollbackしない() {
        when(deliveries.selectList(any())).thenReturn(List.of(row(1),row(2)));
        when(management.replay(1L,9L,"ENVIRONMENT_FIXED",authentication)).thenThrow(new IllegalStateException("synthetic generation conflict"));
        when(management.replay(2L,9L,"ENVIRONMENT_FIXED",authentication)).thenReturn(12L);
        var result=service.replay(command(2),authentication);
        assertThat(result.rejected()).isEqualTo(1); assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(0).outcome()).isEqualTo("REJECTED");
    }
    @Test void 要求監査が失敗したらgenerationを一件も作らない() {
        when(deliveries.selectList(any())).thenReturn(List.of(row(1)));
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit).recordDomainEventRequired(any());
        assertThatThrownBy(()->service.replay(command(1),authentication)).isInstanceOf(IllegalStateException.class);
        verify(management,never()).replay(anyLong(),anyLong(),anyString(),any());
    }
    @Test void 完了監査の失敗は既成功を消さず明示flagを返す() {
        when(deliveries.selectList(any())).thenReturn(List.of(row(1)));
        when(management.replay(1L,9L,"ENVIRONMENT_FIXED",authentication)).thenReturn(11L);
        doAnswer(call->{ AuditLog entry=call.getArgument(0); if("NF05_BULK_REPLAY_COMPLETED".equals(entry.getApplicationCode()))throw new IllegalStateException("synthetic audit failure"); return null; }).when(audit).recordDomainEventRequired(any());
        var result=service.replay(command(1),authentication); assertThat(result.succeeded()).isEqualTo(1); assertThat(result.completionAuditRecorded()).isFalse();
    }
    @Test void 総予算に達したら次項を始めず未処理件数を返す() {
        when(deliveries.selectList(any())).thenReturn(List.of(row(1),row(2)));
        when(management.replay(1L,9L,"ENVIRONMENT_FIXED",authentication)).thenReturn(11L);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"nanos",(java.util.function.LongSupplier)()->calls.getAndIncrement()<2?0:java.util.concurrent.TimeUnit.SECONDS.toNanos(121));
        var result=service.replay(command(2),authentication); assertThat(result.succeeded()).isEqualTo(1); assertThat(result.deferred()).isEqualTo(1);
        verify(management,never()).replay(eq(2L),anyLong(),anyString(),any());
    }
    private IntegrationHubBulkReplayService.Command command(int limit) {
        return new IntegrationHubBulkReplayService.Command("tenant-a",8L,9L,Instant.parse("2026-11-14T00:00:00Z"),Instant.parse("2026-11-16T00:00:00Z"),IntegrationHubBulkReplayService.FailureClass.STARTUP_REVALIDATION_REQUIRED,"ENVIRONMENT_FIXED",limit);
    }
    private ApiDelivery row(long id) { return ApiDelivery.builder().id(id).tenantId("tenant-a").clientId("client-a").subscriptionId(8L).build(); }
    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
