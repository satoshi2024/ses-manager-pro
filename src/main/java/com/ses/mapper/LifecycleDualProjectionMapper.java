package com.ses.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleDualProjection;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 二者確認のtaskごとの現在状態マッパー（{@code t_lifecycle_dual_projection}、V195）。
 *
 * <p>B0の7表で唯一の可変表のため、更新・削除を拒否しない。主キーが (tenant_id, task_id) の複合keyで
 * {@code @TableId} を持たないため、ID指定の汎用メソッド（{@code updateById} 等）は生成されない。
 * 更新は {@link #compareAndSet} だけで行う（W3-21a、計画 §5.12・§7.8）。</p>
 *
 * <p>列名は注釈SQLや文字列に書かず、entityのmetadataから得る（{@code LambdaUpdateWrapper}）。B0の
 * {@code task_generation} は {@code AuthorizationEpochColumnIsolationTest} の監視対象と同名のため（W3-18 review P3-10 (i)）。</p>
 */
@Mapper
public interface LifecycleDualProjectionMapper extends BaseMapper<LifecycleDualProjection> {

    /**
     * projectionの比較交換。tenant・task・{@code expectedVersion} が一致する行だけを {@code next} の内容へ置き換え、
     * {@code projection_version} を1つ進める。列に {@code ON UPDATE} が無いため {@code updated_at} は明示して更新する
     * （W3-18 review P3-10 (iii)）。hashは小文字hexで渡すこと（列は {@code utf8mb4_unicode_ci} で大文字小文字を区別しない）。
     *
     * @param next 置き換え後の内容（tenant・taskは条件、case・世代・revision・提出・証跡hash・状態を書く）
     * @param expectedVersion 読み取った時点の {@code projection_version}
     * @return 更新した行数（0 = 他者が先に更新した、または行が無い）
     */
    default int compareAndSet(LifecycleDualProjection next, long expectedVersion) {
        Objects.requireNonNull(next, "next");
        LambdaUpdateWrapper<LifecycleDualProjection> update = new LambdaUpdateWrapper<LifecycleDualProjection>()
                .eq(LifecycleDualProjection::getTenantId, Objects.requireNonNull(next.getTenantId(), "tenantId"))
                .eq(LifecycleDualProjection::getTaskId, Objects.requireNonNull(next.getTaskId(), "taskId"))
                .eq(LifecycleDualProjection::getProjectionVersion, expectedVersion)
                .set(LifecycleDualProjection::getCaseId, Objects.requireNonNull(next.getCaseId(), "caseId"))
                .set(LifecycleDualProjection::getTaskGeneration,
                        Objects.requireNonNull(next.getTaskGeneration(), "taskGeneration"))
                .set(LifecycleDualProjection::getRevision, Objects.requireNonNull(next.getRevision(), "revision"))
                .set(LifecycleDualProjection::getSubmissionId,
                        Objects.requireNonNull(next.getSubmissionId(), "submissionId"))
                .set(LifecycleDualProjection::getEvidenceSha256,
                        Objects.requireNonNull(next.getEvidenceSha256(), "evidenceSha256"))
                .set(LifecycleDualProjection::getState, Objects.requireNonNull(next.getState(), "state"))
                .set(LifecycleDualProjection::getProjectionVersion, expectedVersion + 1)
                .set(LifecycleDualProjection::getUpdatedAt,
                        next.getUpdatedAt() == null ? LocalDateTime.now() : next.getUpdatedAt());
        return update(null, update);
    }
}
