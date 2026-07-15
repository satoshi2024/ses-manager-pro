package com.ses.common.constant;

/**
 * ステータス定数インターフェース
 * システム全体で使用するステータス値を一元管理する
 */
public interface StatusConstants {

    // ========== エンジニアステータス ==========

    /** エンジニアステータス: 稼動中 */
    String ENGINEER_ACTIVE = "稼動中";

    /** エンジニアステータス: 退場予定 */
    String ENGINEER_LEAVING = "退場予定";

    /** エンジニアステータス: 待機中（ベンチ） */
    String ENGINEER_BENCH = "Bench";

    /** エンジニアステータス: 提案中 */
    String ENGINEER_PROPOSING = "提案中";

    // ========== 案件ステータス ==========

    /** 案件ステータス: 募集中 */
    String PROJECT_RECRUITING = "募集中";

    /** 案件ステータス: 選考中 */
    String PROJECT_SELECTING = "選考中";

    /** 案件ステータス: 充足 */
    String PROJECT_FILLED = "充足";

    /** 案件ステータス: クローズ */
    String PROJECT_CLOSED = "クローズ";

    // ========== 提案ステータス ==========

    /** 提案ステータス: 書類選考中 */
    String PROPOSAL_DOCUMENT_SCREENING = "書類選考中";

    /** 提案ステータス: 一次面接 */
    String PROPOSAL_FIRST_INTERVIEW = "一次面接";

    /** 提案ステータス: 二次面接 */
    String PROPOSAL_SECOND_INTERVIEW = "二次面接";

    /** 提案ステータス: 結果待ち */
    String PROPOSAL_WAITING_RESULT = "結果待ち";

    /** 提案ステータス: 成約 */
    String PROPOSAL_CONTRACTED = "成約";

    /** 提案ステータス: 見送り */
    String PROPOSAL_REJECTED = "見送り";

    // ========== 契約ステータス ==========

    /** 契約ステータス: 準備中 */
    String CONTRACT_PREPARING = "準備中";

    /** 契約ステータス: 稼動中 */
    String CONTRACT_ACTIVE = "稼動中";

    /** 契約ステータス: 終了 */
    String CONTRACT_ENDED = "終了";

    /** 契約ステータス: 解約 */
    String CONTRACT_CANCELLED = "解約";

    // ========== ユーザーロール ==========

    /** ユーザーロール: 管理者 */
    String ROLE_ADMIN = "管理者";

    /** ユーザーロール: 営業 */
    String ROLE_SALES = "営業";

    /** ユーザーロール: HR（人事） */
    String ROLE_HR = "HR";

    /** ユーザーロール: マネージャー */
    String ROLE_MANAGER = "マネージャー";

    // ========== インセンティブ計算基準 ==========

    /** インセンティブ計算基準: 粗利 */
    String COMMISSION_BASE_PROFIT = "粗利";

    /** インセンティブ計算基準: 売上 */
    String COMMISSION_BASE_SALES = "売上";

    // ========== 候補者採用ステージ ==========

    /** 候補者ステージ: 応募受付 */
    String CANDIDATE_STAGE_APPLIED = "応募受付";

    /** 候補者ステージ: 書類選考 */
    String CANDIDATE_STAGE_DOCUMENT_SCREENING = "書類選考";

    /** 候補者ステージ: 一次面談 */
    String CANDIDATE_STAGE_FIRST_INTERVIEW = "一次面談";

    /** 候補者ステージ: 最終面談 */
    String CANDIDATE_STAGE_FINAL_INTERVIEW = "最終面談";

    /** 候補者ステージ: 内定 */
    String CANDIDATE_STAGE_OFFER = "内定";

    /** 候補者ステージ: 内定辞退 */
    String CANDIDATE_STAGE_OFFER_DECLINED = "内定辞退";

    /** 候補者ステージ: 入社 */
    String CANDIDATE_STAGE_HIRED = "入社";

    /** 候補者ステージ: 不採用 */
    String CANDIDATE_STAGE_REJECTED = "不採用";
}
