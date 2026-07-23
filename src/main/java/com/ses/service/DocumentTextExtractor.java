package com.ses.service;

/**
 * ファイルからプレーンテキストを抽出するサービスインターフェース。
 */
public interface DocumentTextExtractor {

    /**
     * 保存済みファイルからプレーンテキストを抽出する。
     *
     * @param storedFileName 保存名（UUID.ext）
     * @param ext            拡張子（pdf/docx/xlsx）
     * @return 抽出テキスト。抽出不能の場合は空文字列
     */
    String extract(String storedFileName, String ext);
}
