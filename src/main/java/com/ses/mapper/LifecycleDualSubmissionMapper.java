package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.LifecycleDualSubmission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認の提出マッパー。
 *
 * <p>{@code t_lifecycle_dual_submission} は追記専用（V195）。再提出は revision を進めた新しい行で表す。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} がSQLの発行前に拒否し、{@code AppendOnlySqlInjector} は
 * 更新・削除の文を注入しない。DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。</p>
 */
@Mapper
public interface LifecycleDualSubmissionMapper extends AppendOnlyMapper<LifecycleDualSubmission> {

    @Override
    default String appendOnlyTable() {
        return "t_lifecycle_dual_submission";
    }
}
