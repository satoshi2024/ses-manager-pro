package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.service.ExternalAccountService;
import com.ses.service.ExternalAccountRevokeConfirmationService;
import com.ses.service.provider.ExternalAccountProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountServiceImpl implements ExternalAccountService {

    private final ExternalAccountSystemMapper externalAccountSystemMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final ExternalAccountProviderClient providerClient;
    private final ExternalAccountRevokeConfirmationService revokeConfirmationService;

    private static String maskIdentifier(String id) {
        if (!StringUtils.hasText(id)) return "***";
        int atIndex = id.indexOf('@');
        if (atIndex > 2) {
            return id.substring(0, 2) + "***" + id.substring(atIndex);
        } else if (id.length() > 4) {
            return id.substring(0, 2) + "***" + id.substring(id.length() - 2);
        }
        return "***";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference registerAccountReference(Long systemId,
                                                              String accountIdentifier,
                                                              String assigneeType,
                                                              Long assigneeId,
                                                              String permissionLevel,
                                                              Long actorUserId) {
        if (systemId == null) {
            throw new BusinessException("外部システムIDは必須です。");
        }
        if (!StringUtils.hasText(accountIdentifier)) {
            throw new BusinessException("外部アカウント識別子は必須です。");
        }
        if (!StringUtils.hasText(assigneeType) || assigneeId == null) {
            throw new BusinessException("紐付け先（要員/ユーザー）は必須です。");
        }

        ExternalAccountSystem system = externalAccountSystemMapper.selectById(systemId);
        if (system == null) {
            throw new BusinessException("指定された外部システムが見つかりません。");
        }

        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(systemId)
                .accountIdentifier(accountIdentifier.trim())
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .permissionLevel(permissionLevel)
                .status("ACTIVE")
                .provisionedAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        externalAccountReferenceMapper.insert(ref);

        log.info("External account reference registered: id={}, system={}, identifier={}",
                ref.getId(), system.getSystemCode(), maskIdentifier(accountIdentifier));
        return ref;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference updateAccountReference(Long id,
                                                            String accountIdentifier,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        ExternalAccountReference current = externalAccountReferenceMapper.selectById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }

        if (StringUtils.hasText(accountIdentifier)) {
            current.setAccountIdentifier(accountIdentifier.trim());
        }
        if (StringUtils.hasText(permissionLevel)) {
            current.setPermissionLevel(permissionLevel);
        }
        externalAccountReferenceMapper.updateById(current);
        return current;
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    public ExternalAccountReference requestRevokeWithIdempotency(Long id, String idempotencyKey, Long actorUserId) {
        ExternalAccountReference current = externalAccountReferenceMapper.selectById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(current.getStatus())) {
            return current;
        }

        String requestKey = StringUtils.hasText(idempotencyKey)
                ? idempotencyKey.trim()
                : "asset-revoke-" + id;

        ExternalAccountReference sameKey = externalAccountReferenceMapper.selectByIdempotencyKey(requestKey);
        if (sameKey != null && !id.equals(sameKey.getId())) {
            throw new BusinessException(409, "失効要求の冪等性キーが別のアカウントに割り当て済みです。");
        }
        if (StringUtils.hasText(current.getIdempotencyKey())
                && !requestKey.equals(current.getIdempotencyKey())) {
            throw new BusinessException(409, "このアカウントには別の失効要求が進行中です。");
        }
        // 既に同じkeyで要求済みならproviderへ再送しない。確認処理はpoll jobへ委譲する。
        if (requestKey.equals(current.getIdempotencyKey())) {
            return current;
        }

        LocalDateTime requestedAt = LocalDateTime.now();
        int claimed;
        try {
            claimed = externalAccountReferenceMapper.claimRevokeRequest(id, requestKey, requestedAt, actorUserId);
        } catch (DuplicateKeyException ex) {
            // 同一keyを別accountが先にclaimした場合はunique制約違反を500へ漏らさず、契約上409で返す。
            throw new BusinessException(409, "失効要求の冪等性キーが別のアカウントに割り当て済みです。");
        }
        if (claimed != 1) {
            // 同一keyの並行claimは先着だけがproviderを呼ぶ。後着はcommit済み状態を再読する。
            ExternalAccountReference latest = externalAccountReferenceMapper.selectById(id);
            if (latest != null && requestKey.equals(latest.getIdempotencyKey())) {
                return latest;
            }
            throw new BusinessException(409, "失効要求の登録が他の操作と競合しました。再試行してください。");
        }
        current = externalAccountReferenceMapper.selectById(id);

        // プロバイダへ失効リクエスト
        boolean sent;
        try {
            sent = providerClient.requestRevoke(current);
        } catch (RuntimeException ex) {
            current.setExternalSyncStatus("SYNC_FAILED");
            current.setLastErrorMessage("Provider revoke request failed");
            current.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
            externalAccountReferenceMapper.updateById(current);
            return externalAccountReferenceMapper.selectById(id);
        }
        if (sent) {
            ExternalAccountProviderClient.RevokeConfirmationStatus conf;
            try {
                conf = providerClient.checkRevokeConfirmation(current);
            } catch (RuntimeException ex) {
                // 確認APIの通信例外も失効成功にはしない。次回poll用の状態を保存して呼出側へ返す。
                persistConfirmationFailure(current, LocalDateTime.now());
                return externalAccountReferenceMapper.selectById(id);
            }
            if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                confirmRevokeFromProviderSync(id, "provider-sync:" + id, requestKey);
            } else if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN) {
                current.setStatus("UNKNOWN");
                current.setLastErrorMessage("Provider revoke response could not be classified");
                current.setExternalSyncStatus("SYNC_FAILED");
                externalAccountReferenceMapper.updateById(current);
            } else if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT) {
                current.setStatus("PENDING_CONFIRMATION");
                current.setLastErrorMessage("Provider revoke confirmation timed out");
                current.setExternalSyncStatus("TIMEOUT");
                // 初回timeoutは直後のpollを許可し、poll側で指数backoffの次回時刻を確定する。
                current.setNextRetryAt(LocalDateTime.now());
                externalAccountReferenceMapper.updateById(current);
            }
        } else {
            current.setStatus("PENDING_CONFIRMATION");
            current.setLastErrorMessage("Provider revoke request was not accepted");
            current.setExternalSyncStatus("SYNC_FAILED");
            current.setNextRetryAt(LocalDateTime.now());
            externalAccountReferenceMapper.updateById(current);
        }
        return externalAccountReferenceMapper.selectById(id);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    public int processPendingRevokePollJob() {
        LocalDateTime now = LocalDateTime.now();
        List<ExternalAccountReference> pendingList = externalAccountReferenceMapper.selectList(
                new LambdaQueryWrapper<ExternalAccountReference>()
                        .in(ExternalAccountReference::getStatus, List.of("PENDING_CONFIRMATION", "SUSPENDED", "UNKNOWN"))
                        .and(w -> w.isNull(ExternalAccountReference::getNextRetryAt)
                                .or(ow -> ow.le(ExternalAccountReference::getNextRetryAt, now)))
        );

        int processed = 0;
        for (ExternalAccountReference ref : pendingList) {
            try {
                ExternalAccountProviderClient.RevokeConfirmationStatus status =
                        providerClient.checkRevokeConfirmation(ref);
                if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                    confirmRevokeFromSchedulerPoll(ref.getId(), "scheduler-poll:" + ref.getId(), ref.getIdempotencyKey());
                    processed++;
                } else {
                    persistConfirmationOutcome(ref, status, now);
                }
            } catch (RuntimeException ex) {
                // 1件のprovider障害で後続アカウントのpollを止めない。状態はfail-closedでPENDINGへ戻す。
                persistConfirmationFailure(ref, now);
                log.warn("Provider revoke confirmation failed; retry will continue: refId={}", ref.getId());
            }
        }
        log.info("Pending revoke polling job processed: count={}, confirmed={}", pendingList.size(), processed);
        return processed;
    }

    private void persistConfirmationOutcome(ExternalAccountReference ref,
                                            ExternalAccountProviderClient.RevokeConfirmationStatus status,
                                            LocalDateTime now) {
        ExternalAccountReference latest = externalAccountReferenceMapper.selectById(ref.getId());
        if (latest == null || "REVOKED".equals(latest.getStatus())) {
            return;
        }
        int retries = latest.getRetryCount() != null ? latest.getRetryCount() + 1 : 1;
        long backoffMinutes = Math.min(1440, (long) Math.pow(2, Math.min(retries, 8)) * 5);
        latest.setRetryCount(retries);
        latest.setNextRetryAt(now.plusMinutes(backoffMinutes));
        if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN) {
            latest.setStatus("UNKNOWN");
            latest.setExternalSyncStatus("SYNC_FAILED");
            latest.setLastErrorMessage("Provider revoke response could not be classified");
        } else if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT) {
            latest.setStatus("PENDING_CONFIRMATION");
            latest.setExternalSyncStatus("TIMEOUT");
            latest.setLastErrorMessage("Provider revoke confirmation timed out");
        } else {
            latest.setStatus("PENDING_CONFIRMATION");
            latest.setExternalSyncStatus("SYNC_PENDING");
            latest.setLastErrorMessage("Provider revoke pending confirmation (retry=" + retries + ")");
        }
        externalAccountReferenceMapper.updateById(latest);
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
        ExternalAccountReference current = externalAccountReferenceMapper.selectById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        current.setStatus(status);
        externalAccountReferenceMapper.updateById(current);
        return current;
    }

    @Override
    public List<ExternalAccountReference> getActiveAccountsByAssignee(String assigneeType, Long assigneeId) {
        return externalAccountReferenceMapper.selectActiveByAssignee(assigneeType, assigneeId);
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
        Page<ExternalAccountReference> pageable = new Page<>(page, size);
        LambdaQueryWrapper<ExternalAccountReference> query = new LambdaQueryWrapper<>();
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
        ExternalAccountReference ref = externalAccountReferenceMapper.selectByIdForUpdate(id);
        if (ref == null) return;
        String st = ref.getStatus();
        throw new BusinessException("外部アカウント参照の終端履歴を含む履歴は状態（" + st + "）にかかわらず論理削除できません。台帳上の履歴を保持してください。");
    }
}
