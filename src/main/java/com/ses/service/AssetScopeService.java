package com.ses.service;

import java.util.List;

/**
 * 資産・アカウント認可スコープ解決サービス
 */
public interface AssetScopeService {

    /**
     * 現在ログインユーザーが全アクセス権を持つか判定（管理者）
     */
    boolean hasFullAccess();

    /**
     * 現在ログインユーザーが閲覧可能な要員IDリストを取得
     * null の場合は全件（制限なし）、空リストの場合は該当0件（アクセス不可）
     */
    List<Long> getAccessibleEngineerIds();

    /** 明示アクターに対する認可済み要員ID。null=全件、空=0件。 */
    List<Long> getAccessibleEngineerIds(String role, Long actorUserId);

    /** 明示アクターに対する資産認可母集団。null=全件、空=0件。 */
    List<Long> getAccessibleAssetIds(String role, Long actorUserId);

    /** 明示アクターに対するライセンスプラン認可母集団。null=全件、空=0件。 */
    List<Long> getAccessibleLicensePlanIds(String role, Long actorUserId);

    /** 外部アカウント/ライセンスassignmentの対象者認可。 */
    boolean isAccessibleAssignee(String assigneeType, Long assigneeId, String role, Long actorUserId);

    /** Document API一覧の資産証跡文書母集団。 */
    List<Long> getAccessibleAssetDocumentIds(String role, Long actorUserId);

    /**
     * 特定の要員IDに対するアクセス権を検証
     */
    void assertAccessibleEngineer(Long engineerId);

    /**
     * 特定のユーザーID（内部ユーザー）に対するアクセス権を検証
     */
    void assertAccessibleUser(Long userId);

    /**
     * 特定の資産に対するアクセス権限を判定
     */
    boolean isAccessible(Long assetId, String role, Long actorUserId);

    /**
     * DocumentLink 経由で文書ID → 業務エンティティ (ASSET_ASSIGNMENT → Asset) を辿り、
     * 指定ユーザーがその文書へアクセス可能か判定する。
     * 文書の認可母集団はリンク先業務エンティティのスコープから導出する（design §6.2）。
     */
    boolean isAccessibleByDocumentLink(Long documentId, String role, Long actorUserId);
}
