package com.ses.dto.certificationlearninggap;

import com.ses.dto.certification.EngineerCertificationViewDto;

import java.util.List;

/** 本人ポータル用の資格record。raw番号・storage keyは含めない。 */
public record CertificationSelfView(
        EngineerCertificationViewDto record,
        List<CertificationEvidenceView> evidences) {
}
