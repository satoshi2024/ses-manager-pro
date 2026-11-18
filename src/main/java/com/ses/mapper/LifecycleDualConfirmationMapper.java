package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.LifecycleDualConfirmation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認の確認・差戻しマッパー。
 *
 * <p>{@code t_lifecycle_dual_confirmation} は追記専用（V195）。提出ごとに1行だけ作る。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} がSQLの発行前に拒否し、{@code AppendOnlySqlInjector} は
 * 更新・削除の文を注入しない。DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。</p>
 */
@Mapper
public interface LifecycleDualConfirmationMapper extends AppendOnlyMapper<LifecycleDualConfirmation> {

    @Override
    default String appendOnlyTable() {
        return "t_lifecycle_dual_confirmation";
    }
}
