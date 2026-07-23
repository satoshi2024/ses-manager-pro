# Requirements — 将来稼働率・Bench予測（FR-07）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-07）。

ダッシュボードは当月の稼働率・Bench数はあるが、**将来のロールオフ予測**が無い。契約終了日から1〜3ヶ月先の稼働率／Bench発生を予測し、「9月に5名ロールオフ→今から動け」を先出しする。新規テーブル不要。

### 確定済みの設計判断
- 予測ホライズンは既定3ヶ月（設定可能）。
- 「将来も稼働」= 対象月に有効な契約が存在（`autoRenew` の見込みを含める/含めないは設定）。
- 口径は既存ダッシュボードの稼働率算定に合わせる（当月値が一致）。

## Requirements

### Requirement 1: 稼働率・Bench予測
1. THE システム SHALL 今後Nヶ月（既定3）について、月次の稼働見込み数・Bench見込み数・稼働率見込みを算出する。
2. THE 判定 SHALL `Contract.endDate`（と `autoRenew` の見込み）を用い、対象月に有効契約が無い要員をBench見込みとする。
3. THE 口径 SHALL 既存 `DashboardServiceImpl` の当月稼働率と一致する。

### Requirement 2: ロールオフ一覧
1. THE システム SHALL 各月に契約が切れてBenchになる見込みの要員一覧（要員・契約・終了日・担当営業）を提示する。
2. THE 一覧 SHALL FR-06（更新カレンダー）と相互リンクする。

### Requirement 3: 画面・権限
1. THE ダッシュボード SHALL 「稼働率予測（3ヶ月）」グラフとロールオフ予定一覧を表示する。
2. THE 機能 SHALL `DataScopeService` に従う。

## Out of Scope
- 提案パイプラインを加味した精緻な稼働予測（本フェーズは契約終了日ベース）。
</content>
