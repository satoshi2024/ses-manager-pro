package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Certification;
import com.ses.mapper.CertificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
}
