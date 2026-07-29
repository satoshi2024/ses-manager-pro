package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 外部Identity Provider設定。secret本体は保存せず参照だけを持つ。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_identity_provider")
public class IdentityProvider extends BaseEntity {
    private String tenantId;
    private String providerType;
    private String issuerUri;
    private String metadataUri;
    private String clientId;
    private String encryptedSecretRef;
    private Integer enabled;
}
