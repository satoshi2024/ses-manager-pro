package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Certification;
import com.ses.mapper.CertificationMapper;
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
        if (certification == null || !StringUtils.hasText(certification.getDisplayName())) {
            throw BusinessException.of(400, "certification.master.invalid");
        }
        String tenantId = StringUtils.hasText(certification.getTenantId()) ? certification.getTenantId() : "default";
        String issuerKey = identityNormalizer.normalizeKeyPart(
                StringUtils.hasText(certification.getIssuerDisplay()) ? certification.getIssuerDisplay() : "UNKNOWN");
        String externalCodeKey = identityNormalizer.normalizeKeyPart(certification.getExternalCode());
        String nameKey = identityNormalizer.normalizeKeyPart(certification.getDisplayName());
        String identityKey = identityNormalizer.buildIdentityKey(issuerKey, externalCodeKey, nameKey);

        Long duplicate = certificationMapper.selectCount(new LambdaQueryWrapper<Certification>()
                .eq(Certification::getTenantId, tenantId)
                .eq(Certification::getIdentityKey, identityKey));
        if (duplicate != null && duplicate > 0) {
            throw BusinessException.of(409, "certification.master.duplicate");
        }

        certification.setTenantId(tenantId);
        certification.setIssuerKey(issuerKey);
        certification.setExternalCodeKey(StringUtils.hasText(externalCodeKey) ? externalCodeKey : null);
        certification.setNameKey(nameKey);
        certification.setIdentityKey(identityKey);
        if (certification.getExpiryType() == null) {
            certification.setExpiryType("NONE");
        }
        if (certification.getRuleVersion() == null) {
            certification.setRuleVersion(1);
        }
        if (certification.getActiveFlag() == null) {
            certification.setActiveFlag(1);
        }
        certification.setCreatedBy(actorUserId);
        certification.setUpdatedBy(actorUserId);
        certificationMapper.insert(certification);
        return certification;
    }

    @Override
    public List<Certification> listMasters(boolean includeInactive) {
        LambdaQueryWrapper<Certification> query = new LambdaQueryWrapper<Certification>()
                .orderByAsc(Certification::getDisplayName).orderByAsc(Certification::getId);
        if (!includeInactive) {
            query.eq(Certification::getActiveFlag, 1);
        }
        return certificationMapper.selectList(query);
    }

    @Override
    public Certification getMaster(Long id) {
        Certification certification = id == null ? null : certificationMapper.selectById(id);
        if (certification == null) {
            throw BusinessException.of(404, "certification.master.notFound");
        }
        return certification;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Certification updateMaster(Long id, Certification input, Long actorUserId) {
        Certification current = getMaster(id);
        validateInput(input);
        String tenantId = StringUtils.hasText(input.getTenantId()) ? input.getTenantId() : current.getTenantId();
        String issuerKey = identityNormalizer.normalizeKeyPart(
                StringUtils.hasText(input.getIssuerDisplay()) ? input.getIssuerDisplay() : "UNKNOWN");
        String externalCodeKey = identityNormalizer.normalizeKeyPart(input.getExternalCode());
        String nameKey = identityNormalizer.normalizeKeyPart(input.getDisplayName());
        String identityKey = identityNormalizer.buildIdentityKey(issuerKey, externalCodeKey, nameKey);
        Long duplicate = certificationMapper.selectCount(new LambdaQueryWrapper<Certification>()
                .eq(Certification::getTenantId, tenantId)
                .eq(Certification::getIdentityKey, identityKey)
                .ne(Certification::getId, id));
        if (duplicate != null && duplicate > 0) {
            throw BusinessException.of(409, "certification.master.duplicate");
        }
        current.setTenantId(tenantId);
        current.setIssuerKey(issuerKey);
        current.setExternalCodeKey(StringUtils.hasText(externalCodeKey) ? externalCodeKey : null);
        current.setNameKey(nameKey);
        current.setIdentityKey(identityKey);
        current.setDisplayName(input.getDisplayName());
        current.setIssuerDisplay(input.getIssuerDisplay());
        current.setExternalCode(input.getExternalCode());
        current.setExpiryType(StringUtils.hasText(input.getExpiryType()) ? input.getExpiryType() : "NONE");
        current.setExpiryMonths(input.getExpiryMonths());
        current.setRuleVersion(input.getRuleVersion() == null ? 1 : input.getRuleVersion());
        current.setActiveFlag(input.getActiveFlag() == null ? current.getActiveFlag() : input.getActiveFlag());
        current.setUpdatedBy(actorUserId);
        certificationMapper.updateById(current);
        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Certification deactivateMaster(Long id, Long actorUserId) {
        Certification current = getMaster(id);
        current.setActiveFlag(0);
        current.setUpdatedBy(actorUserId);
        certificationMapper.updateById(current);
        return current;
    }

    private void validateInput(Certification certification) {
        if (certification == null || !StringUtils.hasText(certification.getDisplayName())) {
            throw BusinessException.of(400, "certification.master.invalid");
        }
        if (certification.getExpiryMonths() != null && certification.getExpiryMonths() < 1) {
            throw BusinessException.of(400, "certification.master.invalid");
        }
    }
}
