package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.FeatureSignOffExtension;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 証拠の類の署名の延長の記録（V203）。{@code t_feature_sign_off_extension} は追記専用。更新・削除・insertOrUpdate は
 * {@link AppendOnlyMapper} が SQL の発行前に拒否し、{@code AppendOnlySqlInjector} は更新・削除の文を注入しない。DB でも
 * BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。
 */
@Mapper
public interface FeatureSignOffExtensionMapper extends AppendOnlyMapper<FeatureSignOffExtension> {

    @Override
    default String appendOnlyTable() {
        return "t_feature_sign_off_extension";
    }

    /** 署名の延長の記録（古い順）。 */
    @Select("SELECT * FROM t_feature_sign_off_extension WHERE sign_off_id = #{signOffId} ORDER BY id")
    List<FeatureSignOffExtension> selectBySignOff(@Param("signOffId") Long signOffId);
}
