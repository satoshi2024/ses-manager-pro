package com.ses.service.servicedesk;

import com.ses.dto.servicedesk.CustomerHealthScoreDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顧客ヘルススコア算定・スナップショット管理サービス
 */
public interface CustomerHealthService {

    /**
     * 全顧客（または検索条件一致顧客）の最新ヘルススコア一覧取得
     */
    List<CustomerHealthScoreDto> listCustomerHealthSummaries(String healthStatus, String keyword);

    /**
     * 単一顧客のヘルススコア算定詳細取得
     */
    CustomerHealthScoreDto calculateCustomerHealth(Long customerId);

    /**
     * 複数顧客のヘルススコア一括算定
     */
    Map<Long, CustomerHealthScoreDto> getHealthMapForCustomers(Set<Long> customerIds);

    /**
     * 月次スナップショット生成実行（全顧客対象）
     * 互換入口。認証済み管理者のHTTP実行に限定する。
     */
    void generateMonthlySnapshot(String targetMonth);

    /**
     * 月次スナップショット生成実行（理由指定・全顧客対象）
     * 互換入口。認証済み管理者のHTTP実行に限定する。
     */
    void generateMonthlySnapshot(String targetMonth, String reason);

    /** 実行主体を明示したスナップショット生成経路。 */
    void generateMonthlySnapshot(String targetMonth, String reason, SnapshotExecutionContext executionContext);
}
