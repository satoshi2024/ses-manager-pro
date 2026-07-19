package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 要員↔ログインアカウント紐付け（物理行・履歴不要）。
 */
@Data
@TableName("t_engineer_account_link")
public class EngineerAccountLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long engineerId;
    private Long sysUserId;
    private Long linkedBy;
    private LocalDateTime linkedAt;
}
