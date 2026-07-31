package com.ses.service.search;

import com.ses.common.exception.BusinessException;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.service.security.AuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Service
public class GlobalSearchServiceImpl implements GlobalSearchService {

    private static final int MAX_RESULTS_PER_TYPE = 10;
    private static final long GLOBAL_SEARCH_TIMEOUT_MS = 3000L; // 3秒

    private final Map<String, GlobalSearchProvider> providerMap = new LinkedHashMap<>();
    private final ObjectProvider<AuthorizationService> authorizationServiceProvider;

    @Autowired
    public GlobalSearchServiceImpl(List<GlobalSearchProvider> providers,
                                  ObjectProvider<AuthorizationService> authorizationServiceProvider) {
        this.authorizationServiceProvider = authorizationServiceProvider;
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

        AuthorizationService authService = authorizationServiceProvider.getIfAvailable();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        long startTime = System.currentTimeMillis();

        for (Map.Entry<String, GlobalSearchProvider> entry : providerMap.entrySet()) {
            String typeKey = entry.getKey();
            if (!targetTypes.isEmpty() && !targetTypes.contains(typeKey)) {
                continue;
            }

            // P1-07: 全体タイムアウトチェック (3秒)
            if (System.currentTimeMillis() - startTime > GLOBAL_SEARCH_TIMEOUT_MS) {
                log.warn("横断検索の全体タイムアウト ({}ms) に達したため残りのプロバイダをスキップします: query={}", GLOBAL_SEARCH_TIMEOUT_MS, cleanedQuery);
                break;
            }

            GlobalSearchProvider provider = entry.getValue();

            // P1-06: メニュー権限・アクション権限の事前判定
            if (authService != null && authentication != null) {
                String actionKey = provider.getRequiredActionKey();
                if (StringUtils.hasText(actionKey) && !authService.isAllowed(authentication, actionKey)) {
                    // 権限がない種別は0件（非開示）
                    resultMap.put(typeKey, List.of());
                    continue;
                }
            }

            try {
                List<GlobalSearchResultDTO> results = provider.search(cleanedQuery, MAX_RESULTS_PER_TYPE);
                if (results == null) {
                    results = List.of();
                }
                resultMap.put(typeKey, results);
            } catch (Exception e) {
                // P1-07: 例外発生時はログ出力し、0件として格納
                log.warn("検索プロバイダでエラーが発生しました: type={}", typeKey, e);
                resultMap.put(typeKey, List.of());
            }
        }

        return resultMap;
    }
}
