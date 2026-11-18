package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.LifecycleDualIdempotency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認の冪等性マッパー。
 *
 * <p>{@code t_lifecycle_dual_idempotency} は追記専用（V195）。同じkeyの再送は既存行を読んで保存済みの結果を返す。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} がSQLの発行前に拒否し、{@code AppendOnlySqlInjector} は
 * 更新・削除の文を注入しない。DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。</p>
 */
@Mapper
public interface LifecycleDualIdempotencyMapper extends AppendOnlyMapper<LifecycleDualIdempotency> {

    @Override
    default String appendOnlyTable() {
        return "t_lifecycle_dual_idempotency";
    }
}
