package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** NF-05 idempotency record。canonical digestとsafe response snapshotだけを保存する。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_idempotency_record")
public class ApiIdempotencyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private String routeTemplate;
    private String idempotencyKey;
    private String requestDigest;
    private String status;
    private Integer responseStatus;
    private String safeResponseSnapshot;
    private String retentionClass;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime terminalAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
