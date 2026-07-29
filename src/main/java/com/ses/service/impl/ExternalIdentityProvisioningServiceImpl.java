package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.ExternalIdentityProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * emailだけの自動linkを禁止し、管理者が対象userとsubjectを明示して承認する。
 */
@Service
@RequiredArgsConstructor
public class ExternalIdentityProvisioningServiceImpl implements ExternalIdentityProvisioningService {

    private final IdentityProviderMapper identityProviderMapper;
    private final UserExternalIdentityMapper externalIdentityMapper;
    private final SysUserMapper sysUserMapper;
    private final com.ses.config.OidcSecurityProperties properties;

    @Override
    @Transactional
    public UserExternalIdentity provision(Long providerId, ExternalIdentityProvisionRequest request) {
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
            return existing;
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
        externalIdentityMapper.insert(link);
        return link;
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
