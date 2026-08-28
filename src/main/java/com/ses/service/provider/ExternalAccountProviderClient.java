package com.ses.service.provider;

import com.ses.entity.ExternalAccountReference;

/**
 * 外部アカウントプロバイダ連携クライアント
 * ※失効要求と確証確認を分離し、timeoutを成功扱いとしない。
 */
public interface ExternalAccountProviderClient {

    /**
     * 外部プロバイダへ失効要求を送信する（非同期リクエスト）
     * @return 要求受付成功（true）または失敗（false）
     */
    boolean requestRevoke(ExternalAccountReference accountRef);

    /**
     * 外部プロバイダ側で実際に失効・削除が完了したか確証ステータスを確認する
     * @return CONFIRMED（失効確証取得）, PENDING（外部側処理中/未完了）, FAILED_OR_TIMEOUT（通信失敗/タイムアウト）
     */
    RevokeConfirmationStatus checkRevokeConfirmation(ExternalAccountReference accountRef);

    enum RevokeConfirmationStatus {
        CONFIRMED,
        PENDING,
        FAILED_OR_TIMEOUT
    }
}
