package com.ses.service.certification;

import com.ses.dto.certification.CertificationExpiryCandidate;
import com.ses.entity.EngineerCertification;

import java.time.LocalDate;
import java.util.List;

/** 資格期限の導出と90/60/30境界判定。 */
public interface CertificationExpiryService {

    /** 全recordから当日通知候補を判定する。recipient未解決の候補は作らない。 */
    List<CertificationExpiryCandidate> findCandidates(LocalDate asOf, Long recipientUserId);

    /** 注入ClockのAsia/Tokyo日付で当日候補を判定する。 */
    List<CertificationExpiryCandidate> findCandidates(Long recipientUserId);

    /** 特定recordを特定recipientへ通知すべきか判定する。 */
    CertificationExpiryCandidate evaluate(EngineerCertification record, LocalDate asOf, Long recipientUserId);
}
