package com.ses.service;

import java.util.Set;

/**
 * ファイル参照を提供するプロバイダーインターフェース。
 * ファイルを保持する各機能がこのインターフェースを実装し、
 * FileCleanupServiceImpl が全プロバイダーを集約して孤児ファイルを特定する。
 * 新機能がファイルを追加するたびにプロバイダーを1つ実装することで、
 * 清理バッチへの登録漏れによる原本消失を構造的に防ぐ。
 */
public interface FileReferenceProvider {

    /**
     * 現在DBに参照されているファイルの保存名（storedName）の集合を返す。
     * null・空文字は含まないこと。
     */
    Set<String> referencedFileNames();
}
