# Design — 契約更新カレンダー＋エスカレーション（FR-06）

親: ロードマップ FR-06。再利用: `Contract`（`endDate`/`autoRenew`/`status`/`renewedFromContractId`/`salesUserId`）・`ContractRenewalService`・`NotificationService`/`WebhookNotifier`・`DataScopeService`。新規テーブル原則不要（対応状態を持つなら軽い列 or 既存で導出）。

## 1. データ・状態導出

- 更新状態は原則**導出**（新テーブルを避ける）:
  - 未対応 = endDate 接近・更新ドラフト無し・継続/終了フラグ無し。
  - ドラフト有 = `renewedFromContractId` が自契約を指す下書き契約が存在。
  - 確定 = そのドラフトが確定。
  - 継続/終了予定 = 明示フラグが要る場合のみ `Contract` に軽い列（例 `renewal_decision` VARCHAR）を追加（V45）。※要確認: 導出で足りるか明示フラグを持つか。
- 期間フィルタAPI `GET /api/contracts/renewal-calendar?from=&to=` → 契約×更新期限（endDate − リード日数）×状態。`DataScopeService` 尊重、上限で欠けない設計（A7-22）。

## 2. エスカレーション

- 段階設定 `m_system_config`（`renewal.escalation-days` 例 "30:営業,14:上長"）。
- スケジューラ（日次）で「更新期限 − 段階日数 ≤ 今日 かつ 未対応」の契約を抽出し、対象ロールへ `NotificationService` 通知（dedupe キー=契約×段階×月）。上長解決は `salesUserId`→上位（役割/組織）で。組織階層が無ければ管理者/マネージャーへ。

## 3. 画面

- `templates/contract/renewal-calendar.html`（または契約画面のカレンダータブ）＋ `static/js/modules/contract-renewal-calendar.js`。月/週表示、状態で色分け、クリックで契約詳細/更新ドラフト作成へ。
- `sidebar` 既存 `contract` メニュー配下に導線（新メニュー不要）。

## 4. テスト
- `RenewalCalendarServiceTest`: 状態導出（未対応/ドラフト有/確定/終了予定）、期間フィルタ、スコープ。
- エスカレーション: 段階日数で対象抽出・dedupe・対応済みで停止。
</content>
