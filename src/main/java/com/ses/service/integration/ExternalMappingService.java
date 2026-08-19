package com.ses.service.integration;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.ExternalMapping;

import java.util.List;

public interface ExternalMappingService extends IService<ExternalMapping> {

    /**
     * マッピングを取得する。
     */
    ExternalMapping getMapping(Long connectionId, String objectType, String internalCode);

    /**
     * マッピングを保存または更新する。
     */
    void saveOrUpdateMapping(ExternalMapping mapping);

    /**
     * 外部マスタとの突合スナップショットを保存し、検証済 (verified_at = now) に更新する。
     */
    void verifyMapping(Long mappingId, String payloadSnapshot);

    /**
     * 外部プロバイダ API を直接照会してマスタ存在を検証し、検証済みにする (P1-05)。
     */
    boolean verifyAndSnapshotMapping(Long mappingId);

    /** mappingIdと接続のtenantをjoinしたSQL境界で検証対象を取得する (R4-R3)。 */
    boolean verifyAndSnapshotMappingScoped(Long mappingId, String tenantId);

    /**
     * 必要なマッピングがすべて登録・検証済みであることを確認する (R1.3)。
     * 欠落または verified_at IS NULL があれば BusinessException をスローする。
     */
    void assertMappingVerified(Long connectionId, String objectType, String internalCode);

    /**
     * 接続ID別のマッピング一覧を取得する。
     */
    List<ExternalMapping> listByConnection(Long connectionId, String objectType);

    /**
     * 許可接続集合に限定したマッピング一覧を取得する (R1-P1-06 / design §5.2)。
     * allowedConnectionIds が空の場合は 0 件を返す (SQL境界)。
     */
    List<ExternalMapping> listByConnectionsScoped(Long connectionId, String objectType, java.util.Set<Long> allowedConnectionIds);
}
