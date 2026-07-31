package com.ses.service.search;

import com.ses.common.exception.BusinessException;
import com.ses.dto.search.GlobalSearchResultDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
public class GlobalSearchServiceImpl implements GlobalSearchService {

    private static final int MAX_RESULTS_PER_TYPE = 10;
    private final Map<String, GlobalSearchProvider> providerMap = new HashMap<>();

    @Autowired
    public GlobalSearchServiceImpl(List<GlobalSearchProvider> providers) {
        if (providers != null) {
            for (GlobalSearchProvider provider : providers) {
                providerMap.put(provider.getType().toUpperCase(), provider);
            }
        }
    }

    @Override
    public Map<String, List<GlobalSearchResultDTO>> search(String query, List<String> types) {
        if (!StringUtils.hasText(query) || query.trim().length() < 2) {
            throw new BusinessException(400, "検索キーワードは2文字以上入力してください");
        }

        String cleanedQuery = query.trim();
        Map<String, List<GlobalSearchResultDTO>> resultMap = new LinkedHashMap<>();

        Set<String> targetTypes = new HashSet<>();
        if (types != null && !types.isEmpty()) {
            for (String t : types) {
                if (StringUtils.hasText(t)) {
                    targetTypes.add(t.trim().toUpperCase());
                }
            }
        }

        // DB コネクションプールを保持・保護するため順次実行で統合
        for (Map.Entry<String, GlobalSearchProvider> entry : providerMap.entrySet()) {
            String typeKey = entry.getKey();
            if (!targetTypes.isEmpty() && !targetTypes.contains(typeKey)) {
                continue;
            }

            GlobalSearchProvider provider = entry.getValue();
            try {
                List<GlobalSearchResultDTO> results = provider.search(cleanedQuery, MAX_RESULTS_PER_TYPE);
                if (results == null) {
                    results = List.of();
                }
                resultMap.put(typeKey, results);
            } catch (Exception e) {
                // 検索プロバイダエラー発生時は安全に空リストを返却（他種別に影響させない）
                resultMap.put(typeKey, List.of());
            }
        }

        return resultMap;
    }
}
