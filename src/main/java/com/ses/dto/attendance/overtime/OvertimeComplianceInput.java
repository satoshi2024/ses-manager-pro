package com.ses.dto.attendance.overtime;

import java.time.YearMonth;
import java.util.List;

/**
 * {@link com.ses.service.attendance.overtime.OvertimeComplianceCalculator}への入力。
 *
 * <p>集計（日次→月次、rolling/年間合計）は本入力を組み立てる側（{@code AttendanceCalculator}、
 * B2の対象外）の責務。calculator自身は与えられた月次値を比較・合算するだけで、
 * 「休日労働を含むか」の判定や協定年度の起算はここへ持ち込まない。</p>
 *
 * @param legalEntityId       法人ID（findingの母集団特定用）
 * @param targetMonth         判定対象月
 * @param applicableExemption 適用除外者（管理監督者等）か。trueならルール1〜6を一切判定しない
 * @param currentMonth        対象月の実績（ルール1・4はこれだけを見る）
 * @param agreementYearMonths 現協定年度の開始月〜対象月まで（対象月を含む、古い順）。
 *                            ルール2・3・6が使う。協定年度境界で毎回作り直すことで
 *                            「ルール2/3/6は協定年度またぎでリセット」を満たす
 * @param rollingWindowMonths 対象月までの直近最大6か月（対象月を含む、古い順、協定年度をまたいでよい）。
 *                            ルール5が使う。実データが用意できた月数分だけ渡せばよい
 *                            （不足月を0埋めしない＝そのnをskipする根拠になる）
 * @param agreement           第1段（法人別36協定）の解決結果。行が存在しない法人は{@code null}にする
 *                            （判定不能findingになる。既定値で「適合」にしない）
 */
public record OvertimeComplianceInput(
        Long legalEntityId,
        YearMonth targetMonth,
        boolean applicableExemption,
        OvertimeMonthMinutes currentMonth,
        List<OvertimeMonthMinutes> agreementYearMonths,
        List<OvertimeMonthMinutes> rollingWindowMonths,
        OvertimeAgreementSnapshot agreement
) {
}
