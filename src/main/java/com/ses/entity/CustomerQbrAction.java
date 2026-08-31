package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * QBRアクションアイテムエンティティ（t_customer_qbr_action）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_qbr_action")
public class CustomerQbrAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** QBR ID */
    private Long qbrId;

    /** タスク件名 */
    private String title;

    /** タスク詳細 */
    private String description;

    /** 担当者ID (sys_user.id) */
    private Long ownerUserId;

    /** 期日 */
    private LocalDate dueDate;

    /** ステータス (OPEN, IN_PROGRESS, COMPLETED, CANCELLED) */
    private String status;

    /** 完了日時 */
    private LocalDateTime completedAt;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;
}
