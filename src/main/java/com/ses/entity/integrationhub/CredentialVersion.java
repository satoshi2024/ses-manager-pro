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

/** NF-05 HMAC service-account credential世代。平文secretは保持しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_credential_version")
public class CredentialVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long apiClientId;
    private Integer credentialVersion;
    private String keyId;
    private String encryptedSecret;
    private String secretHash;
    private String cryptoKeyVersion;
    private String cipherFormat;
    private String status;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime overlapUntil;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
