package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * V106.1 で token_version / refresh_lease_* 列を追加。
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

    /** トークン最終リフレッシュ日時 */
    private LocalDateTime lastRefreshedAt;

    /**
     * トークン更新世代番号。multi-node Token Refresh の 3段階リース CAS で使用する。
     * Step 1 のリース獲得条件と Step 3 の Fencing CAS 条件の双方で参照する。
     * V106.1 追加列。
     */
    @Builder.Default
    private Integer tokenVersion = 1;

    /**
     * トークン更新排他リースUUID。
     * Step 1 でリース獲得に成功したノードが書き込み、Step 3 の Fencing CAS で照合する。
     * NULL = リース非保有（待機可能）。V106.1 追加列。
     */
    private String refreshLeaseToken;

    /**
     * トークン更新排他リース期限。
     * NOW() > refresh_lease_expires_at の場合はリース失効とみなし他ノードが獲得可能。
     * V106.1 追加列。
     */
    private LocalDateTime refreshLeaseExpiresAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;

    @Version
    private Integer version;

    /**
     * 生成列 legal_entity_key / active_slot はMyBatis-Plusの自動INSERT/UPDATEから除外する。
     * MySQL 生成列は INSERT 時に値を指定できないため exist = false で除外。
     */
    @TableField(exist = false)
    private Long legalEntityKey;

    @TableField(exist = false)
    private Integer activeSlot;
}
