package com.ses.dto.certificationlearninggap;

import java.util.List;

/** 本人ポータルの初期表示。資格・学習計画を同じ本人scopeで返す。 */
public record CertificationSelfDashboard(
        List<CertificationSelfView> certifications,
        List<LearningPlanSelfView> learningPlans) {
}
