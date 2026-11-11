package com.ses.service.pwa;

import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/** PWA復元経路の現在DB認可を独立read-only transactionで再検査する。 */
@Service
@RequiredArgsConstructor
public class PwaRestoreIdentityService {
    private final SysUserMapper sysUserMapper;
    private final EngineerAccountLinkMapper linkMapper;
    private final EngineerMapper engineerMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public CurrentIdentity load(Long userId, String tenantId) {
        if (userId == null || tenantId == null || tenantId.isBlank()) return null;
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenantId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !"要員".equals(user.getRole()) || user.getPassword() == null) return null;
        EngineerAccountLink link = linkMapper.selectByUserIdAndTenant(userId, tenantId);
        if (link == null || link.getId() == null || link.getEngineerId() == null || link.getLinkedAt() == null
                || !engineerMapper.selectOwnedEngineerIds(tenantId).contains(link.getEngineerId())) return null;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        return new CurrentIdentity(user.getId(), user.getUsername(), user.getTenantId(), user.getRole(),
                user.getPassword(), user.getLockedUntil(), link.getId(), link.getEngineerId(),
                link.getLinkedAt().toInstant(java.time.ZoneOffset.UTC), locked);
    }

    public record CurrentIdentity(Long userId, String username, String tenantId, String role,
                                  String passwordHash, LocalDateTime lockedUntil,
                                  Long linkId, Long engineerId, Instant linkedAt, boolean locked) { }
}
