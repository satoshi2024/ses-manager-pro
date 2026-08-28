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
}
