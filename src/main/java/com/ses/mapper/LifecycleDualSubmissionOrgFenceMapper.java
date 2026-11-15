package com.ses.mapper;

import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.entity.LifecycleDualSubmissionOrgFence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認の提出時組織集合マッパー。
 *
 * <p>{@code t_lifecycle_dual_submission_org_fence} は追記専用（V195）。主キーが複合keyのためID指定の汎用メソッドは生成されない。
 * 更新・削除・insertOrUpdate は {@link AppendOnlyMapper} がSQLの発行前に拒否し、{@code AppendOnlySqlInjector} は
 * 更新・削除の文を注入しない。DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する。</p>
 */
@Mapper
public interface LifecycleDualSubmissionOrgFenceMapper extends AppendOnlyMapper<LifecycleDualSubmissionOrgFence> {

    @Override
    default String appendOnlyTable() {
        return "t_lifecycle_dual_submission_org_fence";
    }
}
