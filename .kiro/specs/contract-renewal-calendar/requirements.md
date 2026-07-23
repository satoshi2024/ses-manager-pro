# Requirements — 契約更新カレンダー＋エスカレーション（FR-06）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-06）。

契約自動更新ドラフト生成（`ContractRenewalService`）はあるが、全契約の終了/更新期限を**俯瞰するビュー**と、未対応を上長へ**エスカレーション**する仕組みが無い。更新漏れ＝失注を物理的に防ぐ。新規テーブル原則不要。

### 確定済みの設計判断
- カレンダーは月/週表示。契約の `endDate` を基点に「更新期限（endDate − N日）」を配置。
- エスカレーション段階は `m_system_config` で設定（例: 30日前=担当営業、14日前=上長）。
- 「対応済み」の定義=更新ドラフト確定 or 明示的な「更新不要/継続確定」フラグ。

## Requirements

### Requirement 1: 更新カレンダー
1. THE システム SHALL 契約の終了日・更新期限をカレンダー（月/週）で俯瞰表示する。
2. THE 表示 SHALL 期間フィルタ付きAPIで取得する（1000件上限で黙って欠けない。A7-22 整合）。
3. THE 各項目 SHALL 契約・要員・客先・終了日・更新状態（未対応/ドラフト有/確定/継続/終了予定）を示す。

### Requirement 2: エスカレーション
1. WHEN 更新期限のN日前（設定段階）に未対応の契約がある場合、THE システム SHALL 担当営業→上長の順で通知する（`NotificationService`/`WebhookNotifier`）。
2. THE 通知 SHALL 重複抑止（dedupe）され、対応済みで停止する。

### Requirement 3: 権限・スコープ
1. THE カレンダー SHALL `DataScopeService` に従い、担当分のみ表示（管理者/マネージャーは全体）。

## Out of Scope
- 自動更新ドラフト生成そのもの（既存 `ContractRenewalService`）。本機能は可視化とエスカレーション。
</content>
