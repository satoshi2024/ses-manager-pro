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

/** NF-05 client×scope×tenant×route template quota bucket。IP/raw pathは保存しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_usage_bucket")
public class ApiUsageBucket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private String scopeCode;
    private String tenantId;
    private String routeTemplate;
    private LocalDateTime minuteWindowStart;
    private Integer minuteCount;
    private LocalDateTime dayWindowStart;
    private Integer dayCount;
    private Integer burstTokens;
    private LocalDateTime burstLastRefillAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
