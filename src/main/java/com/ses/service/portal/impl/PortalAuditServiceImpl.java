package com.ses.service.portal.impl;

import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.SecurityHashUtil;
import com.ses.entity.PortalAccessLog;
import com.ses.mapper.PortalAccessLogMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * ポータル操作監査の実装。IPはSHA-256 hashのみ保存し、監査記録の失敗は業務を妨げない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAuditServiceImpl implements PortalAuditService {

    private final PortalAccessLogMapper accessLogMapper;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    @Override
    public void record(PortalLoginUser user, String action, String targetType, Long targetId,
                       HttpServletRequest request) {
        if (user == null) {
            return;
        }
        try {
            PortalAccessLog entry = new PortalAccessLog();
            entry.setPortalUserId(user.getPortalUserId());
            entry.setPortalOrgId(user.getPortalOrgId());
            entry.setEmail(user.getEmail());
            entry.setOrgType(user.getOrgType());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setIpHash(SecurityHashUtil.sha256(clientIp(request)));
            entry.setUserAgent(truncate(request == null ? null : request.getHeader("User-Agent"), 512));
            entry.setCreatedAt(LocalDateTime.now(clock));
            accessLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("portal監査記録に失敗しました: user={} action={} target={} error={}",
                    user.getPortalUserId(), action, targetType, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
