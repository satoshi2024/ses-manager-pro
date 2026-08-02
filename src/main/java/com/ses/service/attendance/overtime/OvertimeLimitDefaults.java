package com.ses.service.attendance.overtime;

/**
 * 閾値解決の第2段（config key）と第3段（コード定数）。overtime-rules.md §1 Tier Aの確定値。
 *
 * <p>値を変えるときは本クラスではなく{@code m_system_config}の当該keyを変更する
 * （overtime-rules.md §4.1）。本クラスの定数はDBが空でも計算が壊れないための最終fallbackであり、
 * 運用でここに依存しない。</p>
 */
public final class OvertimeLimitDefaults {

    public static final String CONFIG_MONTH_NORMAL = "overtime.limit.month-normal";
    public static final String CONFIG_YEAR_NORMAL = "overtime.limit.year-normal";
    public static final String CONFIG_YEAR_SPECIAL = "overtime.limit.year-special";
    public static final String CONFIG_MONTH_TOTAL = "overtime.limit.month-total";
    public static final String CONFIG_MULTI_MONTH_AVERAGE = "overtime.limit.multi-month-average";
    public static final String CONFIG_EXCEED_MONTH_COUNT = "overtime.limit.exceed-month-count";

    /** ルール1: 月45時間。 */
    public static final int MONTH_NORMAL_MINUTES = 45 * 60;
    /** ルール2: 年360時間。 */
    public static final int YEAR_NORMAL_MINUTES = 360 * 60;
    /** ルール3: 年720時間（特別条項）。 */
    public static final int YEAR_SPECIAL_MINUTES = 720 * 60;
    /** ルール4: 月100時間（休日労働を含む合計）。 */
    public static final int MONTH_TOTAL_MINUTES = 100 * 60;
    /** ルール5: 複数月平均80時間（休日労働を含む合計）。 */
    public static final int MULTI_MONTH_AVERAGE_MINUTES = 80 * 60;
    /** ルール6: 45時間超の月数、年6回まで。 */
    public static final int EXCEED_MONTH_COUNT = 6;

    private OvertimeLimitDefaults() {
    }
}
