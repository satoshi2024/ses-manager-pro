package com.ses.service.impl;

import com.ses.entity.FreeeConnection;
import com.ses.mapper.FreeeConnectionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接続状態をREAUTH_REQUIREDへ更新する独立トランザクション。
 *
 * <p>HFP-01-REV-002: {@code refreshInternal}（REQUIRES_NEW）内で直接状態を更新すると、
 * 続けて投げるBusinessExceptionによるrollbackで更新が消えるため、
 * 外側トランザクションの完了後（afterCompletion）に、このbeanのREQUIRES_NEWトランザクションで
 * 独立に永続化する。同じ行を二重にロックしないよう、呼び出しは必ず外側tx終了後に行うこと。</p>
 */
@Component
public class FreeeReauthMarker {

    private static final String STATUS_REAUTH_REQUIRED = "REAUTH_REQUIRED";

    private final FreeeConnectionMapper connectionMapper;

    public FreeeReauthMarker(FreeeConnectionMapper connectionMapper) {
        this.connectionMapper = connectionMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequired(FreeeConnection connection) {
        connection.setConnectionStatus(STATUS_REAUTH_REQUIRED);
        connectionMapper.updateById(connection);
    }
}
