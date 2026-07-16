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

    /** 契約状態を許可された遷移だけ変更する。 */
    void changeStatus(Long contractId, String newStatus);

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
}
