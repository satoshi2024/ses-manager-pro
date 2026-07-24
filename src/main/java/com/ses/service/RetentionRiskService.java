package com.ses.service;

import com.ses.dto.engineerfollowup.RetentionRiskDto;

/**
 * 要員の定着リスクスコア算出サービス
 * 長期Bench・直近満足度低・フォロー間隔超過を合成した簡易スコア（design.md 2章）
 */
public interface RetentionRiskService {

    /** 指定要員の定着リスクスコアを算出する */
    RetentionRiskDto score(Long engineerId);
}
