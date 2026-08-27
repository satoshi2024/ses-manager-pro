package com.ses.service.pwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.exception.PwaConflictException;
import com.ses.entity.PwaClientMutation;
import com.ses.mapper.PwaClientMutationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;

/** 要員PWAのclient request IDをDB unique制約で一度だけ実行するledger境界。 */
@Service
@RequiredArgsConstructor
public class PwaClientMutationLedgerService {
    /** 同一hashのCOMPLETED replayをApiAuditFilterが業務監査として二重記録しないためのrequest属性。 */
    public static final String REPLAY_REQUEST_ATTRIBUTE =
            PwaClientMutationLedgerService.class.getName() + ".REPLAY";

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final Duration MAX_QUEUE_AGE = Duration.ofDays(30);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final PwaClientMutationMapper mapper;
    private final PwaUserContextService userContextService;
    private final PwaCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PwaMutationMetrics metrics;

    /** claimからdomain mutation、完了ackまでを呼出元transactionに束縛する。 */
    @Transactional(rollbackFor = Exception.class)
    public Claim claim(PwaMutationCommand command, String clientRequestId, String payloadHash,
                       Long clientCreatedAt, String opaqueScope) {
        validateHeaders(clientRequestId, payloadHash);
        validateClientCreatedAtShape(clientCreatedAt);
        PwaUserContextService.CurrentContext context = userContextService.assertCurrent(opaqueScope);
        validateClientCreatedAt(clientCreatedAt, context);
        String expectedHash = command.payloadHash(canonicalizer);
        String legacyHash = command.legacyPayloadHash(canonicalizer);
        if (!hashMatches(expectedHash, legacyHash, payloadHash)) {
            throw new PwaConflictException("pwa.payloadHashMismatch", Map.of(
                    "type", "PAYLOAD_HASH_MISMATCH",
                    "clientHash", payloadHash,
                    "serverHash", expectedHash,
                    "legacyServerHash", legacyHash,
                    "client", command.payload()));
        }

        PwaClientMutation existing = mapper.selectByUserAndClientRequest(context.userId(), clientRequestId);
        if (existing == null) {
            PwaClientMutation row = new PwaClientMutation();
            row.setClientRequestId(clientRequestId);
            row.setUserId(context.userId());
            // scope leaseは同一ユーザーの再認証で更新され得るため、replay判定はuser_idに束縛する。
            row.setUserScopeHash(userContextService.hashScope(opaqueScope));
            row.setOperation(command.operation());
            row.setScreen(command.screen());
            row.setWorkMonth(command.month());
            row.setPayloadHash(payloadHash);
            row.setBaseVersion(command.baseVersion());
            row.setStatus(PROCESSING);
            row.setCreatedAt(LocalDateTime.now(clock));
            try {
                mapper.insert(row);
                metrics.increment("claimed", command.screen());
                return Claim.newClaim(row.getId(), context);
            } catch (DuplicateKeyException duplicate) {
                existing = mapper.selectByUserAndClientRequest(context.userId(), clientRequestId);
            }
        }

        if (existing == null) {
            throw new PwaConflictException("pwa.mutationInProgress", Map.of("type", "RETRY_CLAIM"));
        }
        // opaque scopeはassertCurrentで現在principalへ検証済み。lease更新後も同一userの
        // COMPLETED commandを同hashでreplayできるよう、scope hashはidempotency判定に使わない。
        // operationはpayloadとは別の副作用境界なので、旧schemaのNULL行を除き必ず一致させる。
        if (existing.getOperation() != null && !Objects.equals(existing.getOperation(), command.operation())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "IDEMPOTENCY_OPERATION_MISMATCH");
            data.put("clientRequestId", clientRequestId);
            data.put("serverOperation", existing.getOperation());
            data.put("clientOperation", command.operation());
            throw new PwaConflictException("pwa.idempotencyPayloadMismatch", data);
        }
        if (!hashMatches(expectedHash, legacyHash, existing.getPayloadHash())
                || !hashMatches(expectedHash, legacyHash, payloadHash)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "IDEMPOTENCY_PAYLOAD_MISMATCH");
            data.put("clientRequestId", clientRequestId);
            data.put("serverHash", existing.getPayloadHash());
            data.put("clientHash", payloadHash);
            data.put("server", existing.getResponseJson());
            data.put("client", command.payload());
            throw new PwaConflictException("pwa.idempotencyPayloadMismatch", data);
        }
        // V112行はoperationがNULLのため、最初に再送された実HTTP経路へ原子的に再束縛する。
        // SELECT ... FOR UPDATEにより、同一旧IDを別operationで同時に再束縛する競合を防ぐ。
        if (existing.getOperation() == null && command.operation() != null) {
            existing.setOperation(command.operation());
            mapper.updateById(existing);
        }
        if (COMPLETED.equals(existing.getStatus())) {
            metrics.increment("replay", command.screen());
            return Claim.replay(existing.getId(), context, readResponse(existing.getResponseJson()));
        }
        throw new PwaConflictException("pwa.mutationInProgress", Map.of(
                "type", "MUTATION_IN_PROGRESS", "clientRequestId", clientRequestId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long mutationId, Object responseData) {
        PwaClientMutation row = mapper.selectById(mutationId);
        if (row == null || !PROCESSING.equals(row.getStatus())) return;
        try {
            row.setStatus(COMPLETED);
            row.setResponseJson(objectMapper.writeValueAsString(responseData));
            row.setCompletedAt(LocalDateTime.now(clock));
            mapper.updateById(row);
            metrics.increment("completed", row.getScreen());
        } catch (Exception e) {
            throw new IllegalStateException("PWA command結果の保存に失敗しました", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long mutationId) {
        PwaClientMutation row = mapper.selectById(mutationId);
        if (row != null && PROCESSING.equals(row.getStatus())) {
            mapper.deleteById(mutationId);
            metrics.increment("abandoned", row.getScreen());
        }
    }

    private JsonNode readResponse(String responseJson) {
        if (responseJson == null) return objectMapper.nullNode();
        try {
            return objectMapper.readTree(responseJson);
        } catch (Exception e) {
            throw new IllegalStateException("PWA command結果の復号に失敗しました", e);
        }
    }

    private boolean hashMatches(String expectedHash, String legacyHash, String actualHash) {
        return Objects.equals(expectedHash, actualHash) || Objects.equals(legacyHash, actualHash);
    }

    private void validateHeaders(String clientRequestId, String payloadHash) {
        if (clientRequestId == null || !REQUEST_ID.matcher(clientRequestId).matches()) {
            throw BusinessException.of(400, "error.pwa.clientRequestIdInvalid");
        }
        if (payloadHash == null || !SHA256.matcher(payloadHash).matches()) {
            throw BusinessException.of(400, "error.pwa.payloadHashInvalid");
        }
    }

    /** server受信時刻を正本としてrecord単位の30日保持期限を検査する。scope leaseの更新とは分離する。 */
    private void validateClientCreatedAtShape(Long clientCreatedAt) {
        if (clientCreatedAt == null || clientCreatedAt <= 0) {
            throw BusinessException.of(400, "error.pwa.createdAtInvalid");
        }
        Instant created = Instant.ofEpochMilli(clientCreatedAt);
        Instant now = Instant.now(clock);
        if (created.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw BusinessException.of(400, "error.pwa.createdAtInvalid");
        }
        if (created.isBefore(now.minus(MAX_QUEUE_AGE))) {
            throw queueExpired(clientCreatedAt);
        }
    }

    private void validateClientCreatedAt(Long clientCreatedAt,
                                         PwaUserContextService.CurrentContext context) {
        if (context.issuedAt() == null) return;
        Instant created = Instant.ofEpochMilli(clientCreatedAt);
        if (created.isBefore(Instant.now(clock).minus(MAX_QUEUE_AGE))) {
            throw queueExpired(clientCreatedAt);
        }
    }

    private PwaConflictException queueExpired(Long clientCreatedAt) {
        return new PwaConflictException("pwa.queueExpired", Map.of(
                "type", "QUEUE_EXPIRED",
                "clientCreatedAt", clientCreatedAt,
                "maxAgeDays", MAX_QUEUE_AGE.toDays()));
    }

    public record Claim(Long mutationId, PwaUserContextService.CurrentContext context,
                        boolean replay, JsonNode responseData) {
        static Claim newClaim(Long id, PwaUserContextService.CurrentContext context) {
            return new Claim(id, context, false, null);
        }

        static Claim replay(Long id, PwaUserContextService.CurrentContext context, JsonNode data) {
            return new Claim(id, context, true, data);
        }
    }
}
