# Requirements Document — 要員担当営業・営業成績/インセンティブ管理

## Introduction

現状、要員(`t_engineer`)と営業ユーザー(`sys_user.role='営業'`)の間に業務的な関連が存在しない
(要員側は登録者 `created_by` のみ、契約にも営業担当カラムなし)。そのため:

- Bench(待機)要員のフォロー責任者が不明確
- 成約実績を営業個人に帰属できない
- インセンティブ(営業成果報酬)が完全に手計算

本機能は (1) 要員↔担当営業の関連付け、(2) 営業別成績集計、(3) インセンティブ自動計算を追加する。

**確定済みの設計判断**(発注者確認済み):

- 関連は専用テーブル(複数営業のフォロー・主担当フラグ・担当変更履歴に対応)
- 分配は手動(自動割当・通知は本フェーズ対象外)
- 成績・インセンティブの帰属は契約単位: `t_contract.sales_user_id`(成約時に要員の主担当を既定値として記録、変更可)。担当変更が過去実績に影響しない
- インセンティブ規則は全社既定(基準=粗利/売上 + 率%、管理者が設定変更可) + 契約単位の個別上書き

## Requirements

### Requirement 1: 要員↔担当営業の関連付け

#### Acceptance Criteria
1. THE システム SHALL 要員に対して複数の担当営業を関連付けできる(`t_engineer_sales`)。
2. THE 関連 SHALL 主担当フラグ(要員あたり現任主担当は常に1名)、担当開始日、担当解除日、備考を持つ。
3. THE 割当先ユーザー SHALL `role='営業'` かつ有効(status=1)でなければならない(違反時 `BusinessException`)。
4. WHEN 要員への最初の割当が行われた場合、THE システム SHALL 自動的に主担当とする。
5. WHEN 主担当を新たに指定した場合、THE システム SHALL 同一トランザクションで既存主担当を副担当へ降格する。
6. THE 解除 SHALL 物理削除・論理削除ではなく `released_at` に日付を設定する(履歴保全)。他の現任担当が残る状態での主担当の直接解除は拒否する。
7. THE システム SHALL 同一営業の重複現任割当を拒否する。

### Requirement 2: 契約への成約担当営業の帰属

#### Acceptance Criteria
1. THE `t_contract` SHALL `sales_user_id`(成約担当営業)を持つ。
2. WHEN 提案が「成約」になり契約ドラフトが生成される時(`ContractServiceImpl.createDraftFromProposal`)、THE システム SHALL 当該要員のその時点の主担当営業を `sales_user_id` の既定値として設定する(主担当なしの場合 NULL)。
3. THE 契約画面 SHALL 担当営業を手動で設定・変更できる。
4. WHEN 契約更新(`ContractRenewalService`)でドラフトが生成される時、THE システム SHALL 元契約の `sales_user_id`・インセンティブ上書き設定を引き継ぐ。
5. THE 契約一覧 SHALL 担当営業名を表示し、担当営業で絞り込みできる。

### Requirement 3: 営業成績集計

#### Acceptance Criteria
1. THE システム SHALL 営業成績画面(`/sales-performance`)で月を指定し、営業ユーザーごとに以下を表示する:
   - **担当要員数**: 現任主担当としての要員数(as-of-now。月次遡及なし、画面に注記)
   - **成約件数(月)**: `t_contract.sales_user_id`=当該ユーザー かつ `created_at` が対象月内の契約数。契約更新由来(`renewed_from_contract_id IS NOT NULL`)は除外
   - **成約率(月)**: `t_proposal.proposed_by`=当該ユーザー かつ `closed_at` が対象月内の提案について 成約÷(成約+見送り)。分母0は「—」表示
   - **稼動中契約数 / 売上合計(月) / 粗利合計(月)**: 対象月に稼動していた契約(status≠準備中、期間が月と重なる)につき、確定済 `t_work_record` があればその金額、なければ契約単価で算出(契約単位のフォールバック)
   - **インセンティブ(月)**: Requirement 4 の計算結果合計
2. THE 集計対象ユーザー SHALL `role='営業'` の全ユーザー ∪ 契約に `sales_user_id` として出現するユーザー(退職・役割変更後も過去実績が消えない)。
3. 集計はリアルタイム計算とし、台帳テーブルへの保存は行わない(v1)。
4. **口径注記**: 成約件数(契約口径)と成約率(提案口径)は帰属基準が異なる。また契約単位フォールバックはダッシュボードの月一括フォールバックと意図的に異なるため、両画面の合計は円単位で一致しない場合がある。

### Requirement 4: インセンティブ計算

#### Acceptance Criteria
1. THE 全社既定規則 SHALL `m_system_config` の `commission.base-type`(粗利/売上)と `commission.rate`(%)で管理し、管理者が既存のシステム設定画面(`/system-config`)から変更できる。
2. THE 契約 SHALL `commission_base_type`・`commission_rate` により規則を個別に上書きできる(NULL=既定適用)。
3. THE 月次インセンティブ SHALL 契約ごとに `max(0, floor(基準額 × 率 ÷ 100))` で算出する(円未満切捨て、粗利がマイナスの月は 0 円)。基準額は基準=売上なら当月売上、粗利なら当月粗利。
4. THE 営業成績画面 SHALL 現在の既定規則と「個別設定契約は上書き適用」の注記を表示する。

### Requirement 5: 画面要件

#### Acceptance Criteria
1. THE 要員詳細画面 SHALL 「担当営業」カードを持ち、現任一覧(氏名/主担当バッジ/開始日)、追加モーダル、主担当変更、解除(SweetAlert2 確認)、履歴表示を提供する。
2. THE 要員一覧 SHALL 主担当営業列と担当営業絞り込みを持つ。
3. THE 分析画面の待機(Bench)一覧 SHALL 主担当営業列(未設定は「—」)と担当営業での絞り込み(クライアントサイド)を持つ。
4. THE 営業成績画面 SHALL 新メニュー `sales-performance`(`m_menu` + `t_role_menu` seed)として追加し、管理者/営業/マネージャーに許可する(HR は非表示かつ直接アクセス 403)。
5. 要員担当 API は既存 `engineer` メニューの `api_prefix`(`/api/engineers`)配下に置き、権限追加なしで動作する。
6. すべての新規ラベル SHALL i18n 4ファイル(`messages.properties` / `_en` / `_ko` / `_zh_CN`)に登録する。
