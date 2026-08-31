package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.audit.ActorAttribution;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;

import java.util.List;

/**
 * 外部アカウント参照管理サービス
 * ※秘密非保存: password, token, key は一切扱わない。
 */
public interface ExternalAccountService {

    /**
     * 外部アカウント参照を新規登録する
     */
    ExternalAccountReference registerAccountReference(Long systemId,
                                                      String accountIdentifier,
                                                      String assigneeType,
                                                      Long assigneeId,
                                                      String permissionLevel,
                                                      Long actorUserId);

    /**
     * 外部アカウント参照を更新する
     */
    ExternalAccountReference updateAccountReference(Long id,
                                                    String accountIdentifier,
                                                    String permissionLevel,
                                                    Long actorUserId);

    /**
     * 外部アカウントの失効完了を確認・記録する（CAS保護、手動または指定実行主体）
     *
     * @param id 対象外部アカウント参照ID
     * @param actorUserId 操作者ユーザーID（手動確認では必須）
     * @param source 互換用の確認チャネル文字列。新規コードは専用メソッドを使用する。
     * @return 更新後の外部アカウント参照
     */
    ExternalAccountReference confirmRevoke(Long id, Long actorUserId, String source);

    /** 実際のログインユーザーによる手動確認。ユーザー解決に失敗した場合は拒否する。 */
    ExternalAccountReference confirmRevokeManually(Long id, Long actorUserId,
                                                    String correlationId, String idempotencyKey);

    /** provider同期レスポンスによる確認。 */
    ExternalAccountReference confirmRevokeFromProviderSync(Long id, String correlationId, String idempotencyKey);

    /** provider callbackによる確認。pollとは別のsourceで記録する。 */
    ExternalAccountReference confirmRevokeFromProviderCallback(Long id, String providerEventId,
                                                               String correlationId);

    /** 明示的な主体値を受け取る内部確認経路。 */
    ExternalAccountReference confirmRevoke(Long id, ActorAttribution attribution);

    /**
     * 外部アカウントの失効完了を確認・記録する（手動確認の互換メソッド）
     *
     * @param id 対象外部アカウント参照ID
     * @param actorUserId 操作者ユーザーID（NULLは拒否）
     * @return 更新後の外部アカウント参照
     */
    default ExternalAccountReference confirmRevoke(Long id, Long actorUserId) {
        return confirmRevokeManually(id, actorUserId, null, null);
    }

    /**
     * システム自動ポーリングによる外部アカウント失効完了を確認・記録する
     *
     * @param id 対象外部アカウント参照ID
     * @return 更新後の外部アカウント参照
     */
    default ExternalAccountReference confirmRevokeBySystem(Long id) {
        return confirmRevokeFromSchedulerPoll(id, null, null);
    }

    /** scheduler poll専用経路。 */
    ExternalAccountReference confirmRevokeFromSchedulerPoll(Long id, String correlationId, String idempotencyKey);

    /**
     * 外部アカウントの失効要求を送信する（冪等性キー付与・タイムアウト時はPENDING_CONFIRMATIONへ）
     */
    ExternalAccountReference requestRevokeWithIdempotency(Long id, String idempotencyKey, Long actorUserId);

    /**
     * 失効確認待ち（PENDING_CONFIRMATION）の定期ポーリング・リトライジョブを実行する
     */
    int processPendingRevokePollJob();

    /**
     * 外部アカウントのステータスを変更する
     */
    ExternalAccountReference changeStatus(Long id, String status, Long actorUserId);

    /**
     * 要員またはユーザーの有効外部アカウント参照一覧を取得する
     */
    List<ExternalAccountReference> getActiveAccountsByAssignee(String assigneeType, Long assigneeId);

    /**
     * 外部システム一覧を取得する
     */
    List<ExternalAccountSystem> getAllSystems();

    /**
     * 外部システムを新規作成または更新する
     */
    ExternalAccountSystem saveSystem(ExternalAccountSystem system);

    /**
     * アカウント参照の検索（ページネーション）
     */
    IPage<ExternalAccountReference> searchAccounts(int page, int size, Long systemId, String assigneeType, Long assigneeId, String status);

    /** 認可済み要員ID集合をSQL条件として適用した一覧検索。null=全件、空=0件。 */
    IPage<ExternalAccountReference> searchAccountsScoped(int page, int size, Long systemId,
                                                         String assigneeType, Long assigneeId, String status,
                                                         List<Long> accessibleEngineerIds);

    /**
     * 外部アカウントを台帳から論理削除する。
     * AS-R1.5(b): 状態にかかわらず既存参照行の論理削除を拒否する（Fail-Closed）。
     *
     * @throws com.ses.common.exception.BusinessException 未失効状態の場合
     */
    void softDeleteAccount(Long id);

    /**
     * 外部アカウント参照を新規登録する（registerAccountReference の別名）
     */
    default ExternalAccountReference createAccountReference(Long systemId,
                                                            String accountIdentifier,
                                                            String assigneeType,
                                                            Long assigneeId,
                                                            String permissionLevel,
                                                            Long actorUserId) {
        return registerAccountReference(systemId, accountIdentifier, assigneeType, assigneeId, permissionLevel, actorUserId);
    }
}
