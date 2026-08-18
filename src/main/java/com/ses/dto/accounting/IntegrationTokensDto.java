package com.ses.dto.accounting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部接続トークンDTO。
 * encrypted_tokens に JSON シリアライズされて格納される。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationTokensDto implements Serializable {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private String scope;
    private Long expiresIn;
}
