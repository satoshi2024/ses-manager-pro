package com.ses.service.portal.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.entity.PortalAccessLog;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalSession;
import com.ses.entity.PortalUser;
import com.ses.entity.PortalUserPermission;
import com.ses.mapper.PortalAccessLogMapper;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalSessionMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.PortalUserPermissionMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.portal.PortalAdminService;
import com.ses.service.portal.PortalMailService;
import com.ses.service.portal.PortalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * portal管理の実装。営業のDataScope（自担当顧客のportal組織のみ）と管理者全件を分岐する（design §6.2）。
 * 招待tokenは256bit random・DBはSHA-256 hash・72時間有効（G3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAdminServiceImpl implements PortalAdminService {

    private static final Set<String> ORG_TYPES = Set.of("CUSTOMER", "BP");
    private static final Set<String> ORG_STATUSES = Set.of("ACTIVE", "SUSPENDED");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "SUSPENDED");
    private static final Set<String> INVITE_ROLES = Set.of("MEMBER", "ADMIN");
    private static final long INVITE_TTL_HOURS = 72;

    private final PortalOrganizationMapper organizationMapper;
    private final PortalUserMapper userMapper;
    private final PortalInvitationMapper invitationMapper;
    private final PortalUserPermissionMapper permissionMapper;
    private final PortalTermsConsentMapper termsConsentMapper;
    private final PortalAccessLogMapper accessLogMapper;
    private final PortalSessionMapper sessionMapper;
    private final PortalSessionService sessionService;
    private final PortalMailService mailService;
    private final SystemConfigService systemConfigService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public Page<PortalOrganization> orgs(long current, long size, Set<Long> allowedCustomerIds, boolean fullAccess) {
        LambdaQueryWrapper<PortalOrganization> wrapper = new LambdaQueryWrapper<PortalOrganization>()
                .orderByDesc(PortalOrganization::getId);
        if (!fullAccess) {
            // 営業: 自担当顧客のCUSTOMER組織のみ。BP組織は管理者のみ（design §6.2）
            if (allowedCustomerIds == null) {
                wrapper.eq(PortalOrganization::getType, "CUSTOMER");
            } else if (allowedCustomerIds.isEmpty()) {
                return new Page<>(current, Math.min(size, 1000), 0);
            } else {
                wrapper.eq(PortalOrganization::getType, "CUSTOMER")
                        .in(PortalOrganization::getCustomerId, allowedCustomerIds);
            }
        }
        return organizationMapper.selectPage(new Page<>(current, Math.min(size, 1000)), wrapper);
    }

    @Override
    public PortalOrganization orgById(Long orgId) {
        return organizationMapper.selectById(orgId);
    }

    @Override
    public PortalUser userById(Long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalOrganization createOrg(String type, Long customerId, Long bpCompanyId) {
        if (type == null || !ORG_TYPES.contains(type)) {
            throw BusinessException.of(400, "error.portal.admin.invalidOrgType");
        }
        if ("CUSTOMER".equals(type) && customerId == null) {
            throw BusinessException.of(400, "error.portal.admin.customerRequired");
        }
        if ("BP".equals(type) && bpCompanyId == null) {
            throw BusinessException.of(400, "error.portal.admin.bpRequired");
        }
        if (organizationMapper.selectByCustomerId(customerId) != null
                || organizationMapper.selectByBpCompanyId(bpCompanyId) != null) {
            throw BusinessException.of(409, "error.portal.admin.orgAlreadyExists");
        }
        PortalOrganization org = new PortalOrganization();
        org.setType(type);
        org.setCustomerId(customerId);
        org.setBpCompanyId(bpCompanyId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        return org;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setOrgStatus(Long orgId, String status) {
        if (status == null || !ORG_STATUSES.contains(status)) {
            throw BusinessException.of(400, "error.portal.admin.invalidStatus");
        }
        PortalOrganization org = requireOrg(orgId);
        int updated = organizationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalOrganization>()
                        .eq("id", orgId)
                        .set("status", status));
        if (updated == 0) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if ("SUSPENDED".equals(status)) {
            sessionService.revokeAllForOrg(orgId, "ORG_SUSPEND");
        }
    }

    @Override
    public Page<PortalUser> users(long current, long size, Long orgId) {
        requireOrg(orgId);
        return userMapper.selectPage(new Page<>(current, Math.min(size, 1000)),
                new LambdaQueryWrapper<PortalUser>()
                        .eq(PortalUser::getPortalOrgId, orgId)
                        .orderByDesc(PortalUser::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setUserStatus(Long userId, String status) {
        if (status == null || !USER_STATUSES.contains(status)) {
            throw BusinessException.of(400, "error.portal.admin.invalidStatus");
        }
        PortalUser user = requireUser(userId);
        int updated = userMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                        .eq("id", userId)
                        .set("status", status));
        if (updated == 0) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if ("SUSPENDED".equals(status)) {
            sessionService.revokeAllForUser(userId, "SUSPEND");
            // 停止されたuserの未使用invitationを失効させる（S13-R1-P0-01: 自己復活経路の遮断）
            invitationMapper.expireActiveByEmail(user.getEmail(), LocalDateTime.now(clock));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetUserMfa(Long userId) {
        PortalUser user = requireUser(userId);
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", userId)
                .set("totp_secret_encrypted", null)
                .set("totp_secret_key_version", null)
                .set("mfa_enabled_at", null)
                .set("recovery_code_hash", null)
                .set("recovery_code_used_at", null)
                .set("last_used_step", null));
        sessionService.revokeAllForUser(userId, "MFA_RESET");
        log.info("portal MFA reset: userId={}（操作者: 管理者）", userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setUserOrgAdmin(Long userId, boolean orgAdmin) {
        PortalUser user = requireUser(userId);
        PortalUserPermission existing = permissionMapper.selectOne(new LambdaQueryWrapper<PortalUserPermission>()
                .eq(PortalUserPermission::getUserId, userId)
                .eq(PortalUserPermission::getPermissionKey, PortalSessionServiceImpl.PERMISSION_ORG_ADMIN));
        if (orgAdmin && existing == null) {
            PortalUserPermission permission = new PortalUserPermission();
            permission.setUserId(userId);
            permission.setPermissionKey(PortalSessionServiceImpl.PERMISSION_ORG_ADMIN);
            permissionMapper.insert(permission);
        } else if (!orgAdmin && existing != null) {
            permissionMapper.deleteById(existing.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalInvitation createInvitation(Long orgId, String email, String role, HttpServletRequest request) {
        if (orgId == null || !StringUtils.hasText(email) || role == null || !INVITE_ROLES.contains(role)) {
            throw BusinessException.of(400, "error.portal.admin.invalidInvitation");
        }
        PortalOrganization org = requireOrg(orgId);
        if (!"ACTIVE".equals(org.getStatus())) {
            throw BusinessException.of(409, "error.portal.org.suspended");
        }
        // G3: 初回組織管理者は内部管理者が発行する。既にACTIVEな組織管理者が存在する組織への
        // ADMIN招待は、組織管理者の承認（現行運用: 内部管理者経由の手続き）を必要とするため
        // 直接発行を拒否する（S13-R1-P2-09）。
        if ("ADMIN".equals(role) && hasActiveOrgAdmin(orgId)) {
            throw BusinessException.of(409, "error.portal.admin.orgAdminExists");
        }
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (invitationMapper.countActiveInvitation(orgId, normalizedEmail, LocalDateTime.now(clock)) > 0) {
            throw BusinessException.of(409, "error.portal.admin.invitationExists");
        }
        PortalInvitation invitation = new PortalInvitation();
        invitation.setPortalOrgId(orgId);
        invitation.setEmail(normalizedEmail);
        invitation.setRole(role);
        String rawToken = randomToken();
        invitation.setTokenHash(SecurityHashUtil.sha256(rawToken));
        invitation.setExpiresAt(LocalDateTime.now(clock).plusHours(INVITE_TTL_HOURS));
        invitationMapper.insert(invitation);
        try {
            mailService.sendInvitation(normalizedEmail, rawToken);
        } catch (RuntimeException e) {
            log.warn("招待メール送信に失敗しました: orgId={} email={}（招待は有効のまま再送可能） error={}",
                    orgId, normalizedEmail, e.getMessage());
        }
        return invitation;
    }

    @Override
    public Page<PortalInvitation> invitations(long current, long size, Long orgId, Set<Long> allowedOrgIds) {
        if (orgId != null) {
            requireOrg(orgId);
        }
        LambdaQueryWrapper<PortalInvitation> wrapper = new LambdaQueryWrapper<PortalInvitation>()
                .eq(orgId != null, PortalInvitation::getPortalOrgId, orgId)
                .orderByDesc(PortalInvitation::getId);
        applyOrgScope(wrapper, allowedOrgIds, PortalInvitation::getPortalOrgId);
        return invitationMapper.selectPage(new Page<>(current, Math.min(size, 1000)), wrapper);
    }

    @Override
    public List<PortalSession> sessions(Long userId) {
        requireUser(userId);
        return sessionService.listActive(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeSession(Long sessionId, Long userId) {
        PortalSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        sessionService.revokeAllForUser(userId, "ADMIN");
    }

    @Override
    public Page<PortalAccessLog> accessLogs(long current, long size, Long orgId, String action,
                                            Set<Long> allowedOrgIds) {
        LambdaQueryWrapper<PortalAccessLog> wrapper = new LambdaQueryWrapper<PortalAccessLog>()
                .eq(orgId != null, PortalAccessLog::getPortalOrgId, orgId)
                .eq(StringUtils.hasText(action), PortalAccessLog::getAction, action)
                .orderByDesc(PortalAccessLog::getId);
        applyOrgScope(wrapper, allowedOrgIds, PortalAccessLog::getPortalOrgId);
        return accessLogMapper.selectPage(new Page<>(current, Math.min(size, 1000)), wrapper);
    }

    /** 営業scope: 可視組織ID集合（null=全件、空集合=0件）をSQL条件へ適用する。 */
    private <T> void applyOrgScope(LambdaQueryWrapper<T> wrapper, Set<Long> allowedOrgIds,
                                   com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> orgIdGetter) {
        if (allowedOrgIds == null) {
            return;
        }
        if (allowedOrgIds.isEmpty()) {
            wrapper.eq(orgIdGetter, -1L);
            return;
        }
        wrapper.in(orgIdGetter, allowedOrgIds);
    }

    @Override
    public String currentTermsVersion() {
        return systemConfigService.getString("portal.terms.current-version", "1");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishTerms(String version) {
        if (!StringUtils.hasText(version) || !version.matches("[0-9]+")) {
            throw BusinessException.of(400, "error.portal.admin.invalidTermsVersion");
        }
        String current = currentTermsVersion();
        try {
            if (Integer.parseInt(version) <= Integer.parseInt(current)) {
                throw BusinessException.of(409, "error.portal.admin.termsVersionNotNewer", current);
            }
        } catch (NumberFormatException e) {
            throw BusinessException.of(400, "error.portal.admin.invalidTermsVersion");
        }
        systemConfigService.put("portal.terms.current-version", version, "ポータル利用規約version");
    }

    private PortalOrganization requireOrg(Long orgId) {
        PortalOrganization org = orgId == null ? null : organizationMapper.selectById(orgId);
        if (org == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return org;
    }

    /** 組織にACTIVEな組織管理者（org.admin権限を持つACTIVE user）が存在するか。 */
    private boolean hasActiveOrgAdmin(Long orgId) {
        List<Long> userIds = userMapper.selectList(new LambdaQueryWrapper<PortalUser>()
                        .eq(PortalUser::getPortalOrgId, orgId)
                        .eq(PortalUser::getStatus, "ACTIVE"))
                .stream().map(PortalUser::getId).toList();
        if (userIds.isEmpty()) {
            return false;
        }
        return permissionMapper.selectCount(new LambdaQueryWrapper<PortalUserPermission>()
                .in(PortalUserPermission::getUserId, userIds)
                .eq(PortalUserPermission::getPermissionKey, PortalSessionServiceImpl.PERMISSION_ORG_ADMIN)) > 0;
    }

    private PortalUser requireUser(Long userId) {
        PortalUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return user;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
