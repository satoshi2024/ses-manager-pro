package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.LifecycleDualEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認の追記監査マッパー。
 *
 * <p>{@code t_lifecycle_dual_event} は追記専用（V195）。訂正が必要なら新しいeventを追記する。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} がSQLの発行前に拒否し、{@code AppendOnlySqlInjector} は
 * 更新・削除の文を注入しない。DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。</p>
 */
@Mapper
public interface LifecycleDualEventMapper extends AppendOnlyMapper<LifecycleDualEvent> {

    @Override
    default String appendOnlyTable() {
        return "t_lifecycle_dual_event";
    }
}
