package com.ses.dto.accounting;

import com.ses.service.accounting.AccountingJobLegalEntityResolver.LegalEntityCheck;
import com.ses.service.integration.ExternalMappingService.MappingCheck;

import java.util.List;

/**
 * freee送信プレビュー。
 *
 * <p>{@code payload} は送信ジョブへ保存されるのと同じ標準DTO（照合済みマッピングの外部IDを反映済み）。
 * {@code mappingChecks} は送信ガードが要求するマッピングの状態で、1件でもOK以外なら送信できない。
 * {@code legalEntityStatus} はジョブへ刻印する法人の判定（OK 以外の MISSING / MISMATCH は送信不可）。
 * 請求書ステータスや月次締めなど、それ以外の可否は送信時のエラーで返す。</p>
 *
 * @param kind              SALES / PURCHASE / EXPENSE
 * @param payload           送信予定の標準DTO
 * @param mappingChecks     必要マッピングの登録・照合状態
 * @param connectionReady   freee会計接続が CONNECTED か
 * @param legalEntityId     送信対象レコードの法人ID（未設定なら null）
 * @param legalEntityStatus OK / MISSING（レコードの法人未設定）/ MISMATCH（接続の法人と不一致）
 * @param sendable          接続済み・必要マッピングがすべて照合済み・法人OK のすべてを満たすか
 */
public record AccountingSendPreviewDto<T>(String kind,
                                          T payload,
                                          List<MappingCheck> mappingChecks,
                                          boolean connectionReady,
                                          Long legalEntityId,
                                          String legalEntityStatus,
                                          boolean sendable) {

    public static <T> AccountingSendPreviewDto<T> of(String kind, T payload, List<MappingCheck> checks,
                                                     boolean connectionReady, LegalEntityCheck legalEntity) {
        boolean allOk = checks.stream().allMatch(MappingCheck::ok);
        return new AccountingSendPreviewDto<>(kind, payload, checks, connectionReady,
                legalEntity.legalEntityId(), legalEntity.status(),
                connectionReady && allOk && legalEntity.ok());
    }
}
