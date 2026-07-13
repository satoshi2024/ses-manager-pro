package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.AuditLog;
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
        try {
            AuditLog entry = new AuditLog();
            entry.setUsername(username);
            entry.setMethod(method);
            entry.setUri(uri);
            entry.setStatus(status);
            entry.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 監査ログの永続化失敗は本来のAPI処理に影響させない
            log.warn("監査ログの記録に失敗しました: user={} {} {}", username, method, uri, e);
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
}
