# Requirements Document — 稼動分析・帳票出力(P7)

## Introduction

ダッシュボードの稼動率・Bench数は現在時点のスナップショットのみで、時系列推移や
「誰がどれだけ Bench に滞留しているか」が見えない。また各一覧画面に帳票(Excel)出力が無い。
本フェーズで稼動分析画面と Apache POI による Excel 出力を追加する。
稼動履歴は新テーブルを作らず `t_contract` の期間から導出する(契約データが正になる)。

**依存**: P5 完了推奨(実績ベースの月次売上帳票が正確になる)。未完了でも理論値で動作する。

## Requirements

### Requirement 1: 稼動率推移

#### Acceptance Criteria
1. THE システム SHALL 直近12ヶ月の月次稼動率(月末時点で稼動中契約を持つ要員数 ÷ 在籍要員数)を折れ線グラフで表示する。
2. THE グラフ SHALL 月次の稼動要員数・Bench数の内訳も表示する(積み上げ or 第2軸)。

### Requirement 2: Bench 分析

#### Acceptance Criteria
1. THE システム SHALL 現在 Bench の要員一覧を「Bench 経過日数」(最終契約終了日または登録日からの日数)降順で表示する。
2. THE 一覧 SHALL 経過日数 30日超を警告色、60日超を危険色で表示し、希望単価・スキル(P1 導入済みの場合)を併記する。
3. THE 一覧 SHALL 行から要員詳細・マッチング画面(P4 導入済みの場合)へ遷移できる。

### Requirement 3: Excel 帳票出力

#### Acceptance Criteria
1. THE システム SHALL 以下の Excel(.xlsx)出力を提供する:
   - 要員一覧(現在の検索条件を反映)
   - 契約一覧(同上)
   - 月次売上レポート(対象年度の月次売上・粗利、P5 導入済みなら実績/見込み区分付き)
2. THE 出力 SHALL 日本語ヘッダー行・列幅調整・日付/金額の書式設定を持つ。
3. ファイル名は `要員一覧_YYYYMMDD.xlsx` の形式とする。

### Requirement 4: メニュー・権限

#### Acceptance Criteria
1. 稼動分析画面(`/analytics`)を `m_menu` に登録し、既定で管理者・営業・マネージャーに許可する。
2. Excel 出力 API は各一覧のメニュー権限(engineer / contract / dashboard)に従う。
