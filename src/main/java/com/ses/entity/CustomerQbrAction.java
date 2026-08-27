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
 * QBRアクションアイテムエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_qbr_action")
public class CustomerQbrAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long qbrId;

    private String title;

    private String description;

    private Long ownerUserId;

    private LocalDate dueDate;

    private String status;

    private LocalDateTime completedAt;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
