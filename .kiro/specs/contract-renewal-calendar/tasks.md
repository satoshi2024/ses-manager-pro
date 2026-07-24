# Tasks — 契約更新カレンダー＋エスカレーション（FR-06）

原則新規テーブル不要（明示フラグを持つ場合のみ V45 で軽い列）。

- [x] A. 更新状態導出＋期間フィルタAPI
  - **Objective**: `renewal-calendar` API（契約×更新期限×状態、期間フィルタ、スコープ）。
  - **実装ガイダンス**: design 1章。状態は原則導出。上限で欠けない（A7-22）。
  - **テスト要件**: `RenewalCalendarServiceTest`（状態導出/期間/スコープ）。
  - **Demo**: curl で期間内の更新期限一覧。
  - 実装: `RenewalCalendarService`/`Impl`、`ContractMapper#selectRenewalCalendarCandidates`/`selectDraftStatusesByOriginalIds`、`GET /api/contracts/renewal-calendar`。状態導出（DRAFT/CONFIRMED）に加え、明示フラグ用に軽量列 `t_contract.renewal_decision`（V50, CONTINUE/END）を追加（要確認事項を「明示フラグを持つ」で決定）。

- [x] B. カレンダーUI
  - **Objective**: 月/週カレンダー、状態色分け、契約/更新ドラフトへ導線。
  - **実装ガイダンス**: design 3章。既存 contract メニュー配下。
  - **Demo**: ブラウザで終了日カレンダー表示・クリック遷移。
  - 実装: `templates/contract/renewal-calendar.html` + `static/js/modules/contract-renewal-calendar.js`（月/週トグル、5状態の色分け凡例、クリックで詳細モーダル→継続確定/更新不要/未定に戻す）。`contract/list.html` に導線ボタンを追加。

- [x] C. エスカレーション通知
  - **Objective**: 段階日数で未対応を担当営業→上長へ通知。
  - **実装ガイダンス**: design 2章。`m_system_config` 段階設定、dedupe、対応済み停止。
  - **テスト要件**: 段階抽出・dedupe・停止。
  - **Demo**: 期限接近の未対応契約で通知が出る。
  - 実装: `RenewalEscalationService`/`Impl` + `RenewalEscalationScheduler`（毎日8:15）。`m_system_config.renewal.escalation-days`（既定 "30:営業,14:上長"）。上長は組織階層が無いため管理者/マネージャー全員へ通知。dedupeは月粒度のdedupeKey、対応済み（確定ドラフト or 明示フラグ）は抽出クエリ自体から除外され自然に停止。

- [x] M. i18n・仕上げ（5ロケール、全量緑）。
  - `messages.properties`/`messages_en`/`messages_zh_CN`/`messages_ko`（`MessageBundleConsistencyTest` が対象とする4バンドル）にキー追加。`mvn test` 全量green（647 tests, 0 failures/errors）。

## 完了条件
- 全契約の終了/更新期限がカレンダーで俯瞰でき、状態が色分けされる。
- 未対応契約が段階的にエスカレーションされ、対応で停止するテストが緑。
</content>
