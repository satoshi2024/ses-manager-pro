package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二者確認のtaskごとの現在状態（{@code t_lifecycle_dual_projection}、V195）。
 *
 * <p>B0の7表で唯一の可変表。主キーは (tenant_id, task_id) の複合keyのため {@code @TableId} を置かない
 * （ID指定の汎用更新は生成されない）。更新は projection_version のCASで行う（計画 §5.12。W3-21aで実装する）。
 * 指す提出は (tenant, task, 世代, revision, 提出ID, 証跡hash) の複合FKで束縛される。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_projection")
public class LifecycleDualProjection {

    /** テナントID */
    private String tenantId;

    /** タスクID (t_lifecycle_task.id。taskごとに1行) */
    private Long taskId;

    /** 案件ID (t_lifecycle_case.id) */
    private Long caseId;

    /** 現在の提出のtask世代 */
    private Long taskGeneration;

    /** 現在の提出版 */
    private Integer revision;

    /** 現在の提出ID */
    private Long submissionId;

    /** 現在の提出の証跡hash */
    private String evidenceSha256;

    /** 状態: PENDING / CONFIRMED / REJECTED */
    private String state;

    /** CAS用の版 */
    private Long projectionVersion;

    /** 更新日時（DBの既定値。CASの更新文で明示する） */
    private LocalDateTime updatedAt;
}
