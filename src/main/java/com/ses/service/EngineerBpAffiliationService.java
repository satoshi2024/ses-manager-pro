package com.ses.service;

import com.ses.entity.EngineerBpAffiliation;

import java.time.LocalDate;
import java.util.List;

public interface EngineerBpAffiliationService {

    /**
     * 要員のBP所属を登録・更新する（期間代数ルール適用）。
     */
    EngineerBpAffiliation assignBpAffiliation(Long engineerId, Long bpCompanyId, LocalDate validFrom, LocalDate validTo);

    /**
     * 指定日付時点での要員のBP所属を取得する。
     */
    EngineerBpAffiliation getActiveAffiliationAsOf(Long engineerId, LocalDate targetDate);

    /**
     * 要員の所属履歴一覧を取得する。
     */
    List<EngineerBpAffiliation> getAffiliationHistory(Long engineerId);
}
