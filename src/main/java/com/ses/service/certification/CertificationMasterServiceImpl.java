package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Certification;
import com.ses.mapper.CertificationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CertificationMasterServiceImpl implements CertificationMasterService {

    private final CertificationMapper certificationMapper;
    private final CertificationIdentityNormalizer identityNormalizer;

    public CertificationMasterServiceImpl(CertificationMapper certificationMapper,
                                          CertificationIdentityNormalizer identityNormalizer) {
        this.certificationMapper = certificationMapper;
        this.identityNormalizer = identityNormalizer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Certification createMaster(Certification certification, Long actorUserId) {
        validateInput(certification);
        String tenantId = trustedTenant(certification);
        Identity identity = identity(certification);

        assertNotDuplicate(tenantId, identity.identityKey(), null);
        certification.setTenantId(tenantId);
        applyIdentity(certification, identity);
        certification.setExpiryType(StringUtils.hasText(certification.getExpiryType())
                ? certification.getExpiryType() : "NONE");
        certification.setRuleVersion(certification.getRuleVersion() == null ? 1 : certification.getRuleVersion());
        certification.setActiveFlag(certification.getActiveFlag() == null ? 1 : certification.getActiveFlag());
        certification.setVersion(0);
        certification.setCreatedBy(actorUserId);
        certification.setUpdatedBy(actorUserId);
        try {
            certificationMapper.insert(certification);
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "certification.master.duplicate");
        }
        return certification;
    }

    @Override
    public List<Certification> listMasters(boolean includeInactive) {
        LambdaQueryWrapper<Certification> query = new LambdaQueryWrapper<Certification>()
                .eq(Certification::getTenantId, trustedTenant(null))
                .orderByAsc(Certification::getDisplayName).orderByAsc(Certification::getId);
        if (!includeInactive) {
            query.eq(Certification::getActiveFlag, 1);
        }
        return certificationMapper.selectList(query);
    }

    @Override
    public Certification getMaster(Long id) {
        Certification certification = id == null ? null : certificationMapper.selectOne(
                new LambdaQueryWrapper<Certification>()
                        .eq(Certification::getId, id)
                        .eq(Certification::getTenantId, trustedTenant(null)));
        if (certification == null) {
            throw BusinessException.of(404, "certification.master.notFound");
        }
        return certification;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Certification updateMaster(Long id, Certification input, Long actorUserId, Integer expectedVersion) {
        requireExpectedVersion(expectedVersion);
        validateInput(input);
        String tenantId = trustedTenant(input);
        Certification current = getMaster(id);
        int currentVersion = current.getVersion() == null ? 0 : current.getVersion();
        if (currentVersion != expectedVersion) {
            throw BusinessException.of(409, "certification.master.optimisticLock");
        }

        Identity identity = identity(input);
        assertNotDuplicate(tenantId, identity.identityKey(), id);
        String expiryType = StringUtils.hasText(input.getExpiryType()) ? input.getExpiryType() : "NONE";
        int ruleVersion = input.getRuleVersion() == null ? 1 : input.getRuleVersion();
        int activeFlag = input.getActiveFlag() == null ? current.getActiveFlag() : input.getActiveFlag();
        int nextVersion = expectedVersion + 1;

        UpdateWrapper<Certification> update = new UpdateWrapper<Certification>()
                .eq("id", id)
                .eq("tenant_id", tenantId)
                .eq("version", expectedVersion)
                .set("issuer_key", identity.issuerKey())
                .set("external_code_key", identity.externalCodeKey())
                .set("name_key", identity.nameKey())
                .set("identity_key", identity.identityKey())
                .set("display_name", input.getDisplayName())
                .set("issuer_display", input.getIssuerDisplay())
                .set("external_code", input.getExternalCode())
                .set("expiry_type", expiryType)
                .set("expiry_months", input.getExpiryMonths())
                .set("rule_version", ruleVersion)
                .set("active_flag", activeFlag)
                .set("updated_by", actorUserId)
                .set("version", nextVersion);
        try {
            if (certificationMapper.update(null, update) != 1) {
                throw BusinessException.of(409, "certification.master.optimisticLock");
            }
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "certification.master.duplicate");
        }

        current.setTenantId(tenantId);
        applyIdentity(current, identity);
        current.setDisplayName(input.getDisplayName());
        current.setIssuerDisplay(input.getIssuerDisplay());
        current.setExternalCode(input.getExternalCode());
        current.setExpiryType(expiryType);
        current.setExpiryMonths(input.getExpiryMonths());
        current.setRuleVersion(ruleVersion);
        current.setActiveFlag(activeFlag);
        current.setUpdatedBy(actorUserId);
        current.setVersion(nextVersion);
        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Certification deactivateMaster(Long id, Long actorUserId, Integer expectedVersion) {
        requireExpectedVersion(expectedVersion);
        String tenantId = trustedTenant(null);
        Certification current = getMaster(id);
        int currentVersion = current.getVersion() == null ? 0 : current.getVersion();
        if (currentVersion != expectedVersion) {
            throw BusinessException.of(409, "certification.master.optimisticLock");
        }
        int nextVersion = expectedVersion + 1;
        UpdateWrapper<Certification> update = new UpdateWrapper<Certification>()
                .eq("id", id)
                .eq("tenant_id", tenantId)
                .eq("version", expectedVersion)
                .set("active_flag", 0)
                .set("updated_by", actorUserId)
                .set("version", nextVersion);
        if (certificationMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "certification.master.optimisticLock");
        }
        current.setActiveFlag(0);
        current.setUpdatedBy(actorUserId);
        current.setVersion(nextVersion);
        return current;
    }

    private Identity identity(Certification certification) {
        String issuerKey = identityNormalizer.normalizeKeyPart(
                StringUtils.hasText(certification.getIssuerDisplay()) ? certification.getIssuerDisplay() : "UNKNOWN");
        String externalCodeKey = identityNormalizer.normalizeKeyPart(certification.getExternalCode());
        String nameKey = identityNormalizer.normalizeKeyPart(certification.getDisplayName());
        return new Identity(issuerKey, StringUtils.hasText(externalCodeKey) ? externalCodeKey : null,
                nameKey, identityNormalizer.buildIdentityKey(issuerKey, externalCodeKey, nameKey));
    }

    private void applyIdentity(Certification certification, Identity identity) {
        certification.setIssuerKey(identity.issuerKey());
        certification.setExternalCodeKey(identity.externalCodeKey());
        certification.setNameKey(identity.nameKey());
        certification.setIdentityKey(identity.identityKey());
    }

    private void assertNotDuplicate(String tenantId, String identityKey, Long excludedId) {
        LambdaQueryWrapper<Certification> query = new LambdaQueryWrapper<Certification>()
                .eq(Certification::getTenantId, tenantId)
                .eq(Certification::getIdentityKey, identityKey);
        if (excludedId != null) {
            query.ne(Certification::getId, excludedId);
        }
        Long duplicate = certificationMapper.selectCount(query);
        if (duplicate != null && duplicate > 0) {
            throw BusinessException.of(409, "certification.master.duplicate");
        }
    }

    private String trustedTenant(Certification requested) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (requested != null && StringUtils.hasText(requested.getTenantId())
                && !tenantId.equals(requested.getTenantId().trim())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        return tenantId;
    }

    private void requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, "certification.master.expectedVersionRequired");
        }
    }

    private void validateInput(Certification certification) {
        if (certification == null || !StringUtils.hasText(certification.getDisplayName())) {
            throw BusinessException.of(400, "certification.master.invalid");
        }
        if (certification.getExpiryMonths() != null && certification.getExpiryMonths() < 1) {
            throw BusinessException.of(400, "certification.master.invalid");
        }
    }

    private record Identity(String issuerKey, String externalCodeKey, String nameKey, String identityKey) {
    }
}
