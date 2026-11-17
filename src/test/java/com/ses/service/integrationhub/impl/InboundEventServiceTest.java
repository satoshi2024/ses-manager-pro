package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.InboundEvent;
import com.ses.mapper.InboundEventMapper;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventAdminReferenceCodec;
import com.ses.service.integrationhub.InboundEventBindingValidator;
import com.ses.service.integrationhub.InboundEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: inbound duplicate/conflictをDBのcanonical stateへ収束する。 */
class InboundEventServiceTest {
    private InboundEventMapper mapper;
    private InboundEventServiceImpl service;
    private InboundEventBindingValidator bindingValidator;
    private InboundEventAdminReferenceCodec referenceCodec;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
    private final String firstHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final String secondHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @BeforeEach
    void setUp() {
        mapper = mock(InboundEventMapper.class);
        bindingValidator = mock(InboundEventBindingValidator.class);
        referenceCodec = mock(InboundEventAdminReferenceCodec.class);
        when(bindingValidator.validateForReceipt(any(), any(), any(), any(), any()))
                .thenReturn(new InboundEventBindingValidator.Binding(null, null));
        when(referenceCodec.eventReference(any(), any(), any())).thenReturn("event-reference");
        service = new InboundEventServiceImpl(mapper, bindingValidator, referenceCodec,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.ses.config.integrationhub.IntegrationHubExternalApiProperties());
    }

    @Test
    void 先行insert後のhash不一致はCONFLICTへ永続化する() {
        InboundEvent existing = event(7L, 0, "RECEIVED", firstHash);
        InboundEvent conflicted = event(7L, 1, "CONFLICT", firstHash);
        when(mapper.selectByProviderEvent("client-a", "provider", "event-1")).thenReturn(existing);
        when(mapper.transitionConflict(7L, 0, secondHash, now, now.plusDays(90))).thenReturn(1);
        when(mapper.selectForUpdate(7L)).thenReturn(conflicted);

        InboundEventService.Receipt receipt = service.recordReceived("client-a", "provider", "event-1", secondHash,
                now, parsed(), true, now);

        assertTrue(receipt.conflict());
        assertEquals("CONFLICT", receipt.event().getStatus());
        verify(mapper).transitionConflict(7L, 0, secondHash, now, now.plusDays(90));
    }

    @Test
    void 同時insertのDuplicateKeyでもhash不一致をCONFLICTへ永続化する() {
        InboundEvent concurrent = event(8L, 0, "RECEIVED", firstHash);
        InboundEvent conflicted = event(8L, 1, "CONFLICT", firstHash);
        when(mapper.selectByProviderEvent("client-a", "provider", "event-2")).thenReturn(null);
        when(mapper.insert(any(InboundEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectByProviderEventForUpdate("client-a", "provider", "event-2"))
                .thenReturn(concurrent);
        when(mapper.transitionConflict(8L, 0, secondHash, now, now.plusDays(90))).thenReturn(1);
        when(mapper.selectForUpdate(8L)).thenReturn(conflicted);

        InboundEventService.Receipt receipt = service.recordReceived("client-a", "provider", "event-2", secondHash,
                now, parsed(), true, now);

        assertTrue(receipt.conflict());
        assertEquals("CONFLICT", receipt.event().getStatus());
        verify(mapper).selectByProviderEventForUpdate("client-a", "provider", "event-2");
        verify(mapper).transitionConflict(8L, 0, secondHash, now, now.plusDays(90));
    }

    /**
     * N5-P3-7: 受信・claimのたびに期限切れleaseの一括UPDATEをしない。node単位でinbound-lease-seconds（既定60秒）に1回だけ。
     * 期限切れのPROCESSING行そのものはclaimがreclaimExpiredで取り直す。明示の回収は間隔に関係なく実行する。
     */
    @Test
    void 要求経路の一括lease回収はlease秒数の間隔に1回だけでclaimは期限切れ行を直接取り直す() {
        InboundEvent expired = InboundEvent.builder().id(9L).version(4).status("PROCESSING")
                .leaseExpiresAt(now.minusSeconds(1)).build();
        when(mapper.selectByProviderEvent(any(), any(), any())).thenReturn(null);
        when(mapper.insert(any(InboundEvent.class))).thenReturn(1);
        when(mapper.selectForUpdate(9L)).thenReturn(expired);
        when(mapper.reclaimExpired(9L, 4, "lease-a", now.plusSeconds(60), now)).thenReturn(1);
        when(mapper.selectById(9L)).thenReturn(expired);

        service.recordReceived("client-a", "provider", "event-3", firstHash, now, parsed(), true, now);
        assertEquals(expired, service.claim(9L, "lease-a", now, now.plusSeconds(60)));
        for (int second = 1; second < 60; second += 7) {
            service.recordReceived("client-a", "provider", "event-3", firstHash, now, parsed(), true,
                    now.plusSeconds(second));
        }
        verify(mapper, org.mockito.Mockito.times(1)).recoverExpiredLeases(any());
        verify(mapper).reclaimExpired(9L, 4, "lease-a", now.plusSeconds(60), now);

        service.recordReceived("client-a", "provider", "event-3", firstHash, now, parsed(), true, now.plusSeconds(60));
        verify(mapper, org.mockito.Mockito.times(2)).recoverExpiredLeases(any());
        verify(mapper).recoverExpiredLeases(now.plusSeconds(60));

        // 時計が間隔より大きく戻った場合は起点を取り直す（回収が止まり続けない）。
        service.recordReceived("client-a", "provider", "event-3", firstHash, now, parsed(), true, now.minusHours(1));
        verify(mapper).recoverExpiredLeases(now.minusHours(1));

        // 運用・試験の明示回収は間隔に関係なく実行する。
        service.recoverExpiredLeases(now.minusHours(1));
        verify(mapper, org.mockito.Mockito.times(2)).recoverExpiredLeases(now.minusHours(1));
    }

    private ExternalDtoSnapshot parsed() {
        return ExternalDtoSnapshot.ofAllowList("{\"eventType\":\"resource.changed\"}",
                ExternalDtoSnapshot.INBOUND_FIELDS);
    }

    private InboundEvent event(Long id, int version, String status, String hash) {
        return InboundEvent.builder().id(id).version(version).status(status).rawBodyHash(hash).build();
    }
}
