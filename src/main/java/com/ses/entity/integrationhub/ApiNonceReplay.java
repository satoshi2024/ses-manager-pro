package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** NF-05 nonce replay prevention ledger。raw nonce/署名/body/secretは保存しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_nonce_replay")
public class ApiNonceReplay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private Integer credentialVersion;
    private String nonceHash;
    private LocalDateTime acceptedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
