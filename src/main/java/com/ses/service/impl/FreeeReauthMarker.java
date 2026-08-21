package com.ses.service.impl;

import com.ses.entity.FreeeConnection;
import com.ses.mapper.FreeeConnectionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接続状態をREAUTH_REQUIREDへ更新する独立トランザクション。
 *
 * <p>HFP-01-REV-002: refresh HTTP は DB transaction 外で実行する（S15-P1-01）。
 * invalid_grant 等で REAUTH_REQUIRED が必要なときは、この bean の REQUIRES_NEW
 * トランザクションで独立に永続化する。unit test（reauthMarker 無し）は呼び出し側が
 * mapper 直接更新へフォールバックする。</p>
 */
@Component
public class FreeeReauthMarker {

    private static final String STATUS_REAUTH_REQUIRED = "REAUTH_REQUIRED";

    private final FreeeConnectionMapper connectionMapper;

    public FreeeReauthMarker(FreeeConnectionMapper connectionMapper) {
        this.connectionMapper = connectionMapper;
    }

    /**
     * connection_statusだけをtargeted UPDATEする（REV-008）。
     * エンティティ全体のupdateByIdは、afterCompletion経路で別threadの成功refresh/再接続が
     * 保存したtoken等をstale値で上書きする余地があるため使わない。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequired(FreeeConnection connection) {
        connectionMapper.updateConnectionStatus(connection.getId(), STATUS_REAUTH_REQUIRED);
    }
}
