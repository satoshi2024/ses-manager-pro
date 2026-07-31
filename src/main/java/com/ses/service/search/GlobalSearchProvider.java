package com.ses.service.search;

import com.ses.dto.search.GlobalSearchResultDTO;

import java.util.List;

/**
 * エンティティ種別ごとの横断検索プロバイダ
 */
public interface GlobalSearchProvider {

    /**
     * 対応するエンティティ種別キー（例: ENGINEER, CUSTOMER, PROJECT, CONTRACT, INVOICE）
     */
    String getType();

    /**
     * 権限スコープ内でクエリにマッチする検索結果を取得
     */
    List<GlobalSearchResultDTO> search(String query, int maxResults);
}
