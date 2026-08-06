# Design — 注文・注文請・月次検収

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（実在V80）

- `t_sales_order(id, tenant_id, legal_entity_id, order_no, customer_po_no, customer_id, contact_id,
  quotation_id, order_date, start/end_date, status, total_amount_snapshot, payment_terms_snapshot,
  source_document_id, acknowledgement_document_id, version)`。
- `t_sales_order_line(order_id, line_no, project_id, engineer_id, quantity, unit_price,
  settlement_min/max, amount, remarks)`。
- `t_contract.order_line_id`（UNIQUEで1明細→1契約、将来複数契約更新はrenewed chainで表現）。
- `t_acceptance(id, contract_id, work_record_id, work_month, status, submitted_at, customer_contact_id,
  accepted_at, reject_comment, document_id, version)`、`UNIQUE(contract_id,work_month)`。
- `t_contract.acceptance_required` default true。
  - **go-live移行方針（R09-P2-01）**: V80適用時点で存在する既存契約（order_line_idがNULL）は、
    検収フロー導入前の実績の請求が停止しないよう `acceptance_required=0`（検収不要）へ移行する。
    V80以後に注文経由で作成される新規契約は `NOT NULL DEFAULT 1`（検収要）のまま。

## 2. Service

- `SalesOrderService`: number、状態、見積差分、contract draft。
- `AcceptanceService`: create/submit/accept/reject/cancel、work record version/amount snapshot。
- Contract draftは既存`buildAndSaveDraft`相当の共通経路へorder sourceを追加。
- Invoice generate queryへacceptance conditionをSQLで追加し、memory filter禁止。

## 3. Document

- 受領注文書は`ORDER_RECEIVED`、注文請書は`ORDER_ACKNOWLEDGEMENT`、検収書は`ACCEPTANCE`。
  - R3.1「原本を持つ」: 検収書（ACCEPTANCE）はB1でupload→文書台帳登録→`t_acceptance.document_id`へ設定し、
    downloadは検収一覧と同じ契約DataScopeで提供する（検収書はCONTRACTへリンクし、document側に別ACLを作らない）。
- 注文請書PDFはcompany/legal entity、注文条件、明細、顧客PO参照を印字。

## 4. UI/API

- `/sales-order`, `/acceptance`。注文一覧/詳細/差分、月次検収grid。
- quotationに「注文draft」、contract/work-recordに注文/検収status link。
- approval specのadapter: 条件差異、注文取消、検収取消。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 注文金額/支払条件 | 見積・契約の現在値 | — | `total_amount_snapshot` / `payment_terms_snapshot` | **注文確定時点** | 未確定（下書き） |
| 検収対象工数 | `t_work_record_daily` | — | `t_acceptance`へwork record `version`＋amount snapshot | **提出時点**。以後の工数変更で検収額を変えない | — |
| 検収の要否 | `t_contract.acceptance_required` | 変更は監査 | — | 請求生成時点 | **NULLを許さない**（default true）。未設定を「不要」にしない |
| work_month | `t_acceptance.work_month` | — | `UNIQUE(contract_id, work_month)` | 対象月 | — |
| 顧客確認者 | `customer_contact_id` | contact側の期間 | 検収時の名称snapshot | 検収実行時点 | 内部代行入力（R3.5） |

- 検収は**提出時点のwork record versionをsnapshot**する。提出後に工数が変わったら
  検収を自動更新せず、**差戻し→再提出**で処理する。S02の「過去snapshotを現在値で上書きしない」と同構造。
- `acceptance_required`は`NOT NULL DEFAULT TRUE`。NULLを許すと「未設定＝検収不要」に化けて
  R3.3（未検収請求の禁止）が破れる。§1.1の典型。

### 5.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 全て | 未検収/期限batch |
| マネージャー | 組織scope ∩ DataScope | 同左 | 自組織の未検収/差戻し | 同上 |
| 営業 | 既存DataScope（担当顧客/契約）。**組織で追加制限しない** | 同左 | 自担当の注文未受領/検収未提出 | 同上 |
| HR | 不可視 | — | — | — |
| 要員 | 自分が対象の検収状態のみ（金額非表示） | — | — | — |
| portal user (顧客) | **本specでは非公開**（S13で自社分の検収を開放） | — | — | — |
| scheduler principal | 全件 | — | 宛先は担当営業/管理者 | 期限超過、未提出 |

- 注文書原本・注文請書PDFのdownloadは、注文一覧と**同じscope**を通す（archive spec の
  `t_document_link`経由）。document側で別ACLを作らない。
- 要員行の可視性は `/api/my/acceptances`（状態のみ・金額非表示）で提供する。このAPIを利用する
  画面UIは本specでは持たず、S13/S14のポータル（要員セルフサービス）で接続する想定（R09-NOTE-01）。
- 月次締めchecklistの未検収件数は、**閲覧者のscopeで数える**。全社件数を全員へ見せない。

### 5.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| order 下書き | →受領確認 / →取消 | 状態CAS | — | 下書きへ |
| 受領確認 | →注文請提出 / →取消 | 状態CAS | — | 受領確認へ |
| 注文請提出 | →契約化 / →取消 | 状態CAS＋`version` | 二重契約化click | 注文請提出へ |
| 契約化 | →完了 / →取消（**承認必須**） | 状態CAS | — | 取消はapproval経由 |
| 完了 / 取消 | 終端 | — | — | 新規注文 |
| acceptance 未提出 | →提出済 | 状態CAS | 二重提出 | 未提出へ |
| 提出済 | →検収済 / →差戻し | 状態CAS＋`version` | 顧客と内部代行の同時操作 | 提出済へ |
| 差戻し | →提出済（再提出） | 状態CAS | — | — |
| 検収済 | →取消（**承認必須**、R3.4） | 状態CAS | 請求生成との競合 | 取消はapproval経由 |

- **実在V80**: DDLマイグレーション`t_contract.order_line_id`に`UNIQUE`。二重clickでも1明細→1契約（R5）。
  CAS＋UNIQUEの二重防御。
- **PO重複**: `customer_po_no`は`(tenant_id, customer_id)`内で**重複警告**（拒否ではない）。
  同一原本hash ofの二重登録は**拒否**（R2.4）。警告と拒否を混同しない。
- **請求guardはSQLで**（R3.3）: invoice生成queryに
  `acceptance_required = FALSE OR EXISTS(検収済のacceptance)` を**WHERE句として**足す。
  取得後のJava filterにしない（§2.2）。
- 検収済work recordのreopen/金額変更は、既存の月次締めguardに加えて**検収取消承認**を要求する。
  検収取消と請求生成が競合した場合、請求生成側を`version` CASで失敗させる。

## 6. テスト

PO/hash重複、状態、複数明細、契約冪等、差分、acceptance CAS、invoice guard、monthly closing、document ACL。

