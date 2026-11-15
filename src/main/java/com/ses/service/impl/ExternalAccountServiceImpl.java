package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ExternalAccountService;
import com.ses.service.ExternalAccountRevokeConfirmationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.security.LegalEntityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountServiceImpl implements ExternalAccountService {

    private static final Duration POLL_LEASE_BUFFER = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PROVIDER_CONFIRMATION_TIMEOUT = Duration.ofSeconds(30);

    private final ExternalAccountSystemMapper externalAccountSystemMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final ExternalAccountProviderClient providerClient;
    private final ExternalAccountRevokeConfirmationService revokeConfirmationService;
    private final LegalEntityContextService legalEntityContextService;
    private final EngineerMapper engineerMapper;
    private final SysUserMapper sysUserMapper;
    private final AttendanceScopeMapper attendanceScopeMapper;
    private final com.ses.service.ExternalAccountRevocationOutbox revocationOutbox;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 互換入口もproxy経由でREAD_COMMITTEDを開始し、default methodの自己呼出しを避ける。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ExternalAccountReference createAccountReference(Long systemId,
                                                            String accountIdentifier,
                                                            String assigneeType,
                                                            Long assigneeId,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        return registerAccountReference(systemId, accountIdentifier, assigneeType, assigneeId, permissionLevel, actorUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ExternalAccountReference registerAccountReference(Long systemId,
                                                              String accountIdentifier,
                                                              String assigneeType,
                                                              Long assigneeId,
                                                              String permissionLevel,
                                                              Long actorUserId) {
        assertRegistrationIsolation();
        if (systemId == null) {
            throw new BusinessException("外部システムIDは必須です。");
        }
        if (!StringUtils.hasText(accountIdentifier)) {
            throw new BusinessException("外部アカウント識別子は必須です。");
        }
        if (!StringUtils.hasText(assigneeType) || assigneeId == null) {
            throw new BusinessException("紐付け先（要員/ユーザー）は必須です。");
        }

        Scope scope = requireCurrentScope();
        String normalizedAssigneeType = assigneeType.trim().toUpperCase(Locale.ROOT);
        assertAssigneeInScope(normalizedAssigneeType, assigneeId, scope);

        ExternalAccountSystem system = externalAccountSystemMapper.selectById(systemId);
        if (system == null) {
            throw new BusinessException("指定された外部システムが見つかりません。");
        }

        ExternalAccountReference ref = ExternalAccountReference.builder()
                .tenantId(scope.tenantId())
                .legalEntityId(scope.legalEntityId())
                .systemId(systemId)
                .accountIdentifier(accountIdentifier.trim())
                .assigneeType(normalizedAssigneeType)
                .assigneeId(assigneeId)
                .permissionLevel(permissionLevel)
                .status("ACTIVE")
                .provisionedAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        externalAccountReferenceMapper.insert(ref);
        revocationOutbox.enqueueLateRegistration(ref, actorUserId);

        log.info("External account reference registered: id={}, system={}, identifier={}",
                ref.getId(), system.getSystemCode(), com.ses.service.ExternalAccountRevocationStates.maskIdentifier(accountIdentifier));
        return ref;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference updateAccountReference(Long id,
                                                            String accountIdentifier,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        Scope scope = requireCurrentScope();
        ExternalAccountReference current = selectScoped(id, scope);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }

        if (current.getIdempotencyKey() != null) {
            throw BusinessException.of(409, "error.asset.revocationTargetFrozen");
        }
        if (StringUtils.hasText(accountIdentifier)) {
            current.setAccountIdentifier(accountIdentifier.trim());
        }
        if (StringUtils.hasText(permissionLevel)) {
            current.setPermissionLevel(permissionLevel);
        }
        updateScoped(current, scope);
        return selectScoped(id, scope);
    }

    @Override
    public ExternalAccountReference confirmRevoke(Long id, Long actorUserId, String source) {
        if (!StringUtils.hasText(source)) {
            throw new BusinessException("失効確認チャネルは必須です。自動処理は専用経路を使用してください。");
        }
        ConfirmationSource confirmationSource;
        try {
            String normalized = source.trim().toUpperCase();
            if ("MANUAL".equals(normalized)) normalized = ConfirmationSource.MANUAL_API.name();
            if ("SYSTEM".equals(normalized)) normalized = ConfirmationSource.SCHEDULER_POLL.name();
            confirmationSource = ConfirmationSource.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("失効確認チャネルが不正です。");
        }
        if (confirmationSource != ConfirmationSource.MANUAL_API && actorUserId != null) {
            throw new BusinessException("自動/外部確認に人間ユーザーIDを指定することはできません。");
        }
        if (confirmationSource == ConfirmationSource.MANUAL_API && actorUserId == null) {
            throw new BusinessException(401, "確認主体のユーザーを解決できないため、失効確認を拒否しました。");
        }
        ActorAttribution attribution = switch (confirmationSource) {
            case MANUAL_API -> ActorAttribution.human(actorUserId, null, null);
            case SCHEDULER_POLL -> ActorAttribution.schedulerPoll(null, null);
            case PROVIDER_SYNC -> ActorAttribution.providerSync(null, null);
            case PROVIDER_CALLBACK -> ActorAttribution.providerCallback(null, null);
            case LEGACY_UNRESOLVED -> ActorAttribution.legacyUnresolved();
        };
        return revokeConfirmationService.confirm(id, attribution);
    }

    @Override
    public ExternalAccountReference confirmRevoke(Long id, ActorAttribution attribution) {
        return revokeConfirmationService.confirm(id, attribution);
    }

    @Override
    public ExternalAccountReference confirmRevokeManually(Long id, Long actorUserId,
                                                           String correlationId, String idempotencyKey) {
        if (actorUserId == null) {
            throw new BusinessException(401, "確認主体のユーザーを解決できないため、失効確認を拒否しました。");
        }
        return revokeConfirmationService.confirm(id, ActorAttribution.human(actorUserId, correlationId, idempotencyKey));
    }

    @Override
    public ExternalAccountReference confirmRevokeFromProviderSync(Long id, String correlationId,
                                                                   String idempotencyKey) {
        return revokeConfirmationService.confirm(id, ActorAttribution.providerSync(correlationId, idempotencyKey));
    }

    @Override
    public ExternalAccountReference confirmRevokeFromProviderCallback(Long id, String providerEventId,
                                                                       String correlationId) {
        return revokeConfirmationService.confirm(id, ActorAttribution.providerCallback(correlationId, providerEventId));
    }

    @Override
    public ExternalAccountReference confirmRevokeFromSchedulerPoll(Long id, String correlationId,
                                                                   String idempotencyKey) {
        return revokeConfirmationService.confirm(id, ActorAttribution.schedulerPoll(correlationId, idempotencyKey));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference requestRevokeWithIdempotency(Long id, String idempotencyKey, Long actorUserId) {
        Scope scope = requireCurrentScope();
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "asset-revoke-" + id;
        return revocationOutbox.request(id, scope.tenantId(), scope.legalEntityId(), key, actorUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference reRequestUnknownRevoke(Long id, Long actorUserId) {
        Scope scope = requireCurrentScope();
        return revocationOutbox.reRequestUnknown(id, scope.tenantId(), scope.legalEntityId(), actorUserId);
    }

    @Override
    public List<ExternalAccountReference> pendingRevocations() {
        String tenant = AccountingTenantContextHolder.requireTenantContext();
        return externalAccountReferenceMapper.selectList(new LambdaQueryWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getTenantId, tenant)
                .in(ExternalAccountReference::getExternalSyncStatus, List.of("REVOKE_REQUESTED", "REVOKE_SENDING",
                        "REVOKE_SEND_FAILED", "REVOKE_SEND_UNKNOWN", "REVOKE_SENT"))
                .ne(ExternalAccountReference::getStatus, "REVOKED")
                .orderByAsc(ExternalAccountReference::getId).last("LIMIT 100"));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    public int processPendingRevokePollJob() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        LocalDateTime now = com.ses.service.ExternalAccountRevocationStates.seconds(LocalDateTime.now());
        List<ExternalAccountReference> pendingList = externalAccountReferenceMapper.selectPendingForTenant(tenantId, now);

        int processed = 0;
        for (ExternalAccountReference ref : pendingList) {
            if (!tenantId.equals(ref.getTenantId()) || ref.getLegalEntityId() == null) {
                log.warn("External account poll skipped because ownership is incomplete: refId={}", ref.getId());
                continue;
            }
            // 各レコードのclaim時点でleaseを開始する。job開始時刻を使い回すと、長いbatchでleaseが短くなる。
            LocalDateTime claimNow = com.ses.service.ExternalAccountRevocationStates.seconds(LocalDateTime.now());
            LocalDateTime leaseUntil = com.ses.service.ExternalAccountRevocationStates.seconds(claimNow.plus(providerConfirmationLease()));
            if (externalAccountReferenceMapper.claimRevokePoll(
                    ref.getId(), ref.getVersion(), claimNow, leaseUntil,
                    tenantId, ref.getLegalEntityId()) != 1) {
                // 別poll worker、手動確認、または要求送信が先にversionを進めた。
                continue;
            }
            ExternalAccountReference claimed = externalAccountReferenceMapper.selectByIdAndScope(
                    ref.getId(), tenantId, ref.getLegalEntityId());
            if (claimed == null) {
                continue;
            }
            try {
                ExternalAccountProviderClient.RevokeConfirmationStatus status =
                        providerClient.checkRevokeConfirmation(claimed);
                if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                    confirmRevokeFromSchedulerPoll(claimed.getId(), "scheduler-poll:" + claimed.getId(), claimed.getIdempotencyKey());
                    processed++;
                } else {
                    persistConfirmationOutcome(claimed, status, LocalDateTime.now());
                }
            } catch (RuntimeException ex) {
                // 1件のprovider障害で後続アカウントのpollを止めない。状態はfail-closedでPENDINGへ戻す。
                persistConfirmationFailure(claimed, LocalDateTime.now());
                log.warn("Provider revoke confirmation failed; retry will continue: refId={}", claimed.getId());
            }
        }
        log.info("Pending revoke polling job processed: count={}, confirmed={}", pendingList.size(), processed);
        return processed;
    }

    private Duration providerConfirmationLease() {
        Duration timeout = providerClient.revokeConfirmationTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_PROVIDER_CONFIRMATION_TIMEOUT;
        }
        return timeout.plus(POLL_LEASE_BUFFER);
    }

    private void persistConfirmationOutcome(ExternalAccountReference ref,
                                            ExternalAccountProviderClient.RevokeConfirmationStatus status,
                                            LocalDateTime now) {
        // claim後に別worker/手動処理がversionを進めていれば、古いworkerのbackoffで上書きしない。
        int retries = ref.getRetryCount() != null ? ref.getRetryCount() + 1 : 1;
        long backoffMinutes = Math.min(1440, 5L << Math.min(retries, 9));
        String nextStatus;
        String nextSyncStatus;
        String nextErrorMessage;
        if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN) {
            nextStatus = "UNKNOWN";
            nextSyncStatus = "SYNC_FAILED";
            nextErrorMessage = "Provider revoke response could not be classified";
        } else if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT) {
            nextStatus = "PENDING_CONFIRMATION";
            nextSyncStatus = "TIMEOUT";
            nextErrorMessage = "Provider revoke confirmation timed out";
        } else {
            nextStatus = "PENDING_CONFIRMATION";
            nextSyncStatus = "SYNC_PENDING";
            nextErrorMessage = "Provider revoke pending confirmation (retry=" + retries + ")";
        }
        if (com.ses.service.ExternalAccountRevocationStates.UNKNOWN.equals(ref.getExternalSyncStatus())) {
            // ACK前の結果不明は確認成功だけで解消する。PENDING/timeout/例外で送信済みと推測しない。
            nextStatus = "UNKNOWN";
            nextSyncStatus = com.ses.service.ExternalAccountRevocationStates.UNKNOWN;
        } else if (ref.getExternalSyncStatus() != null && ref.getExternalSyncStatus().startsWith("REVOKE_")) {
            nextSyncStatus = "UNKNOWN".equals(nextStatus)
                    ? com.ses.service.ExternalAccountRevocationStates.UNKNOWN
                    : com.ses.service.ExternalAccountRevocationStates.SENT;
        }
        int updated = revocationOutbox.completePoll(ref, nextStatus, retries, now.plusMinutes(backoffMinutes),
                nextSyncStatus, nextErrorMessage);
        if (updated != 1) {
            log.debug("Poll result discarded because claim is no longer current: refId={}", ref.getId());
        }
    }

    private void persistConfirmationFailure(ExternalAccountReference ref, LocalDateTime now) {
        try {
            persistConfirmationOutcome(ref, ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT, now);
        } catch (RuntimeException persistFailure) {
            // 既に別workerが終端化した場合などは、そのworkerの結果を壊さずjobだけ継続する。
            log.warn("Could not persist provider revoke retry state: refId={}", ref.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference changeStatus(Long id, String status, Long actorUserId) {
        Scope scope = requireCurrentScope();
        ExternalAccountReference current = selectScoped(id, scope);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if (current.getExternalSyncStatus() != null && current.getExternalSyncStatus().startsWith("REVOKE_")) {
            throw BusinessException.of(409, "error.asset.revocationTargetFrozen");
        }
        current.setStatus(status);
        updateScoped(current, scope);
        return selectScoped(id, scope);
    }

    @Override
    public List<ExternalAccountReference> getActiveAccountsByAssignee(String assigneeType, Long assigneeId) {
        Scope scope = requireCurrentScope();
        return externalAccountReferenceMapper.selectActiveByAssignee(
                assigneeType, assigneeId, scope.tenantId(), scope.legalEntityId());
    }

    @Override
    public List<ExternalAccountReference> getUnrevokedAccountsForEngineer(String tenantId, Long engineerId) {
        if (!AccountingTenantContextHolder.requireTenantContext().equals(tenantId)) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        return externalAccountReferenceMapper.selectUnrevokedForEngineer(tenantId, engineerId);
    }

    @Override
    public List<ExternalAccountSystem> getAllSystems() {
        return externalAccountSystemMapper.selectList(new LambdaQueryWrapper<ExternalAccountSystem>()
                .eq(ExternalAccountSystem::getIsActive, 1));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountSystem saveSystem(ExternalAccountSystem system) {
        if (system == null) {
            throw new BusinessException("外部システム情報は必須です。");
        }
        if (system.getId() == null) {
            externalAccountSystemMapper.insert(system);
        } else {
            externalAccountSystemMapper.updateById(system);
        }
        return system;
    }

    @Override
    public IPage<ExternalAccountReference> searchAccounts(int page, int size, Long systemId, String assigneeType, Long assigneeId, String status) {
        return searchAccountsScoped(page, size, systemId, assigneeType, assigneeId, status, null);
    }

    @Override
    public IPage<ExternalAccountReference> searchAccountsScoped(int page, int size, Long systemId,
                                                                String assigneeType, Long assigneeId, String status,
                                                                List<Long> accessibleEngineerIds) {
        Scope scope = requireCurrentScope();
        Page<ExternalAccountReference> pageable = new Page<>(page, size);
        LambdaQueryWrapper<ExternalAccountReference> query = new LambdaQueryWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getTenantId, scope.tenantId())
                .eq(ExternalAccountReference::getLegalEntityId, scope.legalEntityId());
        if (systemId != null) {
            query.eq(ExternalAccountReference::getSystemId, systemId);
        }
        if (StringUtils.hasText(assigneeType)) {
            query.eq(ExternalAccountReference::getAssigneeType, assigneeType);
        }
        if (assigneeId != null) {
            query.eq(ExternalAccountReference::getAssigneeId, assigneeId);
        }
        if (StringUtils.hasText(status)) {
            query.eq(ExternalAccountReference::getStatus, status);
        }
        if (accessibleEngineerIds != null) {
            if (accessibleEngineerIds.isEmpty()) {
                query.eq(ExternalAccountReference::getId, -1L);
            } else {
                query.eq(ExternalAccountReference::getAssigneeType, "ENGINEER")
                        .in(ExternalAccountReference::getAssigneeId, accessibleEngineerIds);
            }
        }
        query.orderByDesc(ExternalAccountReference::getId);
        return externalAccountReferenceMapper.selectPage(pageable, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteAccount(Long id) {
        // AS-R1.5(b)/(f): 外部アカウント参照は状態にかかわらず履歴台帳として保持する。
        Scope scope = requireCurrentScope();
        ExternalAccountReference ref = externalAccountReferenceMapper.selectByIdForUpdate(
                id, scope.tenantId(), scope.legalEntityId());
        if (ref == null) return;
        String st = ref.getStatus();
        throw new BusinessException("外部アカウント参照の終端履歴を含む履歴は状態（" + st + "）にかかわらず論理削除できません。台帳上の履歴を保持してください。");
    }

    private Scope requireCurrentScope() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        String securityTenantId = legalEntityContextService.requireTenantId();
        if (!tenantId.equals(securityTenantId)) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        Long legalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
        return new Scope(tenantId, legalEntityId);
    }

    /** REQUIREDは外側のRRをRCへ変更しない。scopeの読取りより前に既存transactionも検査する。 */
    private void assertRegistrationIsolation() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        Integer declared = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Integer actual = declared != null ? declared : jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Integer>) java.sql.Connection::getTransactionIsolation);
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(actual)) {
            throw BusinessException.of(409, "error.asset.revocationIsolationRequired");
        }
    }

    private ExternalAccountReference selectScoped(Long id, Scope scope) {
        return externalAccountReferenceMapper.selectByIdAndScope(id, scope.tenantId(), scope.legalEntityId());
    }

    private void updateScoped(ExternalAccountReference reference, Scope scope) {
        int updated = externalAccountReferenceMapper.update(reference,
                new LambdaUpdateWrapper<ExternalAccountReference>()
                        .eq(ExternalAccountReference::getId, reference.getId())
                        .eq(ExternalAccountReference::getTenantId, scope.tenantId())
                        .eq(ExternalAccountReference::getLegalEntityId, scope.legalEntityId()));
        if (updated != 1) {
            throw new BusinessException(409, "外部アカウント参照の更新が他の操作と競合しました。");
        }
    }

    private void assertAssigneeInScope(String assigneeType, Long assigneeId, Scope scope) {
        if ("ENGINEER".equals(assigneeType)) {
            Engineer engineer = engineerMapper.selectByIdForUpdateForTenant(assigneeId, scope.tenantId());
            if (engineer == null || !Objects.equals(scope.legalEntityId(), engineer.getLegalEntityId())) {
                throw BusinessException.of(403, "ASSIGNEE_SCOPE_MISMATCH");
            }
            return;
        }
        if ("USER".equals(assigneeType)) {
            SysUser user = sysUserMapper.selectByIdAndTenant(assigneeId, scope.tenantId());
            LocalDate asOf = legalEntityContextService.requireCurrentDate();
            List<Long> legalEntityIds = attendanceScopeMapper.selectLegalEntityIdsByUser(assigneeId, asOf);
            if (user == null || legalEntityIds == null || !legalEntityIds.contains(scope.legalEntityId())) {
                throw BusinessException.of(403, "ASSIGNEE_SCOPE_MISMATCH");
            }
            return;
        }
        throw new BusinessException(400, "紐付け先区分が不正です。");
    }

    private record Scope(String tenantId, Long legalEntityId) {
    }
}
