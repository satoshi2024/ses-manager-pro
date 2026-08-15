package com.ses.service.staffing;

import com.ses.entity.Contract;

/**
 * 契約とactual allocation（t_allocation_plan.source_contract_id行）の同期。
 *
 * <p>契約成立（準備中/稼動中）でactual行をupsertし、契約終了/解約/ポジション解除で破棄する。
 * actual行は契約から導出される実績であり、計画（plan）と二重計上されない
 * （集計側はsource_contract_idの有無をSQLのWHERE句で排他する。design §5.4）。
 */
public interface StaffingContractSyncService {

    /**
     * 契約のposition_link・状態変化をactual allocationへ同期する。
     * 契約の変更経路（saveWithBusinessRules / updateWithBusinessRules / changeStatus /
     * createDraftFromProposal 等）から呼ばれる。契約は最新状態を再読込して判定する。
     */
    void syncActual(Long contractId);

    /** 契約削除時にactual allocationを破棄する。 */
    void removeActual(Long contractId);

    /** 契約がactualとして計上される状態か（準備中/稼動中）。 */
    boolean isActiveContract(Contract contract);
}
