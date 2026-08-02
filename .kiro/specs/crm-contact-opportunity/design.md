# Design — CRM複数担当者・商機管理

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（V73 / V74 / V74.1 / V74.2 / V74.3）

- **逸脱と根拠（legacy repair）**: V73/V74は適用済みのため編集しない。RepeatableはV73より前の
  target適用でno-op履歴を残し得るため、V74.1をV75（S07 approval予約）より前のforward-fixとして追加する。
  `stage_changed_at`、
  `probability_override_reason`、`source_cost`、`source_opportunity_id`、`contact_id`、
  `opportunity_id`の6列は、V73適用済みDBとV71以前からの部分復旧DBを同一形状へ収束させる
  `R__crm_contact_reconciliation.sql`の条件付きALTERを正とする。これは新規DDLの通常経路ではなく、
  V73適用履歴を確認したうえで欠落列だけを補完する逸脱であり、Repeatable以外のDML/backfillと
  同じrepair境界に置く。V74.1はlegacyのemail-only/phone-onlyを含むcontact backfill、activity
  version、6列/indexの補完を冪等に実施する。V74.2はV74.1で小数型になった`source_cost`を
  円単位の`DECIMAL(14,0)`へ四捨五入して収束させ、leadの会社名/email/電話の正規化済み検索キーと
  indexを追加する。leadのcreate/updateは同じ正規化規則でキーを維持する。V74.2のSQL backfillでは
  MySQLにUnicode NFKCがないため、V74.3のmanaged Java migrationが既存`t_lead`をruntimeと同じ
  NFKC規則で再計算する。正規化後空文字はNULLとする。同期表はV73/V74/V1（不変）、`V74_1`、
  `V74_2`、`V74_3`、`schema-crm-h2.sql`、`engineer-schema-h2.sql`、MySQL smoke assert、entityの7点とし、
  R__の再実行は欠落列・indexだけを変更する。

- `t_customer_contact(id, customer_id, name, name_kana, department, position, roles_json,
  email, phone, primary_flag, valid_from/to, status, version)`。
- `t_lead(id, company_name/company_name_normalized, contact_name, contact_email/contact_email_normalized,
  contact_phone/contact_phone_normalized, source, owner_user_id, status,
  converted_customer_id/opportunity_id, version)`。
- `t_opportunity(id, customer_id, title, stage, expected_start_month, duration_months, required_count,
  unit_price, expected_amount, probability, owner_user_id, next_action_date, competitor, lost_reason,
  converted_project_id/quotation_id, version)`。
- `t_sales_activity.contact_id/opportunity_id/assignee_user_id/version`。versionは更新・完了・削除の
  expectedVersion CASに使い、競合時は409とする。

## 2. 状態/変換

- `OpportunityService`状態機械と`convertToProject/Quotation`。
- opportunity IDをproject/quotation source列へ保存しUNIQUEで冪等。
- 受注/失注後は活動追記可、主要金額/顧客/stageは編集不可。

## 3. Contact利用

- quotation/contract/invoice/documentの宛先はcontact IDを任意指定し、名称/email snapshotを保存。
- 既存単一contact fieldはmigration後read compatibility、write禁止。

## 4. UI

- customer detailへcontacts/opportunities/activities統合timeline。
- `/crm/opportunities` kanban/list、`/crm/leads`。
- next actionからtask specへtask作成。

## 5. KPI/forecast

- opportunity見込は未変換openだけ。proposalへ変換済みは既存提案forecastへ移り二重計上しない。
- probabilityはstage default + override理由。

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 顧客担当者 | `valid_to IS NULL`かつ`status=有効` | `t_customer_contact.valid_from/to` | 帳票の宛先名称/email snapshot | 対象日を含む区間 | 退職日未定（在籍中） |
| 主担当 | `primary_flag=1`の1件 | 期間で切替 | — | 対象日時点 | 主担当**未設定**（宛先自動選択しない） |
| 帳票の宛先 | — | — | quotation/contract/invoice/documentへ名称+email snapshot | **常にsnapshot** | 宛先contact未指定（既存の顧客代表へ） |
| opportunity stage | `t_opportunity.stage` | 遷移は監査ログ | — | 現在値のみ | — |
| 商機stageの滞留起点 | `stage_changed_at` | stage変更時刻は現行値のみ | — | `stage_changed_at` | 明示NULL/履歴不存在は同一視せず、旧行の未記録時だけ`updated_at`へfallback |
| 確度 | `probability` | — | — | 現在値 | stage defaultを使う（override無し） |
| 失注理由 | `lost_reason` | — | — | 現在値 | **未入力**。失注stageでは入力必須 |

- 退職・異動した担当者は**履歴を残し、新規メール宛先候補から除外**（R1.3）。
  過去帳票の宛先表示はsnapshotなので変わらない。
- `primary_flag`は「1顧客につき有効期間内に1件」を部分UNIQUEまたはservice側CASで保証する。
  0件も許容する（主担当未設定）。0件を「先頭の担当者」へ暗黙fallbackしない。

### 6.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 全て | 滞留/next action batch |
| マネージャー | 組織scope ∩ DataScope | 同左 | 自組織の商機 | 同上 |
| 営業 | 既存DataScope（担当顧客/商機）。**組織で追加制限しない** | 同左 | 自担当のnext action/滞留 | 同上 |
| HR | 不可視 | — | — | — |
| 要員 | 不可視 | — | — | — |
| portal user | 不可視（顧客に自社の商機情報を見せない） | — | — | — |
| scheduler principal | 全件 | — | activityは`assignee_user_id`本人、NULL時のみ`created_by`へfallback | next action期限、滞留日数 |

- **PII平文閲覧の主体**: 顧客担当者のemail/phoneは`AuthorizationService`の
  `customer.pii.view` actionとCRM/DataScopeの積集合で判定する。管理者・営業・マネージャーの
  平文可否はrole文字列ではなくpermission groupのbaseline+denyで決め、画面DTOとCSVは同じ
  service実装でmaskする。HR/要員はCRM母集団の外とする。
- **営業活動の主体別可視母集団**: CRM利用可能な管理者・営業・マネージャーは
  `CrmScopeService`、それ以外の既存ロールは既存`DataScopeService`を使う。営業活動APIの
  CRM化でHR/要員の既存顧客活動導線を404へ変えない（CRM商機画面自体の可視性は上表どおり）。

- **営業活動の権限分類**: `/activities`は既存のcustomer activity actionとして扱い、CRM専用の
  `crm.*` actionはcontacts/timelineと商機画面に限定する。controller・service・action resolverは
  同じ主体分岐を使い、HRのDataScope内activityを維持する。

- **contact のPIIは`export`にも同じmaskを適用する**（R1.4）。画面でmaskしてCSVで素通しにしない。
  §2.3のconsumer inventoryで`export/CSV/Excel`を必ず確認する。
- lead は`owner_user_id`基準。未割当leadは**営業全員から見える**（母集団0件を避けるため）。
  この点だけ DataScope の既定（未帰属は可視）と同じ扱いにする。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| opportunity 見込 | →要件確認 | 状態CAS | — | 前stageへ戻す（監査あり） |
| 要件確認 | →提案準備 / →失注 | 状態CAS | — | 同上 |
| 提案準備 | →見積提出 / →失注 | 状態CAS | — | 同上 |
| 見積提出 | →交渉 / →失注 | 状態CAS | — | 同上 |
| 交渉 | →受注 / →失注 | 状態CAS＋`version` | 二重受注click | 同上 |
| 受注 | 終端。活動追記のみ可 | — | — | 金額/顧客/stageは**編集不可** |
| 失注 | 終端。活動追記のみ可 | — | — | `lost_reason`必須 |
| lead 未対応 | →対応中→転換済 / →破棄 | 状態CAS | 二重転換 | 転換済は終端 |

- **受注時のproject/quotation変換は`UNIQUE`で冪等**（R2.4）。
  `t_project.source_opportunity_id`と`t_quotation.source_opportunity_id`にUNIQUE制約を置き、
  2回目の受注操作でも1件しか作らない。CAS＋UNIQUEの二重防御。
- **forecast二重計上の排他**（R4.2）: opportunity forecastの母集団は
  `converted_quotation_id IS NULL AND stage NOT IN (受注, 失注)`。
  変換済みは既存の提案加重forecastへ移る。両系列を足し合わせる画面を作らない。
  `converted_quotation_id IS NULL`は「未変換」であり、§1.1の明示NULL判定を要する。
- 重複contact/leadはemail/phone/正規化会社名で**候補表示のみ。自動mergeしない**（R3.4）。
- sales activityのupdate/complete/deleteは`version`を必須とする。読み取り後に別writerが更新した場合は
  更新件数0を409へ変換し、last-writer-winsを許可しない。

## 7. テスト

primary contact一意、期間、PII mask、stage、冪等変換、forecast排他、activity scope、duplicate candidate。
- migration静的検査: `db/migration`と`sql/runbook`のストアド内`IF ... THEN`/`END IF`均衡、
  V71→latest legacy、V73 partial repair、V74.1 forward-fix、2回目migrate no-op。
- H2⇔MySQL型差: `roles_json`のJSON/CLOB往復fixtureをMySQL smokeとservice testで検証。
- PII mask: mask済みDTOをmockせず、実serviceのAuthorizationService判定を通した営業・マネージャー・
  管理者の画面/CSV出力を検証。
