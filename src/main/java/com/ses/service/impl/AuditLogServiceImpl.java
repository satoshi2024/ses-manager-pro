package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AuditLog;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.mapper.AuditLogMapper;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    @Override
    public void record(String username, String method, String uri, int status) {
        record(username, method, uri, status, "ses-manager", status >= 200 && status < 400);
    }

    @Override
    public void record(String username, String method, String uri, int status, String applicationCode, boolean successFlag) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUsername(username);
            entry.setMethod(method);
            entry.setUri(uri);
            entry.setStatus(status);
            entry.setApplicationCode(applicationCode);
            entry.setSuccessFlag(successFlag);
            applyRequestAttribution(entry);
            applyContext(entry);
            entry.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 監査ログの永続化失敗は本来のAPI処理に影響させない
            log.warn("監査ログの記録に失敗: method={} status={} exceptionClass={} detail={}",
                    method, status, LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
        }
    }

    @Override
    public void recordRequired(String username, String method, String uri, int status,
                               String applicationCode, boolean successFlag) {
        AuditLog entry = new AuditLog();
        entry.setUsername(username);
        entry.setMethod(method);
        entry.setUri(uri);
        entry.setStatus(status);
        entry.setApplicationCode(applicationCode);
        entry.setSuccessFlag(successFlag);
        applyRequestAttribution(entry);
        applyContext(entry);
        entry.setCreatedAt(LocalDateTime.now());
        if (auditLogMapper.insert(entry) != 1) {
            throw new IllegalStateException("重要security監査を永続化できません");
        }
    }

    @Override
    public void recordDomainEventRequired(AuditLog entry) {
        if (entry == null || entry.getActorType() == null || entry.getConfirmationSource() == null) {
            throw new IllegalArgumentException("ドメイン監査には主体とチャネルが必要です");
        }
        try {
            new ActorAttribution(
                    ActorType.valueOf(entry.getActorType()),
                    ConfirmationSource.valueOf(entry.getConfirmationSource()),
                    entry.getHumanUserId(), entry.getCorrelationId(), entry.getIdempotencyKey());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ドメイン監査の主体/チャネルが不正です", ex);
        }
        entry.setCreatedAt(entry.getCreatedAt() != null ? entry.getCreatedAt() : LocalDateTime.now());
        if (auditLogMapper.insert(entry) != 1) {
            throw new IllegalStateException("ドメイン監査を永続化できません");
        }
    }

    private void applyRequestAttribution(AuditLog entry) {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null && userId > 0) {
            entry.setActorType(ActorType.HUMAN.name());
            entry.setConfirmationSource(ConfirmationSource.MANUAL_API.name());
            entry.setHumanUserId(userId);
        } else {
            entry.setActorType(ActorType.LEGACY_UNRESOLVED.name());
            entry.setConfirmationSource(ConfirmationSource.LEGACY_UNRESOLVED.name());
            entry.setHumanUserId(null);
        }
    }

    @Override
    public Page<AuditLog> page(long current, long size, String username, String method) {
        Page<AuditLog> page = new Page<>(current, size);
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            qw.like(AuditLog::getUsername, username);
        }
        if (StringUtils.hasText(method)) {
            qw.eq(AuditLog::getMethod, method);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(page, qw);
    }

    private void applyContext(AuditLog entry) {
        entry.setCorrelationId(CorrelationContext.get(CorrelationContext.CORRELATION_ID));
        entry.setInvoiceId(CorrelationContext.get(CorrelationContext.INVOICE_ID));
        entry.setDigitalInvoiceId(CorrelationContext.get(CorrelationContext.DIGITAL_INVOICE_ID));
        entry.setJobId(CorrelationContext.get(CorrelationContext.JOB_ID));
        entry.setProviderOperationId(CorrelationContext.get(CorrelationContext.PROVIDER_OPERATION_ID));
        entry.setErrorCode(CorrelationContext.get(CorrelationContext.ERROR_CODE));
        entry.setErrorCategory(CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));
    }
}
