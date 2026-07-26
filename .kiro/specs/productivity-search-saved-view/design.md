# Design — 横断検索・実ToDo・保存ビュー・一括操作

## 1. DDL（予約V63）

- `t_task(id, tenant_id, title, description, assignee_user_id, requester_user_id, due_date, priority,
  status, target_type, target_id, completed_at, version, timestamps)`。
- `m_saved_view(id, tenant_id, owner_user_id NULL=共有, page_key, name, filter_json, sort_json,
  columns_json, page_size, shared_flag, version)`。

## 2. Search

- `GlobalSearchProvider` interfaceをentity種別ごとに実装し、`GlobalSearchService`がparallelではなく
  DB poolを枯らさない順次/制限付き実行で統合する。
- DTOは`type,id,title,subtitle,status,url,updatedAt`だけ。PII/原価をsubtitleへ出さない。
- 各providerは既存mapper/data scope queryを再利用し、検索後memory filterは禁止。
- endpoint `GET /api/search?q=&types=`、header command palette。

## 3. Task

- `TaskService`状態機械、関連target存在/scope検証、schedulerで期限通知を1日1回冪等生成。
- todo画面は「タスク」「通知」tabへ分離。既存notification APIを壊さない。

## 4. Saved view

- pageごとに`SavedViewSchemaRegistry`で許可filter/sort/columnを定義。
- URL queryがある場合はURLを優先、明示的保存時だけDB更新。
- default view削除時fallbackを用意する。

## 5. Bulk

- `POST /api/<resource>/bulk-preview`と`bulk-apply`。preview tokenに対象ID/hash/有効期限を署名し、
  apply時のすり替えを防ぐ。
- 各対象を既存単件serviceへ委譲し、状態機械/監査を再実装しない。

## 6. テスト

scope、timeout、短query、target削除、task遷移、期限冪等、view schema、bulk token/200境界/部分成功。

