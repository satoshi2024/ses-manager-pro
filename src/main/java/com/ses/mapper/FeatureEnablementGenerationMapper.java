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
 * 機能の開放の世代（V203）。全ての書込みの transaction は、最初に {@link #insertIgnore} で行を作ってから
 * {@link #selectForUpdate} で lock し、{@link #increment} で1つ上げる（design §2.2・§5.1 の (1)）。
 */
@Mapper
public interface FeatureEnablementGenerationMapper extends BaseMapper<FeatureEnablementGeneration> {

    /** 行が無ければ世代 0 で作る（既にあれば何もしない）。 */
    @Insert("INSERT IGNORE INTO t_feature_enablement_generation (tenant_id, generation, updated_at) "
            + "VALUES (#{tenantId}, 0, #{now})")
    int insertIgnore(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

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
