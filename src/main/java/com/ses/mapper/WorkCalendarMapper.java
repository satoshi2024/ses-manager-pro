package com.ses.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.WorkCalendar;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface WorkCalendarMapper extends BaseMapper<WorkCalendar> {

    /**
     * 全社共通の有効な暦（要員・組織・法人を持たない、{@code status} が {@code 有効}・{@code ACTIVE}、論理削除されていない）のうち、
     * {@code date} より前に終わっていないもの（{@code date} を含む暦と、これから始まる暦）を有効期間の始まりの順に返す（1回の照会）。
     * {@code ServiceSlaCalculator.findCalendarForScope} の4段目と同じ条件で、機能の開放の NF02 の暦の条件
     * （{@code WorkCalendarConditionEvaluator}。feature-enablement-console FE-A11）が使う。
     */
    default List<WorkCalendar> selectCompanyWideNotEndedBefore(LocalDate date) {
        return selectList(new LambdaQueryWrapper<WorkCalendar>()
                .isNull(WorkCalendar::getEngineerId)
                .isNull(WorkCalendar::getOrganizationId)
                .isNull(WorkCalendar::getLegalEntityId)
                .and(w -> w.eq(WorkCalendar::getStatus, "有効").or().eq(WorkCalendar::getStatus, "ACTIVE"))
                .and(w -> w.isNull(WorkCalendar::getValidTo).or().ge(WorkCalendar::getValidTo, date))
                .orderByAsc(WorkCalendar::getValidFrom)
                .orderByAsc(WorkCalendar::getId));
    }
}
