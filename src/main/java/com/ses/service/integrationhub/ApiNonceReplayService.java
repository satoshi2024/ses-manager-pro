package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.ApiNonceReplay;

import java.time.LocalDateTime;

/** NF-05 nonce replay service。raw nonceはhash算出後に保存しない。 */
public interface ApiNonceReplayService extends IService<ApiNonceReplay> {
    boolean accept(String clientId, int credentialVersion, byte[] rawNonce,
                   LocalDateTime signedTimestamp, LocalDateTime acceptedAt);

    int purgeExpired(LocalDateTime serverNow, int maxRows);
}
