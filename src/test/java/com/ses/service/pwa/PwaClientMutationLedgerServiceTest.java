package com.ses.service.pwa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.PwaClientMutation;
import com.ses.mapper.PwaClientMutationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PwaClientMutationLedgerServiceTest {

    @Autowired
    private PwaClientMutationLedgerService ledger;

    @Autowired
    private PwaCanonicalizer canonicalizer;

    @MockBean
    private PwaClientMutationMapper mapper;

    @MockBean
    private PwaUserContextService userContextService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 同一ID同一hashは二度目に保存済み結果をreplayする() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String scope = "scope-A";
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(7L, 9L, scope);
        when(userContextService.assertCurrent(scope)).thenReturn(context);
        when(userContextService.hashScope(scope)).thenReturn("scope-hash");
        when(mapper.selectByUserAndClientRequest(7L, "req-1")).thenReturn(null);
        when(mapper.insert(any(PwaClientMutation.class))).thenAnswer(invocation -> {
            PwaClientMutation row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        });

        PwaClientMutationLedgerService.Claim first = ledger.claim(command, "req-1", hash, scope);

        assertThat(first.replay()).isFalse();
        verify(mapper).insert(any(PwaClientMutation.class));

        PwaClientMutation completed = new PwaClientMutation();
        completed.setId(11L);
        completed.setUserScopeHash("scope-hash");
        completed.setPayloadHash(hash);
        completed.setStatus("COMPLETED");
        completed.setResponseJson("{\"saved\":true}");
        when(mapper.selectByUserAndClientRequest(7L, "req-1")).thenReturn(completed);

        PwaClientMutationLedgerService.Claim replay = ledger.claim(command, "req-1", hash, scope);

        assertThat(replay.replay()).isTrue();
        assertThat(replay.responseData().path("saved").asBoolean()).isTrue();
        verify(mapper).insert(any(PwaClientMutation.class));
    }

    @Test
    void 同一ID異hashは409で業務実行をclaimしない() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String scope = "scope-A";
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(7L, 9L, scope);
        when(userContextService.assertCurrent(scope)).thenReturn(context);
        when(userContextService.hashScope(scope)).thenReturn("scope-hash");
        PwaClientMutation existing = new PwaClientMutation();
        existing.setId(11L);
        existing.setUserScopeHash("scope-hash");
        existing.setPayloadHash("0".repeat(64));
        existing.setStatus("COMPLETED");
        when(mapper.selectByUserAndClientRequest(7L, "req-2")).thenReturn(existing);

        assertThatThrownBy(() -> ledger.claim(command, "req-2", hash, scope))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .hasMessage("pwa.idempotencyPayloadMismatch");
        verify(mapper, never()).insert(any(PwaClientMutation.class));
    }
}
