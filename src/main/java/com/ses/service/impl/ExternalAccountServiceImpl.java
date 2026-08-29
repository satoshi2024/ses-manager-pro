package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.service.ExternalAccountService;
import com.ses.service.provider.ExternalAccountProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountServiceImpl extends ServiceImpl<ExternalAccountReferenceMapper, ExternalAccountReference> implements ExternalAccountService {

    private final ExternalAccountSystemMapper externalAccountSystemMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final ExternalAccountProviderClient providerClient;

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
    @Transactional
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
        save(ref);

        log.info("External account reference registered: id={}, system={}, identifier={}",
                ref.getId(), system.getSystemCode(), maskIdentifier(accountIdentifier));
        return ref;
    }

    @Override
    @Transactional
    public ExternalAccountReference updateAccountReference(Long id,
                                                            String accountIdentifier,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }

        if (StringUtils.hasText(accountIdentifier)) {
            current.setAccountIdentifier(accountIdentifier.trim());
        }
        if (StringUtils.hasText(permissionLevel)) {
            current.setPermissionLevel(permissionLevel);
        }
        updateById(current);
        return current;
    }

    @Override
    @Transactional
    public ExternalAccountReference confirmRevoke(Long id, Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(current.getStatus())) {
            return current;
        }

        LocalDateTime confirmedAt = LocalDateTime.now();
        int rows = externalAccountReferenceMapper.confirmRevokeWithCas(id, confirmedAt, actorUserId, current.getVersion());
        if (rows == 0) {
            throw new BusinessException("失効確認の排他更新に失敗しました。他の操作と競合した可能性があります。");
        }

        current.setStatus("REVOKED");
        current.setRevokeConfirmedAt(confirmedAt);
        current.setRevokeConfirmedBy(actorUserId);
        log.info("External account reference confirmed revoked: id={}, actorUserId={}", id, actorUserId);
        return current;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExternalAccountReference requestRevokeWithIdempotency(Long id, String idempotencyKey, Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(current.getStatus())) {
            return current;
        }

        if (StringUtils.hasText(idempotencyKey)) {
            current.setIdempotencyKey(idempotencyKey);
        }
        current.setStatus("PENDING_CONFIRMATION");
        current.setRevokeRequestedAt(LocalDateTime.now());
        current.setRetryCount(0);
        current.setNextRetryAt(LocalDateTime.now());
        updateById(current);

        // プロバイダへ失効リクエスト
        boolean sent = providerClient.requestRevoke(current);
        if (sent) {
            ExternalAccountProviderClient.RevokeConfirmationStatus conf = providerClient.checkRevokeConfirmation(current);
            if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                confirmRevoke(id, actorUserId);
            } else if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN) {
                current.setStatus("UNKNOWN");
                current.setLastErrorMessage("Provider revoke response could not be classified");
                updateById(current);
            }
        }
        return getById(id);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
            ExternalAccountProviderClient.RevokeConfirmationStatus status = providerClient.checkRevokeConfirmation(ref);
            if (status == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                confirmRevoke(ref.getId(), 1L);
                processed++;
            } else {
                // 指数バックオフ
                int retries = ref.getRetryCount() != null ? ref.getRetryCount() + 1 : 1;
                ref.setRetryCount(retries);
                long backoffMinutes = Math.min(1440, (long) Math.pow(2, Math.min(retries, 8)) * 5);
                ref.setNextRetryAt(now.plusMinutes(backoffMinutes));
                ref.setLastErrorMessage("Provider revoke pending confirmation (retry=" + retries + ")");
                updateById(ref);
            }
        }
        log.info("Pending revoke polling job processed: count={}, confirmed={}", pendingList.size(), processed);
        return processed;
    }

    @Override
    @Transactional
    public ExternalAccountReference changeStatus(Long id, String status, Long actorUserId) {
        ExternalAccountReference current = getById(id);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        current.setStatus(status);
        updateById(current);
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
    @Transactional
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
    @Transactional
    public void softDeleteAccount(Long id) {
        // AS-R1.5(b): 未失効状態（UNKNOWNを含む）の論理削除を禁止する
        ExternalAccountReference ref = externalAccountReferenceMapper.selectByIdForUpdate(id);
        if (ref == null) return;
        String st = ref.getStatus();
        boolean confirmed = ref.getRevokeConfirmedAt() != null;
        if ("REVOKED".equals(st) || confirmed) {
            throw new BusinessException(
                    "失効済み外部アカウントの終端履歴は論理削除できません。台帳上の履歴を保持してください。" );
        }
        if (("ACTIVE".equals(st) || "SUSPENDED".equals(st) || "PENDING_CONFIRMATION".equals(st)
                || "UNKNOWN".equals(st)) && !confirmed) {
            throw new BusinessException(
                    "未失効（ACTIVE/SUSPENDED/PENDING_CONFIRMATION/UNKNOWN）の外部アカウントは論理削除できません（AS-R1.5(b)）。" +
                            "先に失効確認（REVOKED）またはEXCEPTION_HOLD処理を行ってください。");
        }
        removeById(id);
        log.info("ExternalAccountReference soft-deleted: id={}", id);
    }
}
