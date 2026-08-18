# Design — 要員セルフサービスポータルV2

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（S14正式migration V105）

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

### 6.1 時間・asOf・Temporal Model

本specの全サービス・コントローラはシステム時計（parameterless `now()`）の直接呼び出しを禁止し、Spring Beanとして注入されたテナント `Clock`（`Asia/Tokyo`）を使用する（R1-P2-03）。

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 / 境界規則 |
|---|---|---|---|---|---|
| 変更申請 | `t_engineer_change_request.status` | 申請ごとに行 | `payload_json`＋`diff_json`＋target fingerprint | 申請時点 | `applied_at IS NULL`＝**未反映**（承認済でも反映前がありうる） |
| Engineer master | 既存`t_engineer` | 既存 | — | 現在値 | `phone IS NULL`＝電話番号未登録 |
| skill sheet確認 | 確認日/確認version | 確認ごとに更新 | 確認時のdocument version | 確認時点 | **未確認**。客先提出前チェックの対象 |
| 経費 | `t_expense_request.status` | — | 承認時に金額/科目を固定 | 申請時点 | `paid_at IS NULL`＝未払 |
| 給与明細 | freee連携の現在値 | provider側 | — | 対象月 | 未連携。**0円と表示しない** |
| survey回答 | `t_survey_response` | campaign単位 | 回答時の`template_snapshot_version` | campaign期間 | 未回答（**平均値へ含めない**） |
| survey期間 | `period_from` 〜 `period_to` | — | キャンペーン作成時/配信時に確定 | `LocalDate.now(clock)` | 両端Inclusive。`period_from <= today <= period_to`外は400拒否 |
| 1on1候補日 | `candidate_dates_json` | — | 申請時の日付配列 | `LocalDate.now(clock)` | 翌日以降（`date >= today.plusDays(1)`）のみ有効 |

- **Temporal Model規則**:
  - `applied_at IS NULL`と「承認済」を混同しない。承認とmaster反映は別transactionになりうる。
  - survey未回答を0点として集計しない。母数から除外する。
  - survey期間境界: 開始日当日（00:00）〜終了日当日（23:59:59）まで回答を受け付け、開始前および終了日翌日以降は配信一覧から除外し回答APIを400拒否する。
  - サーベイ作成後・配信後に元テンプレートがv2へ更新されても、`t_survey_campaign.template_snapshot_version` および `template_snapshot_json` に基づきv1の設問定義・バージョン番号で永続化・返却される（R1-P1-08）。

### 6.2 主体 × 操作 × 可見母集団

**本specの母集団は原則「本人のみ」であり、engineer-account linkから解決する。**
リクエストパラメータの`engineerId`を信用しない（design §3）。

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 要員（本人） | **自分のみ**（linkから解決。engineerId指定不可） | 自分のみ（CLEAN scan済み添付のみ） | 自分の申請状態（canonical menuKey `myProfile`/`myExpenses`/`mySurveys`） | — |
| HR | 全要員の変更申請・1on1・survey集計。**confidential相談も可視** | 同左 | 承認依頼 | campaign配信 |
| 管理者 | 全件（変更申請/経費/サーベイ）。**ただしconfidential相談は明示権限グループ割当者のみ可視** | 全件 | 全て | — |
| マネージャー | 組織scope ∩ DataScope の配下要員。**confidential相談は不可視** | 同左 | 配下の申請 | — |
| 営業 | 担当要員の1on1（公開部分のみ）。**給与・経費・confidential相談は不可視** | — | 自担当の1on1 | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は本人/承認者（job tx内で発行） | survey campaign、会計連携、期限督促 |

- **本人が見られないもの**（R1.4）: 原価、commission、他要員の情報、営業memo、retention riskの内部スコア。
- **confidential相談の可視範囲はHR＋指定管理者のみ**（R4.3, R1-P1-07）:
  一般管理者ロールであっても、`one-on-one.confidential` 権限グループが明示割当されていない場合は `private_note_ref` をnullマスクし非公開とする。
  1on1相手方ユーザーは `status == 1`（有効）かつ同一組織/管轄マネージャーまたは担当営業に制限する。
- **変更申請添付の文書台帳権限**（R2-P1-01）:
  アップロード時に `targetType="ENGINEER"`, `targetId=engineerId`, `documentType="CHANGE_REQUEST_ATTACHMENT"` を指定して文書台帳内で原子的にリンクする。`createdBy` 単独でのバイパスは廃止し、`ENGINEER=engineerId` の所有リンクおよび `SCAN_CLEAN` 状態を強制する。
  添付のdownloadは documentId ではなく **申請IDを境界** にする: 本人は `/api/my/change-requests/{requestId}/attachment`、管理側は `/api/engineer-change-requests/{requestId}/attachment`。
  許可母集団（決定表固定）: **本人=自分の申請のみ / HR・管理者=全件 / マネージャー=組織scope∩DataScopeの配下 / 営業=不可**。
  他要員のIDOR試行は403でなく **404**（既存IDOR規約: `error.changeRequest.notFound` / `error.changeRequest.attachmentNotFound`）。
  未scan（CLEAN以外）は fail-closed で403（`error.file.scanNotReady`）。downloadは `ApiAuditFilter` により監査ログへ記録される。
  文書種別seed（`CHANGE_REQUEST_ATTACHMENT`）は V105/V105.1/V105.2 を変更せず **新規順方向 migration `V105_3`** で追加する（H2側 `schema-engineer-selfservice-h2.sql` と同期）。
- **survey集計の匿名性保護**（R1-P1-10）:
  `minAnswers`（既定3）未満の組織セグメントおよび設問別集計は `hidden == true` とし、平均値・離職リスクファクターを非表示とする。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback / conflict |
|---|---|---|---|---|
| change request 下書き | →申請中 / →破棄 | 状態CAS | 二重申請 | 下書きへ |
| 申請中 | →承認済 / →差戻し / →取下げ | approval engine | — | 差戻しへ |
| 承認済 | →反映済 | **状態CAS＋fingerprint（SysUser.email + Engineer master）再検証** | master側またはSysUser側の同時更新 | **409 conflict → 再申請**（自動マージしない） |
| 反映済 | 終端 | `applied_at`のCAS | retry | 新規申請 |
| expense 下書き | →申請中 | 状態CAS | — | 下書きへ |
| 申請中 | →承認済 / →差戻し | approval engine | — | 差戻しへ |
| 承認済 | →会計連携済 | `accounting_job_id`のUNIQUE＋送信通知tx内化 | job二重生成 | job retry（通知はtx内でatomic永続化） |
| 会計連携済 | →支払済 | 外部sync＋金額/日付照合 | — | — |
| 1on1 申請 | →日程確定→実施済 / →取消 | 状態CAS | 双方の同時変更 | 申請へ |

- **承認前はEngineer masterおよびSysUserを一切変更しない**（R5）。
  承認済→反映のtransactionで対象entityのfingerprint（`SysUser.email` + `Engineer.phone`/公開フィールド）を再検証し、
  競合したら**409 conflictを返し再申請を要求**する（R1-P1-03）。
- **field allowlist必須**（design §2）: `payload_json`から任意のentity fieldへ反映する経路を作らない。
  `request_type`ごとにDTOを持ち、許可fieldだけをmapする。allowlist外のkeyが来たら**リクエストを400拒否**する。
- 経費の会計連携は`accounting_job_id`のUNIQUEで冪等（R5）。
  同一経費から2件のjobを作らず、`markSent` トランザクション内で**通知行と通知outbox eventを原子的に永続化**する（R1-P1-05）。
  通知の外部配信は `NotificationOutboxService`（別dispatcher・指数backoff・max 5回）が担い、
  markSent commit後に独立して再送される。通知配信失敗は会計jobのSUCCEEDEDを巻き戻さない。
  **S15 provider契約**: 外部会計senderは `job.payload_hash`（job作成時に固定）由来の決定的冪等キーを受領し、
  「外部成功後にlocal commitが失敗して同一payloadを再送された場合」でも、重複登録せず既存の
  correlation_idを返して安全に収束させること（mock senderはこの契約を実装済み）。
- 領収書は archive のscanを通す。**未scan/感染時は本人にも表示しない**（R5、fail-closed）。
- 1on1の本人による取消（`cancelOwn`）は「申請中」段階のみ可能。日程確定（`STATUS_SCHEDULED`）以降の取消は相手方（営業/上長/HR）との日程調整を伴うため管理側取消（`cancel`）にて対応する。

## 7. UI/test

- `/my` dashboard、profile/skill/career/payroll/expense/1on1/survey。
- 本人scope parameterized test、apply競合、receipt ACL、payroll no-store/MFA、匿名閾値、mobile。

