package com.ses.service;

/**
 * 契約自動更新ドラフト生成サービス。
 * auto_renew=1 の契約が終了間近になった際、後続の契約ドラフトを自動生成する。
 */
public interface ContractRenewalService {

    /**
     * 更新ドラフトを生成する。
     * @return 生成したドラフト件数
     */
    int generateRenewalDrafts();

    /**
     * 1件の契約について更新ドラフトを生成する（個別トランザクション用）。
     * @param original 元契約
     */
    void processSingleRenewal(com.ses.entity.Contract original);
}
