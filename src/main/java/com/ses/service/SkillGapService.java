package com.ses.service;

import com.ses.dto.skillgap.SkillGapRequest;
import com.ses.dto.skillgap.SkillGapResult;

/** effective eventだけでskill gapを計算し、再現用snapshotを保存するサービス。 */
public interface SkillGapService {

    String STATUS_OK = "OK";
    String STATUS_HISTORICAL_DATA_UNAVAILABLE = "historical_data_unavailable";

    enum DemandSource {
        PROJECT,
        POSITION,
        COMBINED
    }

    SkillGapResult calculate(SkillGapRequest request);

    SkillGapResult replay(Long snapshotId);
}
