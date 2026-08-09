# Design — 要員セルフサービスポータルV2

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V87）

- `t_engineer_change_request(id, engineer_id, request_type, payload_json, diff_json, status,
  approval_request_id, applied_at, version)`。
- `t_expense_request(id, engineer_id, expense_no, expense_date, category, amount, customer_id,
  project_id, description, receipt_document_id, status, approval_request_id, accounting_job_id, paid_at, version)`。
- `t_one_on_one_request(id, engineer_id, counterpart_user_id, candidate_dates_json, scheduled_at,
  status, employee_visible_note, private_note_ref)`。
- `m_survey_template`, `t_survey_campaign`, `t_survey_response`（回答単位、visibility、consent/version）。
- skill sheet確認日/確認versionは既存document/engineer linkへ追加。

## 2. Apply adapter

- `EngineerChangeRequestService`はfield allowlistとtype別DTOを使い、任意JSON→entity反映を禁止。
- approval finalで既存Engineer/Skill/Career serviceをtransaction内に呼ぶ。target version競合時再申請。
- contact等の機密fieldは暗号化/field permissionに従う。

## 3. Payroll

- `FreeePayrollApiController`の管理APIを本人用に再利用せず、`/api/my/payroll`専用endpoint。
- engineer-account linkから本人を解決し、request engineerIdを受け取らない。
- sensitive responseは`Cache-Control: no-store`、再認証時刻/MFA contextを検証。

## 4. Expense

- approval adapter `EXPENSE_REQUEST`、archive receipt、accounting outbox。
- 金額は円、税区分/勘定科目は会計側mapping。本人が任意科目codeを送れない。

## 5. Survey/privacy

- 定型scaleと任意commentを分離。private noteは通常RetentionRisk DTOへ出さない。
- 集計は少人数組織の匿名性を守る最低回答数config。

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 変更申請 | `t_engineer_change_request.status` | 申請ごとに行 | `payload_json`＋`diff_json`＋target version | 申請時点 | `applied_at IS NULL`＝**未反映**（承認済でも反映前がありうる） |
| Engineer master | 既存`t_engineer` | 既存 | — | 現在値 | — |
| skill sheet確認 | 確認日/確認version | 確認ごとに更新 | 確認時のdocument version | 確認時点 | **未確認**。客先提出前チェックの対象 |
| 経費 | `t_expense_request.status` | — | 承認時に金額/科目を固定 | 申請時点 | `paid_at IS NULL`＝未払 |
| 給与明細 | freee連携の現在値 | provider側 | — | 対象月 | 未連携。**0円と表示しない** |
| survey回答 | `t_survey_response` | campaign単位 | 回答時のtemplate version | campaign期間 | 未回答（**平均値へ含めない**） |

- `applied_at IS NULL`と「承認済」を混同しない。承認とmaster反映は別transactionになりうる。
  未反映の承認済申請が残っていないかを監視する。
- survey未回答を0点として集計しない。母数から除外する。§1.1に該当。

### 6.2 主体 × 操作 × 可見母集団

**本specの母集団は原則「本人のみ」であり、engineer-account linkから解決する。**
リクエストパラメータの`engineerId`を信用しない（design §3）。

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 要員（本人） | **自分のみ**（linkから解決） | 自分のみ | 自分の申請状態 | — |
| HR | 全要員の変更申請・1on1・survey集計。**confidential相談も可視** | 同左 | 承認依頼 | campaign配信 |
| 管理者 | 全件 | 全件 | 全て | — |
| マネージャー | 組織scope ∩ DataScope の配下要員。**confidential相談は不可視** | 同左 | 配下の申請 | — |
| 営業 | 担当要員の1on1（公開部分のみ）。**給与・経費・confidential相談は不可視** | — | 自担当の1on1 | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は本人/承認者 | survey campaign、期限督促 |

- **本人が見られないもの**（R1.4）: 原価、commission、他要員の情報、営業memo、retention riskの内部スコア。
  「自分の情報だから全部見せる」にしない。
- **confidential相談の可視範囲はHR＋指定管理者のみ**（R4.3）。
  `private_note_ref`は通常の`RetentionRisk` DTOへ出さない。営業画面へ自由記述を出さない。
  retention riskの入力に使う場合も、**スコアへの寄与のみで原文を表示しない**（R4.4）。
- survey集計は**最低回答数config未満の組織を非表示**にする（design §5）。
  少人数組織で個人が特定される。
- 給与明細は`/api/my/payroll`専用endpoint。管理API（`FreeePayrollApiController`）を
  本人用に再利用しない。`Cache-Control: no-store`＋再認証/MFA context検証（R2.2）。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| change request 下書き | →申請中 / →破棄 | 状態CAS | 二重申請 | 下書きへ |
| 申請中 | →承認済 / →差戻し / →取下げ | approval engine | — | 差戻しへ |
| 承認済 | →反映済 | **状態CAS＋対象entityの`version`再検証** | master側の同時更新 | **再申請**（自動再試行しない） |
| 反映済 | 終端 | `applied_at`のCAS | retry | 新規申請 |
| expense 下書き | →申請中 | 状態CAS | — | 下書きへ |
| 申請中 | →承認済 / →差戻し | approval engine | — | 差戻しへ |
| 承認済 | →会計連携済 | `accounting_job_id`のUNIQUE | job二重生成 | job retry（外部は冪等） |
| 会計連携済 | →支払済 | 外部sync＋金額/日付照合 | — | — |
| 1on1 申請 | →日程確定→実施済 / →取消 | 状態CAS | 双方の同時変更 | 申請へ |

- **承認前はEngineer masterを一切変更しない**（R5）。
  承認済→反映のtransactionで対象entityの`version`を再検証し、
  競合したら**再申請を要求**する（approval spec §6.4と同じ扱い。自動マージしない）。
- **field allowlist必須**（design §2）: `payload_json`から任意のentity fieldへ反映する経路を作らない。
  `request_type`ごとにDTOを持ち、許可fieldだけをmapする。
  allowlist外のkeyが来たら**リクエストを拒否**する（黙って無視しない）。
- 経費の会計連携は`accounting_job_id`のUNIQUEで冪等（R5）。
  同一経費から2件のjobを作らない。承認済経費の領収書差替えは**再申請**（R3.3）。
- 領収書は archive のscanを通す。**未scan/感染時は本人にも表示しない**（R5、fail-closed）。

## 7. UI/test

- `/my` dashboard、profile/skill/career/payroll/expense/1on1/survey。
- 本人scope parameterized test、apply競合、receipt ACL、payroll no-store/MFA、匿名閾値、mobile。

