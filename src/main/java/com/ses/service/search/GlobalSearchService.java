package com.ses.service.search;

import com.ses.dto.search.GlobalSearchResultDTO;

import java.util.List;
import java.util.Map;

public interface GlobalSearchService {

    /**
     * 横断検索を実行
     *
     * @param query 検索キーワード（2文字以上）
     * @param types 絞り込む種別一覧（nullまたは空の場合は全種別）
     * @return 種別ごとの検索結果マップ
     */
    Map<String, List<GlobalSearchResultDTO>> search(String query, List<String> types);
}
