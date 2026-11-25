package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.entity.WorkCalendar;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.mapper.WorkCalendarMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * NF02 の全社共通カレンダーの条件（feature-enablement-console design §8.1・§18 J-16・D-9。FE-A11）。
 *
 * <ul>
 *   <li>本日（JST の業務日）を含む全社共通の有効な暦があれば成立。{@code ServiceSlaCalculator.findCalendarForScope} の4段目と同じ条件
 *       （要員・組織・法人を持たない、{@code status} が {@code 有効}・{@code ACTIVE}、{@code valid_from <= 本日 <= valid_to}（終わりの
 *       無い暦を含む））で、要員・組織・法人つきの暦だけ・無効の暦・期間の外の暦では不成立。照会は1回。</li>
 *   <li>check の code は目録が開放の時だけの条件（{@code Scope.OPEN_ONLY}）と宣言した code だけを返す。申請・承認の時だけ必須で、
 *       開いた後に暦が切れても NF02 を自動閉鎖しない（日の始まりの評価が警告だけを出す。design §7.4）。照会に失敗した時も例外を
 *       投げず、開放の時だけの条件の評価できない code を返す（registry の例外の扱いは継続の条件になり、照会の失敗で NF02 を閉じて
 *       しまうため）。</li>
 *   <li>{@code validUntil} は暦が成り立つ最後の日の翌日。今の暦の終わりにすぐ続く次の全社共通の暦があれば、その終わりまで延ばす
 *       （次年度の暦を登録済みなら期限の接近を警告しない）。</li>
 *   <li>暦が無い間の SLA の計算（WARN {@code missing_calendar}・土日だけを休日）は変えない。</li>
 * </ul>
 */
@Slf4j
@Component
public class WorkCalendarConditionEvaluator implements FeatureConditionEvaluator {

    /** 暦がある（成立）・無い（不成立）・照会できない時の detail の code。 */
    public static final String DETAIL_FOUND = "calendar.found";
    public static final String DETAIL_MISSING = "calendar.missing";

    private final WorkCalendarMapper workCalendarMapper;

    public WorkCalendarConditionEvaluator(WorkCalendarMapper workCalendarMapper) {
        this.workCalendarMapper = Objects.requireNonNull(workCalendarMapper, "workCalendarMapper");
    }

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF02);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        LocalDate today = context.businessDate();
        List<WorkCalendar> calendars;
        try {
            calendars = workCalendarMapper.selectCompanyWideNotEndedBefore(today);
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 全社共通の暦を照会できません（開放の時だけの条件。開いている NF02 は閉じない）: exceptionClass={}",
                    e.getClass().getSimpleName());
            return List.of(new FeatureConditionCheck(FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE,
                    FeatureConditionCheck.Category.AUTO, FeatureConditionCheck.Scope.OPEN_ONLY,
                    FeatureConditionCheck.Status.UNVERIFIABLE, false, null, List.of()));
        }
        Optional<WorkCalendar> current = calendars == null ? Optional.empty() : calendars.stream()
                .filter(calendar -> covers(calendar, today))
                .max(Comparator.comparing(WorkCalendar::getValidFrom));
        if (current.isEmpty()) {
            return List.of(new FeatureConditionCheck(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                    FeatureConditionCheck.Category.AUTO, FeatureConditionCheck.Scope.OPEN_ONLY,
                    FeatureConditionCheck.Status.UNSATISFIED, false, null, List.of(DETAIL_MISSING)));
        }
        LocalDate end = coverageEnd(current.get().getValidTo(), calendars);
        return List.of(new FeatureConditionCheck(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                FeatureConditionCheck.Category.AUTO, FeatureConditionCheck.Scope.OPEN_ONLY,
                FeatureConditionCheck.Status.SATISFIED, false, end == null ? null : end.plusDays(1),
                List.of(DETAIL_FOUND)));
    }

    /** 暦が切れ目なく続く最後の日（終わりの無い暦があれば null）。 */
    static LocalDate coverageEnd(LocalDate end, List<WorkCalendar> calendars) {
        LocalDate covered = end;
        boolean extended = true;
        while (covered != null && extended) {
            extended = false;
            LocalDate next = covered.plusDays(1);
            for (WorkCalendar calendar : calendars) {
                if (calendar.getValidFrom() != null && !calendar.getValidFrom().isAfter(next)
                        && (calendar.getValidTo() == null || calendar.getValidTo().isAfter(covered))) {
                    covered = calendar.getValidTo();
                    extended = true;
                    break;
                }
            }
        }
        return covered;
    }

    private static boolean covers(WorkCalendar calendar, LocalDate date) {
        return calendar.getValidFrom() != null && !calendar.getValidFrom().isAfter(date)
                && (calendar.getValidTo() == null || !calendar.getValidTo().isBefore(date));
    }
}
