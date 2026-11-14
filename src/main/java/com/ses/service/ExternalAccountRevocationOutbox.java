package com.ses.service;

import com.ses.common.audit.ActorAttribution;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.ses.service.ExternalAccountRevocationStates.*;

/** §5.13: 既存参照行を使う失効要求outbox。書込みと履歴は同じ短いtransactionで確定する。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountRevocationOutbox {
    private static final Pattern LIFECYCLE_KEY = Pattern.compile("lifecycle-resign:([0-9]+):([0-9]+):g([0-9]+)");
    private final ExternalAccountReferenceMapper references;
    private final LifecycleCaseMapper cases;
    private final AssetEventService events;
    private final SysUserMapper users;
    private final NotificationService notifications;
    private final ObjectProvider<ExternalAccountRevocationDispatcher> dispatcher;
    private final Clock clock;

    /** 呼出元はcase→engineerの順でX lock済み。全法人のaccountをID順にロックする。 */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueForResignation(LifecycleCase resignation, Long actorId) {
        String tenant = AccountingTenantContextHolder.requireTenantContext();
        if (!tenant.equals(resignation.getTenantId()) || !"RESIGNATION".equals(resignation.getLifecycleType())) {
            throw BusinessException.of(409, "error.lifecycle.resignationGateFailed");
        }
        for (ExternalAccountReference hint : references.selectUnrevokedForEngineer(tenant, resignation.getEngineerId())) {
            if (hint.getLegalEntityId() == null) {
                throw BusinessException.of(409, "error.lifecycle.resignationGateFailed");
            }
            ExternalAccountReference row = references.selectByIdForUpdate(hint.getId(), tenant, hint.getLegalEntityId());
            if (row == null || !"ENGINEER".equals(row.getAssigneeType())
                    || !Objects.equals(resignation.getEngineerId(), row.getAssigneeId())) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
            if (!"REVOKED".equals(row.getStatus()) && !hasKey(row)) {
                enqueue(row, "lifecycle-resign:" + resignation.getId() + ":" + row.getId() + ":g1", actorId, null);
            }
        }
        dispatchAfterCommit(tenant);
    }

    /** 登録serviceがengineer Xを保持したまま呼ぶ。再入社案件の存在を含めて判定する。 */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueLateRegistration(ExternalAccountReference row, Long actorId) {
        if (!AccountingTenantContextHolder.requireTenantContext().equals(row.getTenantId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        if (!"ENGINEER".equals(row.getAssigneeType())) return;
        LifecycleCase resignation = cases.selectLatestCompletedResignationWithoutReentry(
                row.getTenantId(), row.getAssigneeId());
        if (resignation != null) {
            enqueue(row, "lifecycle-resign:" + resignation.getId() + ":" + row.getId() + ":g1", actorId, null);
            notifyManagement(row, "退社完了後に登録されたアカウントの失効要求を登録しました");
            dispatchAfterCommit(row.getTenantId());
        }
    }

    /** 手動要求も登録と送信を分離する。同じkeyは再送を起こさない。 */
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference request(Long id, String tenant, Long legalEntityId, String key, Long actorId) {
        ExternalAccountReference row = references.selectByIdForUpdate(id, tenant, legalEntityId);
        if (row == null) throw BusinessException.of(404, "error.asset.revocationNotFound");
        if ("REVOKED".equals(row.getStatus())) return row;
        if (hasKey(row)) {
            if (!row.getIdempotencyKey().equals(key)) throw BusinessException.of(409, "error.common.optimisticLock");
            return row;
        }
        if (key == null || key.isBlank() || key.length() > 128) {
            throw BusinessException.of(400, "error.asset.revocationKeyInvalid");
        }
        try {
            enqueue(row, key, actorId, null);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        dispatchAfterCommit(tenant);
        return references.selectByIdAndScope(id, tenant, legalEntityId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference reRequestUnknown(Long id, String tenant, Long legalEntityId, Long actorId) {
        ExternalAccountReference row = references.selectByIdForUpdate(id, tenant, legalEntityId);
        if (row == null || !"UNKNOWN".equals(row.getStatus()) || row.getRevokeConfirmedAt() != null) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        var match = LIFECYCLE_KEY.matcher(row.getIdempotencyKey() == null ? "" : row.getIdempotencyKey());
        if (!match.matches() || !match.group(2).equals(id.toString())) {
            throw BusinessException.of(400, "error.asset.revocationKeyInvalid");
        }
        java.math.BigInteger generation = new java.math.BigInteger(match.group(3)).add(java.math.BigInteger.ONE);
        String key = "lifecycle-resign:" + match.group(1) + ":" + id + ":g" + generation;
        if (key.length() > 128) throw BusinessException.of(409, "error.asset.revocationKeyInvalid");
        enqueue(row, key, actorId, row.getIdempotencyKey());
        dispatchAfterCommit(tenant);
        return references.selectByIdAndScope(id, tenant, legalEntityId);
    }

    private void enqueue(ExternalAccountReference row, String key, Long actorId, String oldKey) {
        if (!AccountingTenantContextHolder.requireTenantContext().equals(row.getTenantId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        LocalDateTime queuedAt = now();
        if (row.getLegalEntityId() == null || references.enqueueRevoke(row.getId(), row.getTenantId(),
                row.getLegalEntityId(), row.getVersion(), oldKey, key, actorId, queuedAt) != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        history(row, REQUESTED, key, "失効要求を登録しました", ActorAttribution.schedulerPoll("revoke-outbox:" + row.getId(), key));
        // 退社後登録APIの応答にも、同じtransactionで登録した要求状態を返す。
        row.setIdempotencyKey(key);
        row.setStatus("PENDING_CONFIRMATION");
        row.setExternalSyncStatus(REQUESTED);
        row.setRevokeRequestedAt(queuedAt);
        row.setRevokeRequestedBy(actorId);
        row.setRetryCount(0);
        row.setNextRetryAt(queuedAt);
        row.setVersion(row.getVersion() + 1);
    }

    /** CASを短いtransactionでcommitしてから、dispatcherだけがprovider I/Oを実行する。 */
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference claim(ExternalAccountReference hint, LocalDateTime leaseUntil) {
        if (!REQUESTED.equals(hint.getExternalSyncStatus()) && !FAILED.equals(hint.getExternalSyncStatus())) return null;
        if (hint.getNextRetryAt() != null && hint.getNextRetryAt().isAfter(now())) return null;
        return transition(hint, SENDING, "PENDING_CONFIRMATION", retries(hint), leaseUntil, null,
                ActorAttribution.schedulerPoll("revoke-outbox:" + hint.getId(), hint.getIdempotencyKey()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference finish(ExternalAccountReference claim, boolean accepted) {
        if (!SENDING.equals(claim.getExternalSyncStatus())) return null;
        int retries = accepted ? retries(claim) : retries(claim) + 1;
        long minutes = Math.min(1440, 5L << Math.min(retries, 9));
        ExternalAccountReference result = transition(claim, accepted ? SENT : FAILED, "PENDING_CONFIRMATION", retries,
                accepted ? now() : now().plusMinutes(minutes), accepted ? null : "PROVIDER_REVOKE_NOT_ACCEPTED",
                ActorAttribution.providerSync("revoke-outbox:" + claim.getId(), claim.getIdempotencyKey()));
        if (result != null && !accepted) notifyManagement(result, "外部アカウントの失効要求送信に失敗しました");
        return result;
    }

    /** 確認pollの状態変更も送信履歴と同じtransactionで残す。未送信行はmapper側で除外する。 */
    @Transactional(rollbackFor = Exception.class)
    public int completePoll(ExternalAccountReference row, String status, int retries, LocalDateTime next,
                            String syncStatus, String error) {
        if (!AccountingTenantContextHolder.requireTenantContext().equals(row.getTenantId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        int updated = references.completeRevokePollWithCas(row.getId(), status, retries, next, syncStatus, error,
                row.getTenantId(), row.getLegalEntityId(), row.getVersion());
        if (updated == 1) {
            history(row, syncStatus, row.getIdempotencyKey(), "失効確認pollの状態を更新しました",
                    ActorAttribution.schedulerPoll("revoke-poll:" + row.getId(), row.getIdempotencyKey()));
            if ("UNKNOWN".equals(status) || (error != null && !error.startsWith("Provider revoke pending"))) {
                ExternalAccountReference current = references.selectByIdAndScope(row.getId(), row.getTenantId(), row.getLegalEntityId());
                if (current != null) notifyManagement(current, "外部アカウントの失効確認が完了していません");
            }
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSendUnknown(ExternalAccountReference claim) {
        if (!SENDING.equals(claim.getExternalSyncStatus())) return;
        ExternalAccountReference result = transition(claim, UNKNOWN, "UNKNOWN", retries(claim), now(),
                "PROVIDER_REVOKE_OUTCOME_UNKNOWN",
                ActorAttribution.schedulerPoll("revoke-outbox:" + claim.getId(), claim.getIdempotencyKey()));
        if (result != null) notifyManagement(result, "失効要求の送信結果が不明です。手動確認が必要です");
    }

    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredLease(ExternalAccountReference row, boolean idempotent) {
        if (!SENDING.equals(row.getExternalSyncStatus()) || row.getNextRetryAt() == null
                || row.getNextRetryAt().isAfter(now())) return;
        ExternalAccountReference result = transition(row, idempotent ? REQUESTED : UNKNOWN,
                idempotent ? "PENDING_CONFIRMATION" : "UNKNOWN", retries(row), now(),
                idempotent ? null : "PROVIDER_REVOKE_OUTCOME_UNKNOWN",
                ActorAttribution.schedulerPoll("revoke-outbox:" + row.getId(), row.getIdempotencyKey()));
        if (result != null && !idempotent) notifyManagement(result, "失効要求の送信結果が不明です。手動確認が必要です");
    }

    private ExternalAccountReference transition(ExternalAccountReference row, String state, String status,
                                                int retries, LocalDateTime next, String error, ActorAttribution attribution) {
        if (!AccountingTenantContextHolder.requireTenantContext().equals(row.getTenantId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        if (references.transitionOutbox(row.getId(), row.getTenantId(), row.getLegalEntityId(), row.getVersion(),
                row.getIdempotencyKey(), row.getExternalSyncStatus(), state, status, retries, next, error) != 1) return null;
        history(row, state, row.getIdempotencyKey(), "失効要求の送信状態を更新しました", attribution);
        return references.selectByIdAndScope(row.getId(), row.getTenantId(), row.getLegalEntityId());
    }

    private void history(ExternalAccountReference row, String state, String key, String summary, ActorAttribution attribution) {
        // gN→gN+1も旧keyと新keyを別々に保持する。識別子・provider応答原文は履歴へ入れない。
        String details = "{\"referenceId\":" + row.getId() + ",\"oldKey\":"
                + jsonKey(row.getIdempotencyKey()) + ",\"newKey\":" + jsonKey(key) + "}";
        events.recordExternalAccountConfirmation(row.getId(), "EXTERNAL_ACCOUNT_REVOKE_OUTBOX",
                row.getExternalSyncStatus() == null ? "NONE" : row.getExternalSyncStatus(), state,
                attribution, summary, details);
    }

    private String jsonKey(String value) {
        if (value == null) return "null";
        // 旧keyが任意の自由文であってもJSONを壊さず、識別子の混入を避けるためhashだけを記録する。
        try {
            return "\"" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + "\"";
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void notifyManagement(ExternalAccountReference row, String message) {
        log.error("外部アカウント失効要求の要対応状態: referenceId={}, state={}", row.getId(), row.getExternalSyncStatus());
        for (String role : List.of("管理者", "HR")) {
            for (SysUser user : users.selectActiveByRoleAndTenant(role, row.getTenantId())) {
                notifications.publishToUser(user.getId(), "EXTERNAL_ACCOUNT_REVOKE_ALERT", "外部アカウント失効の確認",
                        message + ": " + maskIdentifier(row.getAccountIdentifier()), "/asset/accounts",
                        "revoke:" + row.getId() + ":" + row.getVersion() + ":" + role, "asset-management");
            }
        }
    }

    private void dispatchAfterCommit(String tenant) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    AccountingTenantContextHolder.runWithTenant(tenant, () -> dispatcher.getObject().dispatchPending());
                } catch (RuntimeException ex) {
                    log.warn("失効要求の即時送信に失敗しました。回収処理で継続します: exceptionClass={}", ex.getClass().getSimpleName());
                }
            }
        });
    }

    private boolean hasKey(ExternalAccountReference row) { return row.getIdempotencyKey() != null && !row.getIdempotencyKey().isBlank(); }
    private int retries(ExternalAccountReference row) { return row.getRetryCount() == null ? 0 : row.getRetryCount(); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
