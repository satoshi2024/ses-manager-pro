package com.ses.dto.certificationlearninggap;

import java.time.LocalDate;
import java.util.List;

/** 資格recordの画面応答。暗号化列は含めず、raw番号は権限付きdetail/listに限る。 */
public record CertificationLearningGapCertificationDto(
        Long id,
        Long certificationId,
        String certificationDisplayName,
        LocalDate acquiredOn,
        LocalDate expiresOn,
        String recordState,
        String effectiveState,
        Integer currentFlag,
        String certificateNumberMasked,
        String certificateNumber,
        boolean canViewFullNumber,
        List<CertificationEvidenceView> evidences) {
}
