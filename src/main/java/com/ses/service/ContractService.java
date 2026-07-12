package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Contract;

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
     * 稼動中の契約があるか確認
     * @param engineerId エンジニアID
     * @return 稼働中の契約があればtrue
     */
    boolean hasActiveContract(Long engineerId);
}
