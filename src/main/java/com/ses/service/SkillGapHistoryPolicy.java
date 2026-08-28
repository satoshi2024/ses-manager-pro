package com.ses.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** skill-gap event履歴を有効化した日。これより前はcurrent値で補完しない。 */
@Component
public class SkillGapHistoryPolicy {

    private final LocalDate featureStartDate;

    public SkillGapHistoryPolicy(
            @Value("${skill-gap.feature-start-date:2026-08-28}") String featureStartDate) {
        this.featureStartDate = LocalDate.parse(featureStartDate);
    }

    public LocalDate featureStartDate() {
        return featureStartDate;
    }
}
