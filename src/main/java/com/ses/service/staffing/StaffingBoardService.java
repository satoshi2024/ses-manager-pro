package com.ses.service.staffing;

import com.ses.dto.staffing.AllocationCardDto;
import com.ses.dto.staffing.PositionBoardDto;

import java.util.List;

/**
 * ポジションボード/要員タイムラインの表示用集約（T077 A1）。
 * entityは画面へ直接公開せず、表示名・承認状態を付与したDTOを返す。
 * 全engineer×全dayの直積を作らず、server aggregateで返す（design §4）。
 */
public interface StaffingBoardService {

    /** 案件詳細のポジションボード（position列＋配置カード＋充足人数）。 */
    PositionBoardDto projectBoard(Long projectId);

    /** 要員詳細の配置タイムライン（破棄済みを除く全配置を日付順）。 */
    List<AllocationCardDto> engineerTimeline(Long engineerId);

    /** 配置1件のカード表示用DTO。 */
    AllocationCardDto card(Long allocationId);
}
