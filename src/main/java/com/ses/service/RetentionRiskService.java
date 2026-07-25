package com.ses.service;

import com.ses.dto.engineerfollowup.RetentionRiskDto;

import java.util.Collection;
import java.util.Map;

/**
 * 要員の定着リスクスコア算出サービス
 * 長期Bench・直近満足度低・フォロー間隔超過を合成した簡易スコア（design.md 2章）
 */
public interface RetentionRiskService {

    /** 指定要員の定着リスクスコアを算出する */
    RetentionRiskDto score(Long engineerId);

    /**
     * 複数要員の定着リスクスコアをまとめて算出する（要員ID -> スコア）。
     *
     * <p>要員一覧はページごとに全件分のスコアを必要とするため、{@link #score(Long)} を
     * ループで呼ぶと要員数×3クエリのN+1になる。本メソッドは要員数によらず定数回の
     * クエリで算出する。存在しない要員IDは結果に含まれない（例外は投げない）。
     */
    Map<Long, RetentionRiskDto> scoreBatch(Collection<Long> engineerIds);
}
