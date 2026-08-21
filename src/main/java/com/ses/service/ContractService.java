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
     * 業務ルール付き保存（採番＋検証＋要員連動＋労務コンプライアンスチェック）
     * @param contract 契約情報
     * @return 労務コンプライアンスリスクfindings（該当なしは空リスト。ブロックはしない）
     */
    java.util.List<com.ses.dto.compliance.ComplianceFinding> saveWithBusinessRules(Contract contract);

    /**
     * 業務ルール付き更新（検証＋要員連動＋労務コンプライアンスチェック）。
     * 画面 DTO 経由でない呼び出し向け。ALWAYS 列は {@link com.ses.dto.contract.ContractSaveDto#SAVE_PAYLOAD_ALWAYS_FIELDS}
     * を「出現済み」とみなす（DTO に無い positionId / renewalDecision は old から回填）。
     * @param contract 契約情報
     * @return 労務コンプライアンスリスクfindings（該当なしは空リスト。ブロックはしない）
     */
    java.util.List<com.ses.dto.compliance.ComplianceFinding> updateWithBusinessRules(Contract contract);

    /**
     * ALWAYS 列の部分更新安全版。JSON に未出現のキーは {@code presentAlwaysFields} に入れず、
     * 行ロック後の旧値で回填する。明示 null（クリア）したい列だけを含めること（CON-01）。
     * @param contract 契約情報
     * @param presentAlwaysFields JSON に出現した ALWAYS フィールド名
     * @return 労務コンプライアンスリスクfindings（該当なしは空リスト。ブロックはしない）
     */
    java.util.List<com.ses.dto.compliance.ComplianceFinding> updateWithBusinessRules(
            Contract contract, java.util.Set<String> presentAlwaysFields);

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

    /**
     * 注文明細から契約ドラフト（準備中）を生成する（order-acceptance-workflow / R2.2）。
     * t_contract.order_line_id のUNIQUEで1明細→1契約を保証する（二重clickでも1件、R5）。
     * @param line   注文明細
     * @param order  注文
     * @return 生成（または既存）の契約
     */
    Contract createDraftFromSalesOrderLine(com.ses.entity.SalesOrderLine line, com.ses.entity.SalesOrder order);

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

    /**
     * 更新判断（継続確定/更新不要）を設定・解除する（FR-06 契約更新カレンダー）。
     * @param contractId 契約ID
     * @param decision "CONTINUE"（継続確定）/"END"（更新不要）/null（未定に戻す）
     */
    void updateRenewalDecision(Long contractId, String decision);
}
