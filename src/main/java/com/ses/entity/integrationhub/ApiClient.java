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

/** NF-05 B2B client binding。内部IDを外部契約へ返さない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_api_client")
public class ApiClient {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private String ownerRef;
    private String tenantId;
    private Long legalEntityId;
    private String dataScopeJson;
    private String allowedCidrs;
    private String clientTier;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
