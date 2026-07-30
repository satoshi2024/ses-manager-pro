package com.ses.service;

import com.ses.dto.document.DocumentSearchQuery;

import java.io.OutputStream;

/**
 * 税務調査用一括エクスポートサービス（T026）。
 */
public interface DocumentExportService {

    /**
     * 検索条件に一致する文書群のファイルと manifest.csv を含む ZIP アーカイブをストリームへ出力する。
     *
     * @param query  検索クエリ
     * @param os     出力ストリーム
     */
    void exportTaxZip(DocumentSearchQuery query, OutputStream os);
}
