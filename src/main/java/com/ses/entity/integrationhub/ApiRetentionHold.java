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

/** NF-05 legal hold metadata。payload/raw body/PIIを複製しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_retention_hold")
public class ApiRetentionHold {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordKind;
    private Long recordId;
    private String status;
    private Integer holdGeneration;
    private String reasonCode;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime releasedAt;
    private LocalDateTime updatedAt;
}
