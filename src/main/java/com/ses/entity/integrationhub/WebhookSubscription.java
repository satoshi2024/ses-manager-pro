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

/** NF-05 inbound/outbound webhook subscription persistence。送受信処理は別scope。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_webhook_subscription")
public class WebhookSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private String direction;
    private String eventType;
    private String endpointUrl;
    private String keyId;
    private Integer signingCredentialVersion;
    private String encryptedSigningSecret;
    private String cryptoKeyVersion;
    private String dataScopeJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
