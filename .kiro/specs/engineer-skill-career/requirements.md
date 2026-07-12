# Requirements Document — 要員スキル・経歴・案件要求スキル(P1)

## Introduction

DDL(`sql/001_create_tables.sql`)には `t_engineer_skill` / `t_engineer_career` / `t_project_skill` が定義済みだが、
Java コードからは一切参照されていない。要員詳細画面のスキルバッジは `engineer-detail.js` にハードコードされた
ダミー(Java / Spring Boot)である。本フェーズでこれらを実装し、スキル起点の要員検索まで実現する。

## Requirements

### Requirement 1: 要員スキルの登録・表示

**User Story:** 営業/HR として、要員にスキルタグ(習熟度・経験年数付き)を登録し、詳細画面で確認したい。

#### Acceptance Criteria
1. WHEN 要員詳細画面を開いた時、THE システム SHALL `t_engineer_skill` の実データをスキル名・カテゴリ・習熟度・経験年数付きで表示する(ハードコードバッジの廃止)。
2. THE システム SHALL 要員編集時にスキルタグ(`m_skill_tag` から選択)・習熟度(初級/中級/上級)・経験年数を複数件まとめて保存できる(全置換方式)。
3. IF 同一スキルを重複指定した場合、THEN THE システム SHALL 重複を除外して保存する(DB の `uk_engineer_skill` 違反を起こさない)。
4. スキルが未登録の場合は「登録なし」を表示する。

### Requirement 2: 要員経歴の管理

**User Story:** HR として、要員の職務経歴(期間・案件名・役割・使用技術など)を登録し、詳細画面で時系列に確認したい。

#### Acceptance Criteria
1. THE システム SHALL 要員詳細画面に経歴一覧(period_from 降順)を表示する。
2. THE システム SHALL 経歴の追加・編集・削除(モーダル)を提供する。削除は SweetAlert2 確認後に実行する。
3. IF period_to < period_from の場合、THEN THE システム SHALL エラー(ApiResult code≠200)を返し保存しない。

### Requirement 3: 案件要求スキルの管理

**User Story:** 営業として、案件に必須/尚可の要求スキルを登録したい(マッチングの前提データ)。

#### Acceptance Criteria
1. THE システム SHALL 案件編集モーダル内で要求スキル(スキルタグ・要求レベル・必須/尚可)を複数件保存できる(全置換方式)。
2. THE システム SHALL 案件一覧の行または詳細で要求スキルをバッジ表示する(必須は強調表示)。

### Requirement 4: スキル条件による要員検索

**User Story:** 営業として、スキルタグの組み合わせで要員を絞り込みたい。

#### Acceptance Criteria
1. THE 要員一覧 API SHALL `skillIds`(複数)パラメータを受け付け、指定した**全て**のスキルを持つ要員に絞り込む(AND 条件)。
2. THE 要員一覧画面 SHALL スキル選択 UI(カテゴリ別)を検索フォームに備える。
3. 既存の氏名・ステータス・雇用形態フィルタとの併用が可能であること。
