# Design — 月次締めチェックリスト（monthly-closing-checklist）

対象コード: 新 `MonthlyClosingService(Impl)` / 新 `MonthlyClosingApiController` / 新 `MonthlyClosingPageController` /
`WorkRecordMapper`・`InvoiceMapper`（既存クエリ流用＋少量追加） / 新 `templates/monthly-closing/list.html` /
新 `static/js/modules/monthly-closing.js`

## 1. マイグレーション（`V31__monthly_closing_menu.sql`※）

※ 番号は実装時点の最新+1。全体調整は `customer-feature-proposals/README.md` 参照。

```sql
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('monthly-closing', '月次締め', '/monthly-closing', '/api/monthly-closing', <既存最大+1>);
INSERT IGNORE INTO t_role_menu (role, menu_id)
  SELECT r.role, m.id FROM (SELECT '管理者' role UNION SELECT 'マネージャー' UNION SELECT 'HR') r,
         m_menu m WHERE m.menu_key='monthly-closing';
```

DDL 変更なし（新テーブルなし）。H2 側は `m_menu`/`t_role_menu` を持つリプレイ構成
（V2/V9 系）に乗るため、シードを H2 リプレイに含めるかは既存メニュー追加 spec
（V14 の `sales-performance` 等）の前例に従う（実装時に V14 の扱いを確認して同じ方式を取る）。

## 2. サービス（新規 `service/MonthlyClosingService` + impl）

```java
MonthlyClosingSummaryDto summary(String month);      // R1: 5項目の件数+明細
void confirmClosing(String month, Long userId);      // R2: (a)-(d)=0 を再検証してから記録
void reopenClosing(String month, Long userId);       // R2-4
boolean isClosed(String month);                      // R4 用の単一判定点（第1段から公開しておく）
```

### 5項目の抽出（既存定義の共有が最重要・独自再定義禁止）

| 項目 | 実装 |
|---|---|
| (a) 工数未入力 | `workRecordMapper.selectMonthlyGrid(month, monthEnd)` の結果から `workRecordId == null` の行を抽出（グリッドと完全同一条件になる） |
| (b) 未確定実績 | `t_work_record` を `work_month=month AND status<>'確定'` で selectList |
| (c) 確定済み未請求 | 既存 `selectUnbilledWorkRecords(customerId, month)` は顧客単位のため、**全顧客版 `selectUnbilledWorkRecordsAll(month)`** を `InvoiceMapper` に追加する。WHERE 句は既存クエリから顧客条件を除いただけにし、**除外サブクエリ（有効請求書明細）と `c.deleted_flag=0` を一字一句共有**（コメントで既存クエリとの同期義務を明記） |
| (d) 未払BP | `t_bp_payment` × `t_work_record`(work_month=month) を join し status='未払' |
| (e) 期限超過請求 | 既存 `NotificationGenerateService.invoiceOverdue` と同条件（status≠入金済・due_date<today）。P2 導入後は残高列を追加（`InvoicePaymentMapper` の有無をリフレクションでなく **ビルド時依存にしない**ため、残高計算は `InvoiceMapper` 側に「入金テーブルが存在する場合の集計クエリ」を置くのではなく、P2 マージ後に本画面へ残高列を足す**フォローアップ1タスク**として分離する） |

### 締め記録

- `SystemConfigService` に JSON 値の get/set はないため、`MonthlyClosingServiceImpl` 内で
  `closing.confirmed-months` を Jackson で読み書きする小さなヘルパを持つ（他所からは
  `isClosed/confirm/reopen` 経由のみ。JSON 直接操作の散在禁止）。
- `confirmClosing` は保存前に summary を再計算し (a)-(d)>0 なら `error.closing.notReady`（競合防止）。
- 実行者ロール検査: `SecurityUtils.currentUserId` + `CustomUserDetailsService` のロールで
  管理者/マネージャーのみ（HR は 403、`error.closing.roleDenied`）。

## 3. API / ページ

- `GET /api/monthly-closing/summary?month=YYYY-MM`
- `POST /api/monthly-closing/confirm` (body: month) / `POST /api/monthly-closing/reopen`
- `MonthlyClosingPageController`: `GET /monthly-closing` → `monthly-closing/list`
- month は `YearMonth.parse` で検証（不正は 400、`error.closing.invalidMonth`）。

## 4. フロントエンド

`monthly-closing.js` + `list.html`（`layout/base` 準拠・既存 CRUD 画面規約）:

- 月セレクタ（既定=前月）＋5枚のカード（件数バッジ、0件は緑/残ありは黄・(e)のみグレー）。
- カード展開で明細テーブル。行リンク: (a)(b)→`/work-record/list?month=`、(c)(e)→`/invoice`、
  (d)→`/invoice#bp-payment`（`NotificationLinks` の既存定数を使える箇所は使う）。
- 「締め完了」ボタン: (a)-(d) 全て0のときのみ活性。締め済み表示（実行者名・日時）＋
  管理者/マネージャーには「締め解除」。締め済みかつ残件>0 なら警告帯（R2-5）。
- サイドバー: `layout/sidebar.html` へ `th:if="${allowedMenus.contains('monthly-closing')}"` の
  `<li>` を追加（既存規約どおり）。

## 5. i18n

`menu.monthlyClosing` / `closing.item.*`（5項目名） / `closing.btn.confirm/reopen` /
`closing.status.closed/diffWarning` / `error.closing.notReady/roleDenied/invalidMonth` — 4言語。

## 6. レーン分割

小規模のため単一レーン逐次（1→2→3）。P2(ar-management) と `InvoiceMapper` が交差する
（本 spec は `selectUnbilledWorkRecordsAll` 追加のみ）ため、**P2 レーンA のマージ後に着手**が安全。

## 7. テスト方針

| 対象 | ケース |
|---|---|
| summary | (a): グリッド無実績契約が検出/実績ありは非検出 ・(b): 入力中検出/確定非検出 ・(c): 未請求検出/請求済み非検出/取消済み請求書の明細は未請求扱い ・(d): 未払検出/支払済非検出 ・(e): 超過検出/期限内非検出 |
| confirm | (a)-(d)=0 で成功・記録JSONに月/実行者/日時 / (b)残ありで `notReady` / (e)のみ残でも成功 / HR は roleDenied |
| reopen | 記録から除去 / 未締め月の reopen はエラー |
| isClosed | 締め前 false→締め後 true→解除後 false |
