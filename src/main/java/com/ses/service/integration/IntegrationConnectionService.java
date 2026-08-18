package com.ses.service.integration;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.IntegrationConnection;

import java.util.List;
import java.util.function.Function;

public interface IntegrationConnectionService extends IService<IntegrationConnection> {

    /**
     * テナント/法人/プロバイダ/プロダクトから接続情報を取得する。
     */
    IntegrationConnection getConnection(String tenantId, Long legalEntityId, String provider, String product);

    /**
     * 存在しない場合は新規作成（DISCONNECTED）して取得する。
     */
    IntegrationConnection getOrCreateConnection(String tenantId, Long legalEntityId, String provider, String product);

    /**
     * トークン情報を暗号化して保存する。
     */
    void saveTokens(Long connectionId, IntegrationTokensDto tokens, Long companyId, String companyName, Long connectedBy);

    /**
     * トークン情報を復号して取得する。
     */
    IntegrationTokensDto getDecryptedTokens(Long connectionId);

    /**
     * トークン更新（Refresh）。同一接続への同時 401 発生時、1回のみ実行して新トークンを返す (token race guard)。
     */
    IntegrationTokensDto rotateTokens(Long connectionId, Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn);

    /**
     * トークン強制更新 (401 障害復旧用)。有効期限に関わらず必ず最新トークンを取得・保存する。
     */
    IntegrationTokensDto forceRefreshToken(Long connectionId, Function<IntegrationTokensDto, IntegrationTokensDto> refreshFn);

    /**
     * 接続状態を更新する。
     */
    void updateStatus(Long connectionId, String status);

    /**
     * テナント配下の接続一覧を取得する（秘密情報はマスク）。
     */
    List<IntegrationConnection> listConnections(String tenantId);
}
