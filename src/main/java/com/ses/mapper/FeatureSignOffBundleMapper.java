package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FeatureSignOffBundle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 署名の組（V203）。状態の遷移は version の CAS で、列を名指しした {@code UpdateWrapper} で行う。
 */
@Mapper
public interface FeatureSignOffBundleMapper extends BaseMapper<FeatureSignOffBundle> {

    /** tenant の確認済みの組（snapshot の読込み。種類ごとに高々1件）。 */
    @Select("SELECT * FROM t_feature_sign_off_bundle WHERE tenant_id = #{tenantId} AND status = 'CONFIRMED' "
            + "ORDER BY bundle_kind")
    List<FeatureSignOffBundle> selectConfirmedByTenant(@Param("tenantId") String tenantId);

    /** 組を X lock して読む。 */
    @Select("SELECT * FROM t_feature_sign_off_bundle WHERE id = #{id} FOR UPDATE")
    FeatureSignOffBundle selectByIdForUpdate(@Param("id") Long id);

    /** 種類の確認済みの組を X lock して読む（無ければ null）。 */
    @Select("SELECT * FROM t_feature_sign_off_bundle WHERE tenant_id = #{tenantId} AND bundle_kind = #{bundleKind} "
            + "AND status = 'CONFIRMED' FOR UPDATE")
    FeatureSignOffBundle selectConfirmedByKindForUpdate(@Param("tenantId") String tenantId,
                                                        @Param("bundleKind") String bundleKind);
}
