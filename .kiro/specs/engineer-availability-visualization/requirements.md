# Requirements Document — 稼働率カレンダー/ガントチャート可視化

## Introduction

現状の`DashboardServiceImpl`はKPI集計（稼働率・待機人数・売上/粗利の月次推移）のみを扱い、`AnalyticsServiceImpl.benchList()`は「現在Benchのエンジニア一覧」という単純リストに留まる。営業担当者が実務で最も欲しいのは「誰が・いつから・いつまで空くのか」を横断的に見渡せるタイムライン/ガント風ビューであり、これにより契約終了前の先回り営業が可能になる。既存のChart.jsはbar/doughnutのみ使用しており、タイムライン系の可視化は未導入。

### 確定済みの設計判断
- 既存の`Engineer.status`/`availableDate`、`Contract.startDate`/`endDate`を読み取るだけの新規GETエンドポイントのみを追加し、既存の`DashboardServiceImpl`/`AnalyticsServiceImpl`の既存メソッドには一切変更を加えない。
- 新規JSライブラリ(CDN)の追加は最小限とし、まずは既存Chart.jsの`horizontalBar`相当（Chart.js v3以降は`indexAxis: 'y'`のbarチャート）でガント風表現を試みる。表現力が不足する場合のみ、軽量な追加ライブラリ導入を検討する（本specのdesign.mdで判断根拠を明記）。

## Requirements

### Requirement 1: エンジニア稼働タイムラインの表示

#### Acceptance Criteria
1. THE システム SHALL 新規画面でエンジニア一覧を縦軸、月（直近1ヶ月〜先6ヶ月）を横軸としたタイムラインを表示する。
2. THE タイムライン SHALL 各エンジニアの現在の契約期間（`Contract.startDate`〜`endDate`、`status='稼動中'`のもの）を帯として表示する。
3. WHERE エンジニアが契約期間外（Bench中、または`endDate`より先が未定）の場合、THE タイムライン SHALL 当該区間を「空き」として視覚的に区別する（色分け）。
4. THE 画面 SHALL 部署/スキル/現在の担当営業でのフィルタリングを提供する（既存の一覧画面のフィルタUIパターンを踏襲）。

### Requirement 2: 空き予定の早期発見

#### Acceptance Criteria
1. THE システム SHALL `Contract.endDate`が今後30日以内で、後続契約（`renewedFromContractId`で連鎖する次契約）が存在しないエンジニアを「まもなく空き」として強調表示する。
2. WHEN ユーザーがタイムライン上の帯をクリックした場合、THE システム SHALL 当該エンジニアの詳細画面へ遷移するリンクを提供する。

## 踩坑点（実装時の注意）
- 「まもなく空き」の判定は既存の`notification-center`spec の`CONTRACT_END`通知ロジック（30日以内終了の判定条件）と**判定基準を完全に一致させる**こと。別々のロジックで微妙に異なる閾値・除外条件（自動更新フラグの扱い等）を実装すると、通知とダッシュボード表示で矛盾する情報が出て現場が混乱する典型的な失敗パターンになる。可能であれば`NotificationGenerateService`の判定ロジックをprivateメソッド抽出して両者で共有することを検討する。
- タイムラインの月表示範囲(先6ヶ月)は`Contract.endDate`がNULL(期間未定の請負契約等)のケースをどう描画するか事前に決めておくこと（例: 「期間未定」として右端を点線表示等）、単純に無視すると継続案件が空白として誤表示される。
- エンジニア数が数百人規模になった場合、DOM上に全員分の帯を描画すると画面が重くなるため、初期表示は「今後30日以内に空くエンジニア」等でデフォルト絞り込みを効かせる設計にする。
