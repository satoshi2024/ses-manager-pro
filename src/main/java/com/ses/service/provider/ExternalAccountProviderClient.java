package com.ses.service.provider;

import com.ses.entity.ExternalAccountReference;

import java.time.Duration;

/**
 * 外部アカウントプロバイダ連携クライアント
 * 失効要求受付と実状態の確証を分離する。実プロバイダ実装は外部の権威ある状態を確認できた場合だけ
 * CONFIRMED を返し、timeout・未配線・分類不能応答を成功扱いしない。アカウント無効化だけで既存session、offline
 * session、発行済みtokenの無効化まで推定せず、連携先で要求する失効範囲を個別に確認する。秘密情報は台帳やログへ保存しない。
 */
public interface ExternalAccountProviderClient {

    /**
     * 外部プロバイダへ失効要求を送信する（非同期リクエスト）
     * @return 要求受付成功（true）または失敗（false）
     */
    boolean requestRevoke(ExternalAccountReference accountRef);

    /**
     * 外部プロバイダ側で実際に失効・削除が完了したか確証ステータスを確認する
     * @return CONFIRMED（失効確証取得）, PENDING（外部側処理中/未完了）, FAILED_OR_TIMEOUT（通信失敗/タイムアウト）, UNKNOWN（応答形式を分類不能）
     */
    RevokeConfirmationStatus checkRevokeConfirmation(ExternalAccountReference accountRef);

    /**
     * 一回の確認呼出し（provider内部のretryを含む）の最大所要時間。
     * pollのleaseはこの値を超えるように設定し、長時間呼出し中の二重確認を避ける。
     */
    default Duration revokeConfirmationTimeout() {
        return Duration.ofSeconds(30);
    }

    enum RevokeConfirmationStatus {
        CONFIRMED,
        PENDING,
        FAILED_OR_TIMEOUT,
        UNKNOWN
    }
}
