# Operations & Runbook: Customer Success & Service Desk (NF-02)

> **草稿**: 未APPROVEDの先行WIPに付随して書かれた。配点・ステータス名・scheduler間隔は `requirements.md` / `design.md` / `inventory.md` §8 と一致しない箇所がある。運用正本にするのは Task M かつ DG-02 APPROVED 後。現時点のヘルス公式は減点モデル（HEALTHY/WARNING/CRITICAL）であり、本文中の NEUTRAL/AT_RISK・加点内訳はWIP残骸として信用しない。

## 1. 概要 (Overview)

SES Manager Pro のカスタマーサクセス・サービスデスク機能 (NF-02 / DG-02) は、顧客企業からの問い合わせ・要望・インシデントを一元管理し、SLA 監視、CSAT (顧客満足度調査)、定例会 (QBR) 管理、および多角的な指標に基づく顧客ヘルススコア算定と契約更新判断支援を提供する統合運用基盤です。

### 構成要素
1. **サービスデスク (Service Desk)**: 社内向け (`/service-desk/requests`) および顧客ポータル向け (`/portal/customer/requests`) の起票・返信・ステータス管理。
2. **SLA 計算・監視エンジン (SLA Engine & Scheduler)**: 営業時間 (09:00〜18:00)・土日祝日スキップ・顧客回答待ち Pause・再オープンラウンド管理・超過時 Dedupe 通知。
3. **CSAT (顧客満足度調査)**: 解決済みリクエストに対する 1 回限りのポータル評価フォーム (1〜5点 + コメント)。
4. **定例会・QBR 管理 (Customer QBR)**: 議事録・決定事項・アクションアイテム・期日管理。
5. **顧客ヘルススコア (Customer Health Score)**: 未解決P0/P1、SLA超過、CSAT平均、売掛金延滞に基づく 100 点満点減点モデルと要因内訳。
6. **契約更新カレンダー連携 (Renewal Calendar Integration)**: 契約更新カレンダー (`/contract/renewal-calendar`) へのヘルス状態バッジ表示（契約ステータスは非破壊・参照のみ）。

---

## 2. 権限 & アクセスマトリクス (Role & Permission Matrix)

| 機能 / エンドポイント | 管理者 / 営業 / マネージャー | HR / 要員 | 顧客ポータルユーザー | 備考 |
|---|---|---|---|---|
| **内部サービスデスク** (`/service-desk/requests`) | ◯ (閲覧・起票・編集・内部メモ・CSV出力) | ✕ (403) | ✕ (403) | 営業は DataScope (担当顧客) 制御適用 |
| **顧客ポータル** (`/portal/customer/requests`) | ✕ (内部ログイン) | ✕ | ◯ (自社スコープのみ) | IDOR 違反時は 404 Not Found を返却 |
| **内部メモ (Internal Note)** | ◯ (閲覧・投稿) | ✕ | ✕ (API/DTO レベルで完全除外) | 構造的漏洩防止 |
| **SLA ポリシー管理** (`/service-desk/sla-policies`) | ◯ | ✕ | ✕ | 優先度別目標時間設定 |
| **顧客ヘルス画面** (`/customer-success/health`) | ◯ (全顧客/担当顧客ヘルス閲覧・月次スナップショット実行) | ✕ | ✕ | 4要素要因内訳・欠損値補正表示 |
| **QBR 定例会管理** (`/api/customer-success/qbrs`) | ◯ (CRUD) | ✕ | ✕ | |
| **契約更新カレンダー** (`/contract/renewal-calendar`) | ◯ (ヘルスバッジ閲覧) | ✕ | ✕ | 契約更新判定は営業・管理者の手動 |

---

## 3. 運用手順 (Operational Workflows)

### 3.1 サービスリクエストの受付・対応フロー
1. **起票**:
   - 顧客ポータルまたは社内管理画面から起票（優先度 P0〜P3 を指定）。
   - 起票と同時に Round 1 の `t_service_sla_clock` が生成され、初回応答期限・解決目標期限が営業日・営業時間ベースで自動設定される。
2. **受付・初回応答 (`RECEIVED` → `IN_PROGRESS`)**:
   - 担当者をアサインし、顧客向け返信を送信。実初回応答日時が記録され、初回応答 SLA が確定。
3. **顧客確認待ち (`IN_PROGRESS` → `WAITING_CUSTOMER`)**:
   - 顧客からの追加情報待ちの場合、ステータスを `WAITING_CUSTOMER` に変更。SLA 計時が一時停止 (Pause) される。
4. **対応再開 (`WAITING_CUSTOMER` → `IN_PROGRESS`)**:
   - 顧客から返信があった場合、計時が再開され、停止していた時間が解決目標期限に自動延長加算される。
5. **解決 (`IN_PROGRESS` → `RESOLVED`)**:
   - 対応完了時にステータスを `RESOLVED` に変更。解決 SLA が確定し、ポータル側に CSAT 評価フォームが表示される。
6. **再オープン (`RESOLVED` → `IN_PROGRESS`)**:
   - 顧客から追加の問い合わせがあった場合、再オープン回数がインクリメントされ、Round 2 の SLA クロックが新設される（過去の Round 1 記録は保持）。
7. **終了 (`RESOLVED` → `CLOSED`)**:
   - 対応完了を確認後、クローズ。

### 3.2 顧客ヘルススコア算定 & 契約更新連携
- **スコア配点 (100点満点)**:
  1. **未解決 P0/P1 リクエスト (最大 30 点)**: 0件=30点, 1件=15点, 2件以上=0点
  2. **直近 90 日 SLA 遵守率 (最大 25 点)**: 違反0件=25点, 1件=15点, 2件=5点, 3件以上=0点
  3. **直近 180 日 CSAT 平均 (最大 25 点)**: 平均 4.0以上=25点, 3.0〜3.9=15点, 2.0〜2.9=5点, 2.0未満=0点（※CSAT回答なしの場合は 15点デフォルト補正）
  4. **売掛金 (AR) 延滞状況 (最大 20 点)**: 延滞なし=20点, 延滞あり=0点
- **ランク判定**:
  - `HEALTHY` (健全): 80点以上
  - `NEUTRAL` (注意): 60〜79点
  - `AT_RISK` (高リスク): 60点未満
- **契約更新カレンダーの運用**:
  - 更新カレンダー上にヘルスバッジ (`HEALTHY`: 緑, `NEUTRAL`: 黄, `AT_RISK`: 赤) が表示される。
  - **重要**: ヘルススコアは契約ステータスを自動更新しません。更新可否の判断は担当営業がリスク要因（未解決チケットやCSAT）を確認した上で手動で行います。

---

## 4. バッチ & スケジューラ運用 (Schedulers & Background Tasks)

### 4.1 `ServiceSlaScheduler`
- **実行間隔**: 30分毎 (`0 */30 * * * *`)
- **実行内容**:
  1. 未完了 (`status != 'RESOLVED' AND status != 'CLOSED'`) かつ未超過 (`response_breached = 0` または `resolve_breached = 0`) の SLA クロックを走査。
  2. 現在日時が各期限を超過している場合、`response_breached = 1` または `resolve_breached = 1` を DB に永続化。
  3. 初回検知時のみ主担当者 (`owner_user_id`) および営業担当宛てにシステム通知を発行（2回目以降は重複送信抑止）。
- **分散ロック**: ShedLock (`name = "serviceSlaMonitorTask"`, `lockAtLeastFor = "PT1M"`, `lockAtMostFor = "PT10M"`) により複数インスタンス稼動時も安全に単一実行。

### 4.2 月次ヘルススナップショット
- **手動実行**: `/api/customer-success/health/snapshot` (POST)
- **定期実行**: 毎月1日未明に全アクティブ顧客のヘルススコアを計算し、`t_customer_health_snapshot` に永続化。

---

## 5. トラブルシューティング (Troubleshooting)

| 現象 | 想定原因 | 対処方法 |
|---|---|---|
| ポータルからリクエストが見えない / 404 エラー | ログインユーザーの顧客組織IDとリクエストの `customer_id` が不一致 | ポータルユーザーの所属組織設定 (`m_portal_organization`) を確認 |
| SLA の期限が営業時間外に設定されている | カレンダーマスタ (`m_work_calendar_day`) の休日設定またはポリシーの営業時間が不正 | システム設定からカレンダーおよび SLA ポリシーを確認・再設定 |
| 内部メモがポータルに表示される懸念 | `t_service_comment.visibility` が `PORTAL_VISIBLE` で登録された | 社内画面からの投稿時に「内部メモ」トグルが ON になっていたか確認（API/DTO レベルでは `INTERNAL` は完全にフィルタされます） |
| CSAT を複数回回答できない | 1リクエストにつき1回のみ回答可能な仕様（一意性制約） | 正常な動作です。再評価が必要な場合はリクエストを再オープンしてください |
| CSV エクスポートで文字化けする | Excel のエンコーディング自動判別エラー | 本システムの CSV は UTF-8 with BOM で出力されているため、最新の Excel またはテキストエディタで直接開いてください |
