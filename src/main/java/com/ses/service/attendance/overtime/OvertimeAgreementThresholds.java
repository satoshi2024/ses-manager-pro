package com.ses.service.attendance.overtime;

/**
 * 閾値解決の第1段（法人別36協定）。overtime-rules.md §3 の優先順位1に対応する。
 *
 * <p>本interfaceはS11 F1（V78、{@code m_overtime_agreement}）が実装するまで未接続のまま残す
 * （B2の対象外）。個々のlimit系accessorが{@code null}を返した場合は、その項目だけ
 * 第2段（{@code m_system_config}）へfall throughする。</p>
 */
public interface OvertimeAgreementThresholds {

    /**
     * 特別条項の有無。falseの場合、ルール3/4/5/6は判定しない（overtime-rules.md §3）。
     */
    boolean specialClauseEnabled();

    /** ルール1（月の時間外）上限・分。 */
    Integer monthNormalLimitMinutes();

    /** ルール2（年の時間外）上限・分。 */
    Integer yearNormalLimitMinutes();

    /** ルール3（年の時間外・特別条項）上限・分。 */
    Integer yearSpecialLimitMinutes();

    /** ルール4（月の合計）上限・分。 */
    Integer monthTotalLimitMinutes();

    /** ルール5（複数月平均）上限・分。 */
    Integer multiMonthAverageLimitMinutes();

    /** ルール6（45時間超の月数）上限・回。 */
    Integer exceedMonthCountLimit();
}
