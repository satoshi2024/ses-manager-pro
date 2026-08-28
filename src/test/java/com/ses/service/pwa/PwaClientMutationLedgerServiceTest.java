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

        long createdAt = Instant.now().toEpochMilli();
        PwaClientMutationLedgerService.Claim first = ledger.claim(command, "req-1", hash, createdAt, scope);

        assertThat(first.replay()).isFalse();
        verify(mapper).insert(any(PwaClientMutation.class));

        PwaClientMutation completed = new PwaClientMutation();
        completed.setId(11L);
        completed.setUserScopeHash("scope-hash");
        completed.setPayloadHash(hash);
        completed.setStatus("COMPLETED");
        completed.setResponseJson("{\"saved\":true}");
        when(mapper.selectByUserAndClientRequest(7L, "req-1")).thenReturn(completed);

        PwaClientMutationLedgerService.Claim replay = ledger.claim(command, "req-1", hash, createdAt, scope);

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

        assertThatThrownBy(() -> ledger.claim(command, "req-2", hash, Instant.now().toEpochMilli(), scope))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .hasMessage("pwa.idempotencyPayloadMismatch");
        verify(mapper, never()).insert(any(PwaClientMutation.class));
    }

    @Test
    void 同一ID同一payloadでもoperationが異なれば409で再利用しない() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand(
                "timesheet:POST:/api/my/pwa/timesheet/daily", "timesheet", "2026-08", 0, payload);
        String scope = "scope-operation";
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(7L, 9L, scope);
        when(userContextService.assertCurrent(scope)).thenReturn(context);
        PwaClientMutation existing = new PwaClientMutation();
        existing.setId(13L);
        existing.setOperation("expense:POST:/api/my/pwa/expenses/drafts");
        existing.setPayloadHash(hash);
        existing.setStatus("COMPLETED");
        when(mapper.selectByUserAndClientRequest(7L, "req-operation")).thenReturn(existing);

        assertThatThrownBy(() -> ledger.claim(command, "req-operation", hash,
                Instant.now().toEpochMilli(), scope))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .hasMessage("pwa.idempotencyPayloadMismatch");
        verify(mapper, never()).insert(any(PwaClientMutation.class));
    }

    @Test
    void V112旧hash行は現在のoperationへ一度だけ再束縛してreplayできる() {
        ObjectNode payload = objectMapper.createObjectNode().put("id", 1L);
        PwaMutationCommand command = new PwaMutationCommand(
                "expense:PUT:/api/my/pwa/expenses/drafts/1", "expense", "2026-08", 2, payload);
        String scope = "scope-legacy";
        assertThat(command.legacyPayloadHash(canonicalizer))
                .isEqualTo("368a9731bfdd1593e89a29771b422ccf4c189b541d3bb64da30835fa70d23fb8");
        PwaUserContextService.CurrentContext context =
                new PwaUserContextService.CurrentContext(7L, 9L, scope);
        when(userContextService.assertCurrent(scope)).thenReturn(context);
        PwaClientMutation completed = new PwaClientMutation();
        completed.setId(14L);
        completed.setPayloadHash(command.legacyPayloadHash(canonicalizer));
        completed.setStatus("COMPLETED");
        completed.setResponseJson("{\"saved\":true}");
        when(mapper.selectByUserAndClientRequest(7L, "req-legacy")).thenReturn(completed);

        PwaClientMutationLedgerService.Claim replay = ledger.claim(command, "req-legacy",
                command.legacyPayloadHash(canonicalizer), Instant.now().toEpochMilli(), scope);

        assertThat(replay.replay()).isTrue();
        assertThat(completed.getOperation()).isEqualTo(command.operation());
        verify(mapper).updateById(completed);
    }

    @Test
    void 同一userのscope更新後も同一hashの完了commandをreplayする() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext rotatedContext =
                new PwaUserContextService.CurrentContext(7L, 9L, "scope-B");
        when(userContextService.assertCurrent("scope-B")).thenReturn(rotatedContext);
        PwaClientMutation completed = new PwaClientMutation();
        completed.setId(12L);
        completed.setUserScopeHash("hash-of-scope-A");
        completed.setPayloadHash(hash);
        completed.setStatus("COMPLETED");
        completed.setResponseJson("{\"saved\":true}");
        when(mapper.selectByUserAndClientRequest(7L, "req-rotated")).thenReturn(completed);

        PwaClientMutationLedgerService.Claim replay = ledger.claim(command, "req-rotated", hash,
                Instant.now().toEpochMilli(), "scope-B");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.responseData().path("saved").asBoolean()).isTrue();
        verify(mapper, never()).insert(any(PwaClientMutation.class));
    }

    @Test
    void 期限超過のclient作成日時は送信をclaimしない() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);

        assertThatThrownBy(() -> ledger.claim(command, "req-expired", hash,
                Instant.now().minusSeconds(31L * 24 * 60 * 60).toEpochMilli(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .hasMessage("pwa.queueExpired");
        verify(mapper, never()).insert(any(PwaClientMutation.class));
        verify(userContextService, never()).assertCurrent("scope-A");
    }

    @Test
    void 未来すぎるclient作成日時は400で送信をclaimしない() {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);

        assertThatThrownBy(() -> ledger.claim(command, "req-future", hash,
                Instant.now().plusSeconds(10 * 60).toEpochMilli(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasMessage("error.pwa.createdAtInvalid");
        verify(mapper, never()).insert(any(PwaClientMutation.class));
        verify(userContextService, never()).assertCurrent("scope-A");
    }
}
