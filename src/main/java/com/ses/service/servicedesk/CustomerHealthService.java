package com.ses.service.servicedesk;

import com.ses.dto.servicedesk.CustomerHealthScoreDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顧客ヘルススコア算定サービス
 */
public interface CustomerHealthService {

    /**
     * 指定顧客の最新ヘルススコアおよびブレークダウンを計算して返却する。
     */
    CustomerHealthScoreDto calculateCustomerHealth(Long customerId);

    /**
     * 複数顧客のヘルススコアを一括算出してマップで返却する（カレンダー等で使用）。
     */
    Map<Long, CustomerHealthScoreDto> getHealthMapForCustomers(Set<Long> customerIds);

    /**
     * 顧客ヘルススコア一覧（フィルタ・検索対応）
     */
    List<CustomerHealthScoreDto> listCustomerHealthSummaries(String healthStatus, String keyword);

    /**
     * 月次スナップショットを生成して保存する。
     */
    void generateMonthlySnapshot(String snapshotMonth);
}
