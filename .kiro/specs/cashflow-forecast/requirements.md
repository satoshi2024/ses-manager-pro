# Requirements — 資金繰り予測（キャッシュフロー）（FR-05）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-05）。

SESは「BP/給与を先に払い、客先入金は後」という構造で資金ショートが起きやすい。既存 `revenue-forecast` は売上予測はあるが、**入金と支払のタイミング差＝キャッシュフロー**は無い。本機能は月次で「入金予定 − 支払予定 = ネット」「累計残高見込み」を可視化し、マイナス月を警告する。新規テーブル不要（既存データを集計）。

### 確定済みの設計判断
- 予測ホライズンは既定6ヶ月（設定可能）。
- 入金予定は請求の `due_date` 月に計上（未入金分）。既入金は除外。
- 支払予定は BP支払＋給与＋固定費（`m_system_config` に月額登録）。
- 期首残高は手入力または設定値（銀行残高連携はfreeeがあれば任意）。

## Requirements

### Requirement 1: キャッシュフロー集計
1. THE システム SHALL 指定期間（既定6ヶ月）について月次の入金予定・支払予定・ネット・累計残高見込みを算出する。
2. THE 入金予定 SHALL `t_invoice` の未入金分を `due_date` の月に、`total`（残額）で計上する。
3. THE 支払予定 SHALL BP支払（`t_bp_payment` の支払予定月）＋給与（payroll/`FreeeEmployeeLink`）＋固定費（`m_system_config`）を月次で計上する。
4. THE 売上/確定実績の判定口径 SHALL 既存 `MonthlyRevenueCalcService` と整合させる（全社KPIと突合可能に）。

### Requirement 2: 警告
1. WHEN 累計残高見込みが警戒閾値（`m_system_config`）を下回る月がある場合、THE システム SHALL その月を強調表示し、管理者へ通知する（`NotificationService`）。

### Requirement 3: 画面
1. THE ダッシュボード SHALL 「資金繰り（CF）」タブで月次の棒（入金/支払）＋折れ線（残高見込み）を表示する（Chart.js）。
2. THE 画面 SHALL 期首残高・期間・固定費を設定でき、CSV出力できる。
3. THE 機能 SHALL 管理者/マネージャー権限に限定する。

## Out of Scope
- 銀行API直結の実残高同期（freee経由の任意連携に留める）。
- 部門別/プロジェクト別の資金配賦。
</content>
