package com.ses.service.impl;

import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ActorType;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AuditLog;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.SysUser;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetEventService;
import com.ses.service.AuditLogService;
import com.ses.service.ExternalAccountRevokeConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 失効確認の単一書込み境界。
 * CAS成功後のイベントと監査が失敗した場合も、このトランザクション全体をロールバックする。
 */
@Service
@RequiredArgsConstructor
public class ExternalAccountRevokeConfirmationServiceImpl implements ExternalAccountRevokeConfirmationService {

    private final ExternalAccountReferenceMapper referenceMapper;
    private final SysUserMapper sysUserMapper;
    private final AssetEventService assetEventService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExternalAccountReference confirm(Long referenceId, ActorAttribution attribution) {
        if (referenceId == null || attribution == null) {
            throw new BusinessException("失効確認対象と確認主体は必須です。");
        }

        SysUser human = null;
        if (attribution.actorType() == ActorType.HUMAN) {
            human = sysUserMapper.selectById(attribution.humanUserId());
            if (human == null || !Integer.valueOf(1).equals(human.getStatus())) {
                throw new BusinessException(401, "確認主体のユーザーを解決できないため、失効確認を拒否しました。");
            }
        }

        ExternalAccountReference current = referenceMapper.selectById(referenceId);
        if (current == null) {
            throw new BusinessException("指定されたアカウント参照が見つかりません。");
        }
        if ("REVOKED".equals(current.getStatus())) {
            return current;
        }

        String correlationId = hasText(attribution.correlationId())
                ? attribution.correlationId() : "external-revoke-confirm:" + referenceId;
        String idempotencyKey = hasText(attribution.idempotencyKey())
                ? attribution.idempotencyKey()
                : (hasText(current.getIdempotencyKey())
                ? current.getIdempotencyKey() : "external-revoke-confirm:" + referenceId);
        ActorAttribution effectiveAttribution = new ActorAttribution(
                attribution.actorType(), attribution.confirmationSource(), attribution.humanUserId(),
                correlationId, idempotencyKey);
        String beforeState = current.getStatus();
        LocalDateTime confirmedAt = LocalDateTime.now();
        Long humanUserId = effectiveAttribution.humanUserId();
        int rows = referenceMapper.confirmRevokeWithCas(
                referenceId,
                confirmedAt,
                humanUserId,
                effectiveAttribution.actorType().name(),
                effectiveAttribution.confirmationSource().name(),
                current.getVersion());
        if (rows != 1) {
            throw new BusinessException(409, "失効確認の排他更新に失敗しました。他の操作と競合した可能性があります。");
        }

        current.setStatus("REVOKED");
        current.setRevokeConfirmedAt(confirmedAt);
        current.setRevokeConfirmedBy(humanUserId);
        current.setActorType(effectiveAttribution.actorType().name());
        current.setConfirmationSource(effectiveAttribution.confirmationSource().name());
        current.setRevokeConfirmedSource(effectiveAttribution.confirmationSource().name());
        if (current.getVersion() != null) {
            current.setVersion(current.getVersion() + 1);
        }

        assetEventService.recordExternalAccountConfirmation(
                referenceId,
                "EXTERNAL_ACCOUNT_REVOKE_CONFIRMED",
                beforeState,
                "REVOKED",
                effectiveAttribution,
                "外部アカウント失効確認",
                "{\"referenceId\":" + referenceId + ",\"actorType\":\""
                        + attribution.actorType().name() + "\",\"confirmationSource\":\""
                        + attribution.confirmationSource().name() + "\"}");

        AuditLog audit = new AuditLog();
        audit.setUsername(human != null ? human.getUsername() : effectiveAttribution.actorType().name());
        audit.setMethod("POST");
        audit.setUri("/api/external-accounts/" + referenceId + "/confirm-revoke");
        audit.setStatus(200);
        audit.setApplicationCode("EXTERNAL_ACCOUNT_REVOKE_CONFIRMED");
        audit.setSuccessFlag(true);
        audit.setReferenceType("EXTERNAL_ACCOUNT_REFERENCE");
        audit.setReferenceId(referenceId);
        audit.setActorType(effectiveAttribution.actorType().name());
        audit.setConfirmationSource(effectiveAttribution.confirmationSource().name());
        audit.setHumanUserId(humanUserId);
        audit.setBeforeState(beforeState);
        audit.setAfterState("REVOKED");
        audit.setCorrelationId(effectiveAttribution.correlationId());
        audit.setIdempotencyKey(effectiveAttribution.idempotencyKey());
        auditLogService.recordDomainEventRequired(audit);
        return current;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
