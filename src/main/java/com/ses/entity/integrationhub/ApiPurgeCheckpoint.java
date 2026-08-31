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

/** NF-05 purge resume位置。削除可否の正本ではなくbounded batchのcheckpoint。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_purge_checkpoint")
public class ApiPurgeCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordKind;
    private String retentionClass;
    private Long restoreEpoch;
    private LocalDateTime lastExpiresAt;
    private Long lastRecordId;
    private String runStatus;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    @Version
    private Integer version;
    private LocalDateTime updatedAt;
}
