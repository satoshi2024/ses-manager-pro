package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureEnablementState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 開放単位の開放状態（V203）。状態の遷移は version の CAS で、列を名指しした {@code UpdateWrapper} で行う（NULL に戻す列は
 * {@code set(col, null)}。{@code FieldStrategy.ALWAYS} を使わない）。lock の順は世代の行の後、親→子・同じ階層は key の昇順
 * （design §5.1 の (2)）。
 */
@Mapper
public interface FeatureEnablementStateMapper extends BaseMapper<FeatureEnablementState> {

    /** tenant の全ての開放状態（snapshot の読込み）。 */
    @Select("SELECT * FROM t_feature_enablement_state WHERE tenant_id = #{tenantId} ORDER BY feature_key")
    List<FeatureEnablementState> selectByTenant(@Param("tenantId") String tenantId);

    /** 1件の開放状態を X lock して読む（行が無ければ null）。 */
    @Select("SELECT * FROM t_feature_enablement_state WHERE tenant_id = #{tenantId} AND feature_key = #{featureKey} "
            + "FOR UPDATE")
    FeatureEnablementState selectByKeyForUpdate(@Param("tenantId") String tenantId,
                                                @Param("featureKey") String featureKey);
}
