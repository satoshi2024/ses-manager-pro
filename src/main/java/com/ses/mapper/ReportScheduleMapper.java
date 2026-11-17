package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理レポートschedule mapper。due claim/completionはclaim token付きCASで行う。
 *
 * <p>lease占有の有無は {@code processing_claimed_at} だけで判定する。{@code processing_claim_token} は
 * {@code processing_claimed_at} が非NULLの間だけ現在のownerを識別する。{@code processing_claimed_at IS NULL}
 * の行に残ったtoken（V185以前の旧版completionが {@code processing_claimed_at} だけをclearした「孤児token」）は、
 * completionが非NULLのclaimedAt一致を要求するためどのworkerも完了できない。したがって未占有として扱い、
 * due snapshotのtoken完全一致を条件に、claim時に新tokenで上書きする（scheduler-claim-fence-plan.md §9）。
 *
 * <p>retry上限（scheduler-claim-fence-plan.md §14.2）: 連続失敗が {@link #MAX_CONSECUTIVE_FAILURES} 回に達したfailure completionは、同じUPDATEで
 * {@code enabled = 0} と停止理由 {@link #RETRY_EXHAUSTED_ERROR_CODE} を保存する（所有権CASと停止を分離しない）。
 * 停止したscheduleは管理者の {@link #updateEnabled} で再開でき、その時だけ連続失敗回数を0へ戻す。
 *
 * <p>期限切れleaseの引き継ぎ（completionに至らずlease期限を超えた前の実行）も連続失敗1回として数える
 * （review P3-6、poison pillの無限引き継ぎ対策）。引き継ぎclaimは同じUPDATEで {@code failure_count} を加算し、
 * 加算後に上限へ達する引き継ぎはclaimせず {@link #markTakeoverExhausted} で停止する。新しい列は使わない。
 */
@Mapper
public interface ReportScheduleMapper extends BaseMapper<ReportSchedule> {

    /**
     * scheduleを自動停止する連続失敗回数（初回実行の失敗を含む。B-NF10-2の決定値）。
     * 値はSQLへ定数として埋め込むため、変更はmapperの変更として扱う（設定値にはしない）。
     */
    int MAX_CONSECUTIVE_FAILURES = 12;

    /** retry上限で自動停止したことを示す {@code last_error_code}。API応答でも停止理由として確認できる。 */
    String RETRY_EXHAUSTED_ERROR_CODE = "SCHEDULE_RETRY_EXHAUSTED";

    /**
     * 起動guard用に、token無しのprocessing lease（legacy lease。V185以前のclaim、またはV185適用後に旧版schedulerが
     * 取得したlease）を数える。{@code claimedSince} を指定した場合はその時刻以降に取得されたleaseだけを数える。
     * 新版のclaimは必ずtokenを保存するため、0件でない限り旧版schedulerの痕跡である（同plan §14.1）。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM m_report_schedule WHERE tenant_id = #{tenantId}
              AND enabled = 1 AND deleted_flag = 0
              AND processing_claimed_at IS NOT NULL AND processing_claim_token IS NULL
              <if test="claimedSince != null">AND processing_claimed_at &gt;= #{claimedSince}</if>
            </script>
            """)
    int countLegacyLeases(@Param("tenantId") String tenantId,
                          @Param("claimedSince") LocalDateTime claimedSince);

    /**
     * due候補を取得する。retry/通常dueは未占有（{@code processing_claimed_at IS NULL}）の行だけを返し、
     * 占有中の行はstale leaseだけを返す。実行中のretry行が毎poll LIMIT枠を占有しないようにする。
     */
    @Select("SELECT * FROM m_report_schedule WHERE tenant_id = #{tenantId} "
            + "AND enabled = 1 AND deleted_flag = 0 "
            + "AND ((processing_claimed_at IS NULL AND retry_scheduled_at IS NOT NULL "
            + "      AND retry_scheduled_at <= #{now}) "
            + "  OR (processing_claimed_at IS NULL AND retry_scheduled_at IS NULL "
            + "      AND next_run_at IS NOT NULL AND next_run_at <= #{now}) "
            + "  OR (processing_claimed_at IS NOT NULL AND processing_claimed_at < #{staleBefore})) "
            + "ORDER BY COALESCE(retry_scheduled_at, processing_logical_run_at, next_run_at), id LIMIT #{limit}")
    List<ReportSchedule> selectDue(@Param("tenantId") String tenantId,
                                   @Param("now") LocalDateTime now,
                                   @Param("staleBefore") LocalDateTime staleBefore,
                                   @Param("limit") int limit);

    /**
     * due snapshotの全状態列に対するCASでclaimする。期待値がNULLの列は {@code IS NULL} として明示照合し、
     * パラメータ型推論に依存する {@code ? IS NULL} は使わない。成功時はper-claimのclaimedAtと新tokenを
     * 同一UPDATEで保存する。{@code next_run_at}/{@code retry_scheduled_at} は変更しない。
     *
     * <p>期限切れleaseの引き継ぎ（{@code expectedClaimedAt} が非NULL。WHERE句で占有中の行だけに一致する）は、
     * completionに至らなかった前の実行を連続失敗1回として {@code failure_count} を同じUPDATEで加算する
     * （review P3-6）。加算後に上限へ達する引き継ぎは一致させない（{@link #markTakeoverExhausted} が停止する）。
     * 加算式は自列だけを参照するため、MySQLのSET句左から評価の影響を受けない。それ以外のclaimは
     * {@code failure_count} を変更しない。
     */
    @Update("""
            <script>
            UPDATE m_report_schedule SET processing_logical_run_at = #{logicalRunAt},
                processing_claimed_at = #{claimedAt}, processing_claim_token = #{claimToken},
                last_run_at = #{logicalRunAt},
                <if test="expectedClaimedAt != null">failure_count = failure_count + 1,</if>
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = #{tenantId} AND id = #{id} AND enabled = 1 AND deleted_flag = 0
              AND <choose><when test="expectedNextRunAt == null">next_run_at IS NULL</when>
                  <otherwise>next_run_at = #{expectedNextRunAt}</otherwise></choose>
              AND <choose><when test="expectedRetryAt == null">retry_scheduled_at IS NULL</when>
                  <otherwise>retry_scheduled_at = #{expectedRetryAt}</otherwise></choose>
              AND <choose><when test="expectedClaimedAt == null">processing_claimed_at IS NULL</when>
                  <otherwise>processing_claimed_at = #{expectedClaimedAt}</otherwise></choose>
              AND <choose><when test="expectedLogicalRunAt == null">processing_logical_run_at IS NULL</when>
                  <otherwise>processing_logical_run_at = #{expectedLogicalRunAt}</otherwise></choose>
              AND <choose><when test="expectedClaimToken == null">processing_claim_token IS NULL</when>
                  <otherwise>processing_claim_token = #{expectedClaimToken}</otherwise></choose>
              AND <choose><when test="expectedLastRunAt == null">last_run_at IS NULL</when>
                  <otherwise>last_run_at = #{expectedLastRunAt}</otherwise></choose>
              AND failure_count = #{expectedFailureCount}
              <if test="expectedClaimedAt != null">AND failure_count + 1 &lt; """
            + MAX_CONSECUTIVE_FAILURES + """
             </if>
              AND ((processing_claimed_at IS NULL AND retry_scheduled_at IS NOT NULL
                    AND retry_scheduled_at &lt;= #{claimedAt})
                OR (processing_claimed_at IS NULL AND retry_scheduled_at IS NULL
                    AND next_run_at IS NOT NULL AND next_run_at &lt;= #{claimedAt})
                OR (processing_claimed_at IS NOT NULL AND processing_claimed_at &lt; #{staleBefore}))
            </script>
            """)
    int claimDue(@Param("tenantId") String tenantId, @Param("id") Long id,
                 @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                 @Param("expectedRetryAt") LocalDateTime expectedRetryAt,
                 @Param("expectedClaimedAt") LocalDateTime expectedClaimedAt,
                 @Param("expectedLogicalRunAt") LocalDateTime expectedLogicalRunAt,
                 @Param("expectedClaimToken") String expectedClaimToken,
                 @Param("expectedLastRunAt") LocalDateTime expectedLastRunAt,
                 @Param("expectedFailureCount") Integer expectedFailureCount,
                 @Param("logicalRunAt") LocalDateTime logicalRunAt,
                 @Param("claimToken") String claimToken,
                 @Param("claimedAt") LocalDateTime claimedAt,
                 @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 成功completion。非NULLのclaim token・claimedAt・logicalRunAtの完全一致を条件に、次回cronへ進めて
     * lease/token/logical run/retry/error/failure countをclearする。0件は所有権喪失を意味する。
     */
    @Update("UPDATE m_report_schedule SET next_run_at = #{nextRunAt}, processing_claimed_at = NULL, "
            + "processing_claim_token = NULL, "
            + "processing_logical_run_at = NULL, retry_scheduled_at = NULL, failure_count = 0, "
            + "last_error_code = NULL, last_error_message = NULL, last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} AND id = #{id} "
            + "AND processing_claim_token = #{claimToken} AND processing_logical_run_at = #{logicalRunAt} "
            + "AND processing_claimed_at = #{claimedAt}")
    int markSuccess(@Param("tenantId") String tenantId, @Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("nextRunAt") LocalDateTime nextRunAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt);

    /**
     * 失敗completion。成功と同じ所有者条件で、lease/tokenだけをclearしlogical runを保持する。
     * failure countは所有者条件下のDB式で加算し、古いsnapshot値で上書きしない。0件は所有権喪失を意味する。
     *
     * <p>加算後の連続失敗回数が {@link #MAX_CONSECUTIVE_FAILURES} 以上なら、同じUPDATEで {@code enabled = 0} と
     * {@code last_error_code = }{@link #RETRY_EXHAUSTED_ERROR_CODE} を保存する。retry予定とlogical runは保持し、
     * 管理者が再有効化した時に同一論理runから再開できるようにする。
     *
     * <p><b>SET句の順序に依存する。</b>MySQLの単一表UPDATEはSET句を左から評価し、後ろの式は更新後の列値を参照する
     * （H2は標準SQLどおり更新前の値）。両DBで同じ結果にするため、{@code failure_count} を参照する式は必ず
     * {@code failure_count} の代入より前に置く。
     */
    @Update("UPDATE m_report_schedule SET "
            + "enabled = CASE WHEN COALESCE(failure_count, 0) + 1 >= " + MAX_CONSECUTIVE_FAILURES
            + " THEN 0 ELSE enabled END, "
            + "last_error_code = COALESCE(CASE WHEN COALESCE(failure_count, 0) + 1 >= " + MAX_CONSECUTIVE_FAILURES
            + " THEN '" + RETRY_EXHAUSTED_ERROR_CODE + "' END, #{errorCode}), "
            + "failure_count = COALESCE(failure_count, 0) + 1, "
            + "retry_scheduled_at = #{retryAt}, processing_claimed_at = NULL, processing_claim_token = NULL, "
            + "last_error_message = #{errorMessage}, "
            + "last_run_at = #{logicalRunAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND processing_claim_token = #{claimToken} "
            + "AND processing_logical_run_at = #{logicalRunAt} AND processing_claimed_at = #{claimedAt}")
    int markFailure(@Param("tenantId") String tenantId, @Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("retryAt") LocalDateTime retryAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt,
                    @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    /**
     * 引き継げば連続失敗が上限に達する期限切れleaseを、引き継がずに停止する（review P3-6）。completionに至らずlease期限を
     * 超えた前の実行を失敗1回として加算し、{@link #markFailure} の上限到達時と同じ状態（{@code enabled = 0}、停止理由
     * {@link #RETRY_EXHAUSTED_ERROR_CODE}、retry予定と論理runの保持、lease/tokenのclear）にする。管理者の
     * {@link #updateEnabled} で連続失敗回数を0へ戻し、保持したretryから同一論理runを再開できる。
     *
     * <p>条件は {@link #claimDue} の引き継ぎと同じdue snapshotの全状態列CASに、期限切れ（{@code staleBefore} より前）と
     * 上限到達を加えたもの。tokenをclearするため、期限後も実行中だった旧workerのcompletionは0件になる。
     * SET句の式は自列またはパラメータだけを参照し、評価順に依存しない。0件は他workerが先に状態を変えたことを意味する。
     */
    @Update("<script>UPDATE m_report_schedule SET enabled = 0, "
            + "last_error_code = '" + RETRY_EXHAUSTED_ERROR_CODE + "', last_error_message = #{errorMessage}, "
            + "failure_count = failure_count + 1, retry_scheduled_at = #{retryAt}, "
            + "processing_claimed_at = NULL, processing_claim_token = NULL, "
            + "processing_logical_run_at = #{logicalRunAt}, last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND enabled = 1 AND deleted_flag = 0 "
            + "AND <choose><when test=\"expectedNextRunAt == null\">next_run_at IS NULL</when>"
            + "<otherwise>next_run_at = #{expectedNextRunAt}</otherwise></choose> "
            + "AND <choose><when test=\"expectedRetryAt == null\">retry_scheduled_at IS NULL</when>"
            + "<otherwise>retry_scheduled_at = #{expectedRetryAt}</otherwise></choose> "
            + "AND processing_claimed_at = #{expectedClaimedAt} "
            + "AND <choose><when test=\"expectedLogicalRunAt == null\">processing_logical_run_at IS NULL</when>"
            + "<otherwise>processing_logical_run_at = #{expectedLogicalRunAt}</otherwise></choose> "
            + "AND <choose><when test=\"expectedClaimToken == null\">processing_claim_token IS NULL</when>"
            + "<otherwise>processing_claim_token = #{expectedClaimToken}</otherwise></choose> "
            + "AND <choose><when test=\"expectedLastRunAt == null\">last_run_at IS NULL</when>"
            + "<otherwise>last_run_at = #{expectedLastRunAt}</otherwise></choose> "
            + "AND failure_count = #{expectedFailureCount} "
            + "AND failure_count + 1 &gt;= " + MAX_CONSECUTIVE_FAILURES + " "
            + "AND processing_claimed_at &lt; #{staleBefore}</script>")
    int markTakeoverExhausted(@Param("tenantId") String tenantId, @Param("id") Long id,
                              @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                              @Param("expectedRetryAt") LocalDateTime expectedRetryAt,
                              @Param("expectedClaimedAt") LocalDateTime expectedClaimedAt,
                              @Param("expectedLogicalRunAt") LocalDateTime expectedLogicalRunAt,
                              @Param("expectedClaimToken") String expectedClaimToken,
                              @Param("expectedLastRunAt") LocalDateTime expectedLastRunAt,
                              @Param("expectedFailureCount") Integer expectedFailureCount,
                              @Param("logicalRunAt") LocalDateTime logicalRunAt,
                              @Param("retryAt") LocalDateTime retryAt,
                              @Param("staleBefore") LocalDateTime staleBefore,
                              @Param("errorMessage") String errorMessage);

    /**
     * completion結果を確認できなかった後の状態確認用に、tenant内の1行を読む（論理削除済みは返さない）。
     * scheduler以外の経路から使わない。
     */
    @Select("SELECT * FROM m_report_schedule WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_flag = 0")
    ReportSchedule selectForTenant(@Param("tenantId") String tenantId, @Param("id") Long id);

    /**
     * DBサーバーの現在時刻（UNIX epoch秒。session time zoneに依存しない）。本nodeの時計との差を測る診断用
     * （review P2-2。ShedLockはDB時刻、processing leaseはnode時刻で判定するため）。H2はMySQL modeで同名関数を持つ。
     */
    @Select("SELECT UNIX_TIMESTAMP()")
    Long selectDbEpochSecond();

    /**
     * 管理者のenable/disable。lease/next/retry/failure列を読取時点の値で書き戻さないよう、enabledと監査列、
     * 楽観lock versionだけを更新する。論理削除済み行は対象外。更新者不明（null）の場合は既存の
     * {@code updated_by} を保持する（global {@code update-strategy: not_null} と同じ扱い）。
     *
     * <p>retry上限で自動停止した行（{@code enabled = 0} かつ {@code last_error_code = }
     * {@link #RETRY_EXHAUSTED_ERROR_CODE}）を有効化する時だけ、連続失敗回数を0へ戻す（手動再開）。
     * retry予定・logical run・停止理由のerror codeは保持し、次のcompletionが上書き/clearする。
     * {@code failure_count} の式は {@code enabled} の代入より前に置く（MySQLのSET句左から評価への対策）。
     */
    @Update("<script>UPDATE m_report_schedule SET "
            + "<if test=\"enabled == 1\">failure_count = CASE WHEN enabled = 0 AND last_error_code = '"
            + RETRY_EXHAUSTED_ERROR_CODE + "' THEN 0 ELSE failure_count END, </if>"
            + "enabled = #{enabled}, "
            + "<if test=\"updatedBy != null\">updated_by = #{updatedBy}, </if>"
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_flag = 0</script>")
    int updateEnabled(@Param("tenantId") String tenantId, @Param("id") Long id,
                      @Param("enabled") int enabled, @Param("updatedBy") Long updatedBy);
}
