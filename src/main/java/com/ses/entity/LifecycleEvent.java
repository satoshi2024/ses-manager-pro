package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ライフサイクルイベント追記台帳エンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_event")
public class LifecycleEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 案件ID (t_lifecycle_case.id)
     */
    private Long caseId;

    /**
     * タスクID (t_lifecycle_task.id / null可)
     */
    private Long taskId;

    /**
     * イベント種別
     */
    private String eventType;

    /**
     * 操作実行ユーザーID
     */
    private Long actorUserId;

    /** 操作主体区分。自動処理はSYSTEMを明示し、ユーザーIDを代入しない。 */
    private String actorType;

    /** 確認チャネル。 */
    private String confirmationSource;

    /**
     * 操作実行者ロールスナップショット
     */
    private String actorRoleSnapshot;

    /**
     * 遷移前状態
     */
    private String beforeState;

    /**
     * 遷移後状態
     */
    private String afterState;

    /**
     * 詳細JSON
     */
    private String detailsJson;

    /**
     * 発生日時
     */
    private LocalDateTime occurredAt;
}
