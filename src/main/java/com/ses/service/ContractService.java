package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Contract;
import com.ses.entity.Proposal;

import java.time.LocalDate;

/**
 * 契約サービスインターフェース
 */
public interface ContractService extends IService<Contract> {

    /**
     * 契約番号採番
     * @param baseDate 基準日
     * @return 契約番号 (C-YYYYMM-NNNN)
     */
    String generateContractNo(LocalDate baseDate);

    /**
     * 業務ルール付き保存（採番＋検証＋要員連動）
     * @param contract 契約情報
     */
    void saveWithBusinessRules(Contract contract);

    /**
     * 業務ルール付き更新（検証＋要員連動）
     * @param contract 契約情報
     */
    void updateWithBusinessRules(Contract contract);

    /**
     * 契約状態を許可された遷移だけ変更する。
     * 解約(解約)遷移のときは cancelDate(解約日=実質終了日)が必須で、end_date を当該日で上書きする。
     * それ以外の遷移では cancelDate は無視される。
     * @param contractId 契約ID
     * @param newStatus 新ステータス
     * @param cancelDate 解約日(解約遷移時のみ必須)
     */
    void changeStatus(Long contractId, String newStatus, LocalDate cancelDate);

    /**
     * 稼動中の契約があるか確認
     * @param engineerId エンジニアID
     * @return 稼働中の契約があればtrue
     */
    boolean hasActiveContract(Long engineerId);

    /**
     * 成約した提案から契約ドラフト（準備中）を生成する。
     * 既に同一提案から生成済みの契約があれば何もせず既存契約を返す（冪等）。
     * @param proposal 成約した提案
     * @return 生成（または既存）の契約
     */
    Contract createDraftFromProposal(Proposal proposal);

    /**
     * 受注した見積から契約ドラフト（準備中）を生成する。
     * 既に同一見積から生成済みの契約があれば既存契約を返す（冪等）。要員未設定は拒否する。
     * @param quotation 受注した見積
     * @return 生成（または既存）の契約
     */
    Contract createDraftFromQuotation(com.ses.entity.Quotation quotation);

    // ===== 契約単価の改定履歴（contract-price-history / P6） =====
    /**
     * 単価改定を登録する。初回改定なら契約開始月・現行単価の初期履歴を自動補完し、
     * 契約の現在単価を「当月時点で有効な履歴」で再計算する。
     * @return 過去遡及かつ確定済み実績がある場合 true（警告）。
     */
    boolean revisePrice(Long contractId, String applyFromMonth, java.math.BigDecimal selling,
                        java.math.BigDecimal cost, String reason);

    /** 契約の単価改定履歴を適用開始月昇順で返す。 */
    java.util.List<com.ses.entity.ContractPriceHistory> priceHistory(Long contractId);

    /** 将来予約（当月より後）の改定のみ削除する。 */
    void deleteFuturePriceRevision(Long contractId, String applyFromMonth);
}
