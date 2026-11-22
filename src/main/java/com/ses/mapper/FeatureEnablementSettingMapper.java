package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureEnablementSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 承認で変える設定（V203）。値を未設定へ戻す更新は {@code UpdateWrapper.set(col, null)} で列を名指しする。
 */
@Mapper
public interface FeatureEnablementSettingMapper extends BaseMapper<FeatureEnablementSetting> {

    /** tenant の全ての設定（snapshot の読込み）。 */
    @Select("SELECT * FROM t_feature_enablement_setting WHERE tenant_id = #{tenantId} ORDER BY setting_key")
    List<FeatureEnablementSetting> selectByTenant(@Param("tenantId") String tenantId);

    /** 1件の設定を X lock して読む（design §5.1 の (5)。行が無ければ null）。 */
    @Select("SELECT * FROM t_feature_enablement_setting WHERE tenant_id = #{tenantId} AND setting_key = #{settingKey} "
            + "FOR UPDATE")
    FeatureEnablementSetting selectByKeyForUpdate(@Param("tenantId") String tenantId,
                                                  @Param("settingKey") String settingKey);
}
