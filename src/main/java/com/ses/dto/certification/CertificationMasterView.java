package com.ses.dto.certification;

import com.ses.entity.Certification;

/** 資格masterの管理画面向け安全projection。tenant・監査項目・内部identityは返さない。 */
public record CertificationMasterView(Long id, String displayName, String issuerDisplay, String externalCode,
                                      String expiryType, Integer expiryMonths, Integer ruleVersion,
                                      Integer activeFlag, Integer version) {

    public static CertificationMasterView from(Certification certification) {
        return new CertificationMasterView(certification.getId(), certification.getDisplayName(),
                certification.getIssuerDisplay(), certification.getExternalCode(), certification.getExpiryType(),
                certification.getExpiryMonths(), certification.getRuleVersion(), certification.getActiveFlag(),
                certification.getVersion());
    }
}
