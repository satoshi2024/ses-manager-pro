package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_freee_connection")
public class FreeeConnection extends BaseEntity {
    private Long companyId;
    private String companyName;
    private String accessTokenEncrypted;
    private String refreshTokenEncrypted;
    private LocalDateTime tokenExpiresAt;
    private Long connectedBy;
    /** 接続状態: CONNECTED / REAUTH_REQUIRED。DISCONNECTEDは行なし、MISCONFIGUREDは設定から導出。 */
    private String connectionStatus;
}
