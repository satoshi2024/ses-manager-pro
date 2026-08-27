package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.AuditLogService;
import com.ses.service.ExternalIdentityProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * emailだけの自動linkを禁止し、管理者が対象userとsubjectを明示して承認する。
 * 承認は追責可能な reviewer と、失敗時にロールバックする追加監査を必須とする。
 */
@Service
@RequiredArgsConstructor
public class ExternalIdentityProvisioningServiceImpl implements ExternalIdentityProvisioningService {

    private final IdentityProviderMapper identityProviderMapper;
    private final UserExternalIdentityMapper externalIdentityMapper;
    private final SysUserMapper sysUserMapper;
    private final com.ses.config.OidcSecurityProperties properties;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserExternalIdentity provision(Long providerId, ExternalIdentityProvisionRequest request) {
        if (!StatusConstants.ROLE_ADMIN.equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        IdentityProvider provider = identityProviderMapper.selectById(providerId);
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1
                || !"OIDC".equalsIgnoreCase(provider.getProviderType())
                || !properties.getTenantId().equals(provider.getTenantId())) {
            throw BusinessException.of(403, "error.identity.providerNotAllowed");
        }

        SysUser target = sysUserMapper.selectById(request.getUserId());
        if (target == null) {
            throw BusinessException.of(404, "error.identity.userNotFound");
        }
        if (target.getStatus() == null || target.getStatus() != 1) {
            throw BusinessException.of(400, "error.identity.userInactive");
        }

        String subject = request.getSubject().trim();
        UserExternalIdentity existing = externalIdentityMapper.selectByTenantProviderAndSubject(
                provider.getTenantId(), provider.getId(), subject);
        if (existing != null) {
            if (!target.getId().equals(existing.getUserId())) {
                throw BusinessException.of(409, "error.identity.subjectAlreadyLinked");
            }
            return markApproved(existing);
        }

        String email = StringUtils.hasText(request.getEmailSnapshot())
                ? request.getEmailSnapshot().trim() : null;
        validateEmailCollision(target, email);

        UserExternalIdentity link = new UserExternalIdentity();
        link.setTenantId(provider.getTenantId());
        link.setUserId(target.getId());
        link.setProviderId(provider.getId());
        link.setSubject(subject);
        link.setEmailSnapshot(email);
        link.setLinkedAt(LocalDateTime.now());
        applyApprovedFields(link, requireReviewerId());
        // DuplicateKey は binding insert の競合だけを扱う。監査の DuplicateKey を
        // 誤って競合正規化すると APPROVED かつ必須監査欠落のまま commit しうる。
        try {
            if (externalIdentityMapper.insert(link) != 1) {
                throw BusinessException.of(409, "error.identity.linkConflict");
            }
        } catch (DuplicateKeyException e) {
            // 別node/transactionが同じsubjectを先に確定した場合は再読して結果を正規化する。
            // MySQL REPEATABLE READ では非ロック SELECT が snapshot のまま null を返しうるため FOR UPDATE で current read する。
            UserExternalIdentity concurrent = externalIdentityMapper.selectByTenantProviderAndSubjectForUpdate(
                    provider.getTenantId(), provider.getId(), subject);
            if (concurrent != null && target.getId().equals(concurrent.getUserId())) {
                return markApproved(concurrent);
            }
            throw BusinessException.of(409, "error.identity.subjectAlreadyLinked");
        }
        // insert後にbinding IDが確定してから追加監査を書く（失敗時は同一txでロールバック）。
        recordApprovalAudit(link, "NEW");
        return link;
    }

    private UserExternalIdentity markApproved(UserExternalIdentity link) {
        if (link.getId() == null) {
            throw BusinessException.of(409, "error.identity.linkConflict");
        }
        Long reviewerId = requireReviewerId();
        UserExternalIdentity locked = externalIdentityMapper.selectByIdForUpdate(link.getId());
        if (locked == null) {
            throw BusinessException.of(409, "error.identity.linkConflict");
        }
        if (!link.getUserId().equals(locked.getUserId())) {
            throw BusinessException.of(409, "error.identity.subjectAlreadyLinked");
        }
        if ("APPROVED".equals(locked.getReviewStatus())) {
            // 先行承認済み。reviewer/時刻は上書きせず、追加監査も書かない。
            return locked;
        }
        String beforeStatus = StringUtils.hasText(locked.getReviewStatus())
                ? locked.getReviewStatus() : "UNKNOWN";
        LocalDateTime reviewedAt = LocalDateTime.now();
        if (externalIdentityMapper.approveIfNotApproved(locked.getId(), reviewerId, reviewedAt) != 1) {
            UserExternalIdentity raced = externalIdentityMapper.selectById(locked.getId());
            if (raced != null && "APPROVED".equals(raced.getReviewStatus())
                    && link.getUserId().equals(raced.getUserId())) {
                return raced;
            }
            throw BusinessException.of(409, "error.identity.linkConflict");
        }
        applyApprovedFields(locked, reviewerId);
        locked.setReviewedAt(reviewedAt);
        recordApprovalAudit(locked, beforeStatus);
        return locked;
    }

    private Long requireReviewerId() {
        Long reviewerId = SecurityUtils.currentUserId();
        if (reviewerId == null) {
            // 再承認の追責ができない状態では APPROVED にしない（fail-closed）。
            throw BusinessException.of(403, "error.identity.reviewerRequired");
        }
        return reviewerId;
    }

    private void applyApprovedFields(UserExternalIdentity link, Long reviewerId) {
        link.setReviewStatus("APPROVED");
        link.setReviewedAt(LocalDateTime.now());
        link.setReviewedBy(reviewerId);
    }

    /**
     * binding単位の追加監査。ApiAuditFilterのbest-effort記録とは独立し、失敗時は承認をロールバックする。
     */
    private void recordApprovalAudit(UserExternalIdentity link, String beforeStatus) {
        String uri = "/internal/oidc-bindings/" + link.getId()
                + "/approve?from=" + beforeStatus
                + "&to=APPROVED"
                + "&subjectSha256=" + sha256Hex(link.getSubject())
                + "&reviewerId=" + link.getReviewedBy()
                + "&userId=" + link.getUserId();
        auditLogService.recordRequired(
                SecurityUtils.currentUsername(),
                "APPROVE",
                uri,
                200,
                "OIDC_BINDING_APPROVED",
                true);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private void validateEmailCollision(SysUser target, String email) {
        if (!StringUtils.hasText(email)) {
            return;
        }
        Collection<SysUser> matches = sysUserMapper.selectByEmail(email);
        if (matches != null && matches.size() > 1) {
            throw BusinessException.of(409, "error.identity.emailAmbiguous");
        }
        if (matches != null && !matches.isEmpty()) {
            SysUser matched = matches.iterator().next();
            if (!target.getId().equals(matched.getId())) {
                throw BusinessException.of(409, "error.identity.emailConflict");
            }
        }
        if (StringUtils.hasText(target.getEmail()) && !target.getEmail().equalsIgnoreCase(email)) {
            throw BusinessException.of(409, "error.identity.emailConflict");
        }
    }
}
