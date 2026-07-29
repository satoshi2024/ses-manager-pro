# Design — 横断検索・実ToDo・保存ビュー・一括操作

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V65）

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

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| task期限 | `t_task.due_date` | 変更は監査ログ | — | 現在値のみ | **期限なし**。期限超過判定と督促通知の対象外 |
| task完了 | `status`＋`completed_at` | — | — | 現在値のみ | 未完了 |
| saved view | `m_saved_view`現在値 | 版を持たない（上書き） | — | 現在値のみ | `owner_user_id IS NULL` = **共有view**（未設定ではない） |
| 検索結果 | 常に現在値 | — | — | asOf検索を提供しない | — |
| bulk preview token | 発行時点で固定 | — | 対象ID＋hashを署名 | token内の値 | — |

- `due_date IS NULL`を「今日が期限」に丸めない。期限超過scheduler の`WHERE`から明示的に除外する。
- `m_saved_view.owner_user_id IS NULL`は**共有view**を意味する業務値である。
  「所有者未設定」として扱うと共有viewが全員の個人viewに化ける。§1.1のexplicit NULLに該当する。
- 横断検索はasOf検索を提供しない。過去時点の検索が要るなら別specとする（R1の範囲外）。

### 6.2 主体 × 操作 × 可見母集団

**横断検索は既存一覧の母集団を再実装しない。** 各`GlobalSearchProvider`は対応する既存mapperの
scope付きqueryをそのまま呼ぶ。検索用に新しいSQLを書くと母集団が二重定義になる（S02の再発パターン）。

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 自分宛task | 期限通知（全task） |
| マネージャー | 各provider既存の組織scope ∩ DataScope | 同左 | 自分宛＋自組織task | 同上 |
| 営業 | 各provider既存のDataScope。**組織で追加制限しない** | 同左 | 自分宛task | 同上 |
| HR / 要員 | 各provider既存のrole範囲 | 同左 | 自分宛task | 同上 |
| scheduler principal | 全件 | — | 宛先はtask担当者本人に限定 | 期限通知を1日1回冪等生成 |

- **件数にも同じscopeを適用する。** ヒット0件と権限なしを区別せず、どちらも0件で返す（R1.2）。
- taskの可視性は`assignee_user_id` OR `requester_user_id` = 自分。**組織scopeを重ねない**
  （§2.4の宛先指定通知と同じ理由。異動した本人が自分のtaskを失う）。
- saved view: 個人viewは`owner_user_id`本人のみ。共有viewは全員read、**管理者のみwrite**。
  管理者も他人の個人viewを上書きできない（R3.2）。
- bulk applyの対象母集団は**preview時のscopeで固定**。apply時に再評価してscopeが広がる実装にしない。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| task 未着手 | →進行中 / →取消 | 状態CAS | 二重click | 未着手へ戻す |
| 進行中 | →完了 / →取消 | 状態CAS＋`version` | 担当者と依頼者の同時操作 | 進行中へ戻す |
| 完了 / 取消 | 終端（再open不可） | — | — | 新taskを作る |
| saved view | 遷移なし | `version` 楽観ロック | 同一viewの同時保存 | 保存前へ戻す |
| bulk job | preview→apply（1回のみ） | preview token の一回性＋`UNIQUE` | token再送 | 各行独立。**partial success** |

- 通知の既読とtaskの完了は**独立**。既読化でtaskを完了させない、完了でtaskの元通知を消さない（R2.3）。
- 期限通知schedulerは`(task_id, notify_date)`のUNIQUEで冪等。再起動・重複起動で二重送信しない。
- bulkは200件上限、201件で**リクエスト全体を拒否**（部分実行しない）。
  200件以内は各行結果を返すpartial success（R4.1 / R4.3）。
- preview tokenは対象ID集合のhashを含めて署名し、apply時に照合する。
  対象がpreview後に変化していた行は**その行だけ失敗**させる。

## 7. テスト

scope、timeout、短query、target削除、task遷移、期限冪等、view schema、bulk token/200境界/部分成功。

