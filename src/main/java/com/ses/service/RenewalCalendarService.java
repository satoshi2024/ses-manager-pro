package com.ses.service;

import com.ses.dto.contract.RenewalCalendarResponseDto;

import java.time.LocalDate;

/**
 * 契約更新カレンダー（FR-06）。全契約の終了/更新期限を俯瞰表示するためのAPI用サービス。
 */
public interface RenewalCalendarService {

    /**
     * 指定期間内に更新期限（endDate - リード日数）が入る契約を状態付きで返す。
     * @param from 期間開始日（更新期限日基準、両端含む）
     * @param to   期間終了日（更新期限日基準、両端含む）
     */
    RenewalCalendarResponseDto getCalendar(LocalDate from, LocalDate to);
}
