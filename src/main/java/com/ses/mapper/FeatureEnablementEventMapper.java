package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.FeatureEnablementEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 機能の開放の遷移の履歴（V203）。{@code t_feature_enablement_event} は追記専用。訂正が必要なら新しい行を追記する。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} が SQL の発行前に拒否し、{@code AppendOnlySqlInjector} は更新・削除の
 * 文を注入しない。DB でも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。
 */
@Mapper
public interface FeatureEnablementEventMapper extends AppendOnlyMapper<FeatureEnablementEvent> {

    @Override
    default String appendOnlyTable() {
        return "t_feature_enablement_event";
    }

    /** 対象の履歴（新しい順）。 */
    @Select("SELECT * FROM t_feature_enablement_event WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType} "
            + "AND subject_key = #{subjectKey} ORDER BY id DESC")
    List<FeatureEnablementEvent> selectBySubject(@Param("tenantId") String tenantId,
                                                 @Param("subjectType") String subjectType,
                                                 @Param("subjectKey") String subjectKey);
}
