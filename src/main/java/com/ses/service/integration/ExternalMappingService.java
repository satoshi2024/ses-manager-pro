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
     * mappingIdと接続のtenantをjoinしたSQL境界で検証対象を取得し、外部マスタと照合する (R4-R3)。
     * 照合に失敗した場合は verified_at を NULL へ戻して確定させ（以前の「検証済」を残さない）、
     * {@link VerifyOutcome#REJECTED} を返す。例外で返すとロールバックで失敗が記録されないため。
     */
    VerifyOutcome verifyAndSnapshotMappingScoped(Long mappingId, String tenantId);

    /**
     * 必要なマッピングがすべて登録・検証済みであることを確認する (R1.3)。
     * 欠落または verified_at IS NULL があれば BusinessException をスローする。
     */
    void assertMappingVerified(Long connectionId, String objectType, String internalCode);

    /** 送信プレビュー用に、必要マッピング1件の登録・照合状態を返す（例外は投げない）。 */
    MappingCheck inspect(Long connectionId, String objectType, String internalCode);

    /** 外部マスタ照合の結果。 */
    enum VerifyOutcome {
        /** 対象マッピング（または接続）が現tenantに存在しない。 */
        NOT_FOUND,
        /** 照合に成功し検証済みになった。 */
        VERIFIED,
        /** 外部マスタに一致するものが無く、未検証へ戻した。 */
        REJECTED,
        /** 種別が送信経路で扱えないため照合できず、未検証へ戻した。 */
        UNSUPPORTED_TYPE
    }

    /** 必要マッピング1件の状態。status は OK / MISSING / UNVERIFIED。 */
    record MappingCheck(String objectType, String internalCode, String externalId, String status) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

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
