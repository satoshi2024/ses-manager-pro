package com.ses.service;

import com.ses.entity.BpTerms;

import java.time.LocalDate;

public interface BpTermsResolver {

    /**
     * 指定された支払確定日時点の有効な取引条件を取得する。
     * @param bpCompanyId BP会社ID
     * @param targetDate 支払確定日 (必須)
     * @return 有効なBpTerms。見つからない場合はnull
     */
    BpTerms resolveTermsAsOf(Long bpCompanyId, LocalDate targetDate);
}
