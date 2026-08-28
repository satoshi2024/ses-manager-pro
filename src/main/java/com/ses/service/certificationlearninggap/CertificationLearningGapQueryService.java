package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** A1のlist/detail/count/exportが共有するeffective engineer population query。 */
public interface CertificationLearningGapQueryService {

    Page<CertificationLearningGapRow> page(CertificationLearningGapFilter filter, long current, long size,
                                           Authentication authentication);

    long count(CertificationLearningGapFilter filter, Authentication authentication);

    CertificationLearningGapRow detail(Long engineerId, CertificationLearningGapFilter filter,
                                       Authentication authentication);

    List<CertificationLearningGapRow> export(CertificationLearningGapFilter filter,
                                             Authentication authentication);

    /** managerのorg∩DataScopeを含む、指定日現在の表示可能engineer ID。 */
    Set<Long> visibleEngineerIds(LocalDate asOf);
}
