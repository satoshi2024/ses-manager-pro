package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

/**
 * 外部アカウントシステムマスタエンティティ (m_external_account_system)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_external_account_system")
public class ExternalAccountSystem extends BaseEntity {

    /**
     * システムコード (例: GOOGLE_WORKSPACE, GITHUB, SLACK, AWS_IAM)
     */
    private String systemCode;

    /**
     * システム名称
     */
    private String systemName;

    /**
     * システム種別: IDP, SAAS_MAIL, SAAS_COLLAB, CLOUD_INFRA, MDM
     */
    private String systemType;

    /**
     * 認可方式: SAML_OIDC, OAUTH2, DIRECT_PROVISION
     */
    @Builder.Default
    private String authType = "SAML_OIDC";

    /**
     * 有効フラグ: 1=有効, 0=無効
     */
    @Builder.Default
    private Integer isActive = 1;
}
