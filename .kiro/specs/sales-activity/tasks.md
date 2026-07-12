# Implementation Plan — 営業活動管理(P6)

- [ ] 1. DDL・エンティティ・Mapper・サービス
  - **Objective**: `t_sales_activity` をコードから扱えるようにする。
  - **実装ガイダンス**: `sql/006_create_sales_activity.sql`(design.md 1章)+ H2 スキーマ + エンティティ(deletedFlag あり)+ Mapper + IService。
  - **テスト要件**: H2 で論理削除が効くこと(removeById 後 selectList に出ない)。
  - **Demo**: `mvn test` グリーン。

- [ ] 2. 活動 CRUD API + サマリ API
  - **Objective**: design.md 2章の 7 エンドポイント。
  - **実装ガイダンス**: `SalesActivityApiController` 新設。created_by は `SecurityUtils`(P2 Task1 の成果物。未実装なら先にそれだけ持ってくる)。サマリは `CustomerSummaryDto`。
  - **テスト要件**: CRUD・完了化・成約率分母0。
  - **Demo**: `curl /api/customers/1/summary` で集計 JSON。

- [ ] 3. 顧客詳細画面
  - **Objective**: `/customer/{id}` に基本情報 + KPI + 活動タイムライン。
  - **実装ガイダンス**: design.md 3章。既存モーダル/Toast/SweetAlert2 パターン踏襲。
  - **Demo**: 顧客一覧 → 詳細 → 活動を登録・編集・完了・削除の一連操作。

- [ ] 4. 要フォローの可視化
  - **Objective**: 期限到来の未完了活動を一覧バッジ・詳細警告色で表示。
  - **実装ガイダンス**: `follow-ups` API + `customer.js` / `customer-detail.js` の表示分岐。
  - **Demo**: 昨日日付の next_action_date を持つ活動が要フォロー表示になり、完了で消える。

- [ ] 5. 通知連携(P3 導入済みの場合のみ)
  - **Objective**: FOLLOW_UP 通知の日次生成。
  - **実装ガイダンス**: `NotificationGenerateService.followUpDue()` 追加(design.md 4章)。
  - **テスト要件**: 冪等性(同一活動・同一予定日で1件)。
  - **Demo**: 通知バッチ実行でベルに「【フォロー】顧客名」が出る。
