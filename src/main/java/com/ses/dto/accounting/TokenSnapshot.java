package com.ses.dto.accounting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部連携接続の復号済みトークンと世代番号 (token_version) の原子スナップショット (R1-P1-03)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenSnapshot {
    private String accessToken;
    private String refreshToken;
    private Integer tokenVersion;
    private Long connectionId;
}
