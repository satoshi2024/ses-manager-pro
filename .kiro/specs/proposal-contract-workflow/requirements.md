# Requirements Document — 提案・契約ワークフロー(P2)

## Introduction

現状 `ProposalServiceImpl.changeStatus()` は任意のステータス間遷移を許し、`closed_at` / `changed_by` を記録せず、
成約しても契約・要員ステータスと連動しない。`ContractServiceImpl` は空実装で契約番号の採番規則もない。
本フェーズで 提案 → 契約 → 要員ステータス の業務閉ループを作る。

## Requirements

### Requirement 1: 提案ステータス遷移の状態機械化

#### Acceptance Criteria
1. THE システム SHALL 以下の遷移のみ許可する:
   - 書類選考中 → 一次面接 / 見送り
   - 一次面接 → 二次面接 / 結果待ち / 見送り
   - 二次面接 → 結果待ち / 見送り
   - 結果待ち → 成約 / 見送り
   - 成約・見送り(終端)→ 遷移不可
2. IF 許可されない遷移が要求された場合、THEN THE システム SHALL `BusinessException` により日本語エラーメッセージを返し、カンバンの D&D を元の列に戻す。
3. WHEN ステータスが「成約」または「見送り」になった時、THE システム SHALL `t_proposal.closed_at` に現在日時を設定する。
4. THE システム SHALL `t_proposal_history.changed_by` にログイン中ユーザーの ID を記録する。

### Requirement 2: 成約時の契約作成連携

#### Acceptance Criteria
1. WHEN 提案が「成約」に変更された時、THE カンバン画面 SHALL 契約作成モーダルを開き、要員・案件・顧客・売上単価(=提案単価)・提案ID を事前入力する。
2. THE システム SHALL 契約保存時に `proposal_id` を紐付ける。
3. IF 同一要員に「稼動中」の契約が既に存在する状態で成約しようとした場合、THEN THE システム SHALL 警告メッセージを返す(確認の上続行は可能)。

### Requirement 3: 契約番号の自動採番

#### Acceptance Criteria
1. WHEN 契約番号が未入力で契約を新規登録した時、THE システム SHALL `C-YYYYMM-NNNN`(月内連番4桁)形式で自動採番する。
2. IF 採番が UNIQUE 制約に衝突した場合、THEN THE システム SHALL 連番をインクリメントして最大3回まで再試行する。

### Requirement 4: 要員ステータスの自動連動

#### Acceptance Criteria
1. WHEN 提案を新規作成した時、IF 要員が「Bench」なら THE システム SHALL 「提案中」へ更新する。
2. WHEN 契約が「稼動中」で保存された時、THE システム SHALL 要員を「稼動中」へ更新する。
3. WHEN 契約が「終了」または「解約」になった時、IF その要員に他の稼動中契約が無ければ THE システム SHALL 要員を「Bench」へ更新する。
4. WHEN 提案が「見送り」になった時、IF その要員に他のオープンな提案・稼動中契約が無ければ THE システム SHALL 要員を「Bench」へ戻す。

### Requirement 5: 契約の入力妥当性検証

#### Acceptance Criteria
1. IF `end_date < start_date`、または `selling_price < cost_price`(粗利マイナス)で警告なし保存が要求された場合、THEN THE システム SHALL エラー/警告を返す(日付逆転=エラー、粗利マイナス=警告)。
2. IF `settlement_hours_max < settlement_hours_min` の場合、THEN THE システム SHALL エラーを返す。
