package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleDualProjection;
import org.apache.ibatis.annotations.Mapper;

/**
 * 二者確認のtaskごとの現在状態マッパー（{@code t_lifecycle_dual_projection}、V195）。
 *
 * <p>B0の7表で唯一の可変表のため、更新・削除を拒否しない。主キーが (tenant_id, task_id) の複合keyで
 * {@code @TableId} を持たないため、ID指定の汎用メソッド（{@code updateById} 等）は生成されない。
 * 更新は projection_version を条件にしたCAS（tenant・taskを明示した条件付き更新）で行う。
 * CASの専用メソッドは coordinator を実装するバッチ（W3-21a）で追加する（計画 §5.12・§7.8）。</p>
 */
@Mapper
public interface LifecycleDualProjectionMapper extends BaseMapper<LifecycleDualProjection> {
}
