package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureEnablementGeneration;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 機能の開放の世代（V203）。全ての書込みの transaction は、最初に {@link #lockRow} で行を作るか排他 lock を取り、続けて
 * {@link #selectForUpdate} で読み、{@link #increment} で1つ上げる（design §2.2・§5.1 の (1)）。
 *
 * <p>{@code INSERT IGNORE} を使わない: 重複した行への {@code INSERT IGNORE} は共有 lock（duplicate-key の S lock）を取り、その後の
 * {@code FOR UPDATE} で排他 lock へ昇格する。二つの書き手が共に S lock を持った後にそれぞれ昇格を待つと deadlock（1213）になる
 * （review rb-fe-a1 P1-1。同じ理由の先例: {@code PrivacyHoldProjectionStore.ensureStateLocked}・
 * {@code JdbcPrivacyJournalAdapter}・{@code JdbcPrivacyPartitionFence}・{@code ProductionPrivacyJournalAdapter}）。no-op の upsert は
 * 重複の時に初めから排他 lock を取るため、二つ目の書き手は一つ目の commit まで待ち、昇格が起きない。</p>
 */
@Mapper
public interface FeatureEnablementGenerationMapper extends BaseMapper<FeatureEnablementGeneration> {

    /** 行が無ければ世代 0 で作り、あれば値を変えずに排他 lock だけを取る（no-op の upsert）。 */
    @Insert("INSERT INTO t_feature_enablement_generation (tenant_id, generation, updated_at) "
            + "VALUES (#{tenantId}, 0, #{now}) ON DUPLICATE KEY UPDATE generation = generation")
    int lockRow(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

    /** 世代の行を X lock して読む。 */
    @Select("SELECT tenant_id, generation, updated_at FROM t_feature_enablement_generation "
            + "WHERE tenant_id = #{tenantId} FOR UPDATE")
    FeatureEnablementGeneration selectForUpdate(@Param("tenantId") String tenantId);

    /** 世代を1つ上げる（lock を持つ transaction の中で呼ぶ）。 */
    @Update("UPDATE t_feature_enablement_generation SET generation = generation + 1, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId}")
    int increment(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

    /** snapshot の poll が読む世代（行が無ければ null。世代 0 として扱う）。 */
    @Select("SELECT generation FROM t_feature_enablement_generation WHERE tenant_id = #{tenantId}")
    Long selectGeneration(@Param("tenantId") String tenantId);
}
