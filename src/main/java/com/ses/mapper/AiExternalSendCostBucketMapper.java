package com.ses.mapper;

import com.ses.entity.AiExternalSendCostBucket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * NF08 永続費用ledgerの日次・月次bucket。annotation SQLだけを持ち、汎用CRUD（BaseMapper）を継承しない。
 * <p>呼出しは {@code PersistentExternalSendCostLedger} に限り、lock順（run → DAY → MONTH → entry）はclaim serviceが固定する。</p>
 */
@Mapper
public interface AiExternalSendCostBucketMapper {

    String BUCKET_COLUMNS = "id, cost_scope, bucket_type, bucket_key, committed_yen, last_limit_yen, "
            + "last_approval_digest, clock_high_water_at, version, created_at, updated_at ";

    /**
     * DAY・MONTHの2行の存在を保証する（claimとは別の短いtransactionで呼ぶ）。既存行は変更しない。
     * INSERT IGNOREはCHECK違反・切詰めを隠すため使わない。
     */
    @Insert("INSERT INTO t_ai_external_send_cost_bucket (cost_scope, bucket_type, bucket_key) "
            + "VALUES (#{scope}, 'DAY', #{dayKey}), (#{scope}, 'MONTH', #{monthKey}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int ensureBuckets(@Param("scope") String scope, @Param("dayKey") String dayKey,
                      @Param("monthKey") String monthKey);

    /** claim transaction内で1行を排他lockして読む。行が無ければnull（再試行可能なbucket欠落）。 */
    @Select("SELECT " + BUCKET_COLUMNS + "FROM t_ai_external_send_cost_bucket "
            + "WHERE cost_scope = #{scope} AND bucket_type = #{type} AND bucket_key = #{key} FOR UPDATE")
    AiExternalSendCostBucket lockBucket(@Param("scope") String scope, @Param("type") String type,
                                        @Param("key") String key);

    /** 読取専用の現在値（lockしない）。 */
    @Select("SELECT " + BUCKET_COLUMNS + "FROM t_ai_external_send_cost_bucket "
            + "WHERE cost_scope = #{scope} AND bucket_type = #{type} AND bucket_key = #{key}")
    AiExternalSendCostBucket selectBucket(@Param("scope") String scope, @Param("type") String type,
                                          @Param("key") String key);

    /**
     * lock済みの行へ上限内でだけ予約を積む。versionを必ず増やし（found rowsとchanged rowsを一致させる）、
     * 観測時刻の高水位は増やす方向にだけ更新する。0件は上限超過を意味する（行はlock済みで消えない）。
     */
    @Update("UPDATE t_ai_external_send_cost_bucket SET committed_yen = committed_yen + #{yen}, "
            + "version = version + 1, last_limit_yen = #{limit}, last_approval_digest = #{approvalDigest}, "
            + "clock_high_water_at = CASE WHEN clock_high_water_at IS NULL OR clock_high_water_at < #{observedAt} "
            + "THEN #{observedAt} ELSE clock_high_water_at END "
            + "WHERE id = #{id} AND committed_yen + #{yen} <= #{limit}")
    int reserve(@Param("id") Long id, @Param("yen") long yen, @Param("limit") long limit,
                @Param("approvalDigest") byte[] approvalDigest, @Param("observedAt") LocalDateTime observedAt);

    /** 同じcost_scopeの全bucket（別日・別月を含む）の時刻高水位。索引 idx_ai_cost_clock を使う。 */
    @Select("SELECT MAX(clock_high_water_at) FROM t_ai_external_send_cost_bucket WHERE cost_scope = #{scope}")
    LocalDateTime selectClockHighWater(@Param("scope") String scope);
}
