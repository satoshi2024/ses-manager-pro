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
                new com.fasterxml.jackson.databind.ObjectMapper());
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

    private ExternalDtoSnapshot parsed() {
        return ExternalDtoSnapshot.ofAllowList("{\"eventType\":\"resource.changed\"}",
                ExternalDtoSnapshot.INBOUND_FIELDS);
    }

    private InboundEvent event(Long id, int version, String status, String hash) {
        return InboundEvent.builder().id(id).version(version).status(status).rawBodyHash(hash).build();
    }
}
