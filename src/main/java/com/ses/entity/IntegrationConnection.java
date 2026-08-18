package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 外部サービス連携接続マスタ (m_integration_connection)。
 * tenant_id / legal_entity_id / provider / product 単位の接続と暗号化tokenを管理する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_integration_connection")
public class IntegrationConnection {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** テナントID */
    private String tenantId;

    /** 法人ID（NULL=共通） */
    private Long legalEntityId;

    /** プロバイダ (freee / csv / mock 等) */
    private String provider;

    /** プロダクト種別 (accounting / payroll 等) */
    private String product;

    /** 外部事業所ID (freee company_id等) */
    private Long externalCompanyId;

    /** 外部事業所/会社名 */
    private String companyName;

    /** 暗号化されたトークン情報JSON (accessToken, refreshToken等) */
    private String encryptedTokens;

    /** トークン有効期限 */
    private LocalDateTime expiresAt;

    /** 接続状態 (CONNECTED / REAUTH_REQUIRED / DISCONNECTED) */
    private String status;

    /** 接続実行ユーザーID */
    private Long connectedBy;

    /** 接続日時 */
    private LocalDateTime connectedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;

    @Version
    private Integer version;
}
