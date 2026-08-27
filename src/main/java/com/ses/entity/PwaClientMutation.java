package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 要員PWAのclient commandを、ユーザーscope付きで一度だけ実行するための専用ledger。 */
@Data
@TableName("t_pwa_client_mutation")
public class PwaClientMutation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientRequestId;
    private Long userId;
    private String userScopeHash;
    private String operation;
    private String screen;
    private String workMonth;
    private String payloadHash;
    private Integer baseVersion;
    private String status;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
