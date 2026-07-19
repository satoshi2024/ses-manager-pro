# Design — データスコープ権限（data-scope-permission）

対象コード: 新 `service/security/DataScopeService(Impl)` / 各 `*ApiController`（読み経路への適用）/
`ExportApiController` / `SkillSheetApiController` / マイグレーション（config シードのみ）

**前提**: 全 P1〜P6 の完了後に着手（読み経路が増える P1/P4 を先に固めてから棚卸しするため。
本 spec が最後尾である理由）。

## 1. マイグレーション（`V34__data_scope_config.sql`※）

```sql
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('scope.sales-own-data-only', 'false', '営業ロールの閲覧を担当データのみに制限(true/false)');
```

※ 番号は実装時点の最新+1。DDL なし。

## 2. スコープ解決サービス（新 `service/security/DataScopeService`）

```java
public interface DataScopeService {
  /** スコープ発動中か（config=true かつ 現在ユーザーが営業）。以降のメソッドは発動中のみ意味を持つ */
  boolean isScoped();
  Set<Long> allowedEngineerIds();   // 現任担当（t_engineer_sales.released_at IS NULL）
  Set<Long> allowedContractIds();   // sales_user_id=自分 ∪ sales_user_id IS NULL（未帰属は可視）
  Set<Long> allowedCustomerIds();   // 担当契約・担当要員の提案の顧客 ∪ 営業活動担当
  Set<Long> allowedProposalIds();   // proposed_by=自分 ∪ engineer_id ∈ allowedEngineerIds
}
```

- 実装: 現在ユーザーは `SecurityUtils.currentUserId()`＋ロールは `Authentication` から。
  各 Set は必要テーブルを1クエリずつでロードし、`RequestContextHolder` ベースの
  リクエストスコープキャッシュ（`@RequestScope` Bean）で同一リクエスト内は再計算しない。
- **適用パターンは2種に限定**（散在防止）:
  - **一覧/検索**: 既存のページングクエリに `in("id", allowedIds)`（または対応カラム IN）を
    条件追加。allowedIds が空なら空ページを即返す（IN 空リストの SQL エラー回避）。
  - **詳細/ID直指定**: 取得後に `if (scoped && !allowed.contains(id)) throw 404`
    （`BusinessException.of(404, "error.scope.notFound")` — 既存の 404 ハンドリングに乗る）。
- ID 集合が巨大化した場合の性能は本フェーズでは許容（数百件規模想定）。将来は EXISTS
  サブクエリ化を検討、と Javadoc に明記。

## 3. 適用箇所（棚卸し表の初期仮説）

タスク1で確定させる棚卸し表の出発点。**適用方法**列の型は2章の2パターンのみ。

| エンドポイント | スコープ | 方法 |
|---|---|---|
| `GET /api/contracts`（page）/ export / gantt | contractIds | 一覧IN |
| `GET /api/contracts/{id}` | contractIds | 詳細404 |
| `GET /api/engineers`（page）/ export / `{id}` / スキルシート | engineerIds | 一覧IN / 詳細404 |
| `GET /api/customers`（page）/ `{id}` / activities | customerIds | 一覧IN / 詳細404 |
| `GET /api/proposals/kanban` ほか提案読み | proposalIds | 一覧IN |
| `GET /api/quotations`（P4） | customerIds ∪ engineerIds 由来 | 一覧IN / 詳細404 |
| セレクト用途（顧客/要員/案件セレクト） | 各 | 一覧IN |
| 適用外: dashboard/analytics/sales-performance/invoice/work-record/bp/notification/user/role-menu | — | —（R2-2 の根拠を表に記載） |

- 案件（project）は顧客経由の従属（担当顧客の案件のみ）とする——タスク1で妥当性確認。
- 営業成績: 全行表示のまま、自分の行を強調（CSSクラス1つ。集計値は不変）。

## 4. i18n

`error.scope.notFound`（既存の 404 文言と同型）— 4言語。設定画面の説明はシードの
description で足りる（`unitNoteFor` 追記不要）。

## 5. レーン分割

- **タスク1（棚卸し）→ 2（基盤）→ 3（コア4領域: 契約・要員・顧客・提案）→ 4（周辺: セレクト・
  エクスポート・見積・強調表示）→ M** の逐次。適用が機械的になるよう 2 で型を固めるのが要点。
- ほぼ全 ApiController に触れるため**他 spec と並行させない**（本 spec 実施中は他の実装を止める）。

## 6. テスト方針

- `DataScopeServiceTest`: 各 allowed 集合の解決（担当あり/なし/未帰属契約の包含）。
- 統合テスト `DataScopeIntegrationTest`（H2）: 営業2人＋担当データを用意し、
  棚卸し表の各行につき「自分の分のみ見える/他人の詳細は404/無効時は全件」を検証。
  R4-1 の4象限は代表エンドポイント（契約・要員）でフルに、残りは有効×営業のみで簡略化してよい。
- 回帰: 既定 false のまま既存テスト全緑（設定を立てるテストだけが新挙動を見る）。
