# Design — 注文・注文請・月次検収

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（実在V80）

- `t_sales_order(id, tenant_id, legal_entity_id, order_no, customer_po_no, customer_id, contact_id, quotation_id, order_date, start/end_date, status, total_amount_snapshot, payment_terms_snapshot, source_document_id, acknowledgement_document_id, version)`。
- `t_sales_order_line(order_id, line_no, project_id, engineer_id, quantity, unit_price, settlement_min/max, amount, remarks)`。
- `t_contract.order_line_id`（UNIQUEで1明細→1契約、将来複数契約更新はrenewed chainで表現）。
- `t_contract.acceptance_required` (`NOT NULL DEFAULT 1`) および `acceptance_exemption_reason` (`VARCHAR(255)`/`TEXT` nullable)。
- `t_acceptance(id, contract_id, work_record_id, work_month, status, submitted_at, customer_contact_id, accepted_at, reject_comment, document_id, version)`、`UNIQUE(contract_id, work_month)`。
- **Migration検査方針（R09-P1-01）**: V80のオブジェクト（INDEX/FK等）存在チェックは、単なる名前の一致だけでなく `NON_UNIQUE` フラグ、順序付き構成列、prefix、参照先テーブル/列、ON UPDATE/DELETE 規則まで厳格に照合する。誤った定義の場合は drop & recreate を実行し、missing / wrong / correct の三分岐で制御する。
- **Go-live移行の耐久性（R09-P1-02）**: V80適用開始時点の既存契約ID集合を fallible DDL 実行前に durable にキャプチャし、マーカー作成前に失敗・再実行が挟まった場合でも途中で追加された新規契約が誤って `acceptance_required=0` へ移行するのを防ぐ。

## 2. Service

- `SalesOrderService`: number、状態、見積差分、`legal_entity_id` 参照、contract draft、二重hash原子検証。
- `AcceptanceService`: create/submit/accept/reject/cancel、work record Lock/CAS、as-of scope判定。
- Contract draftは既存`buildAndSaveDraft`相当の共通経路へorder sourceを追加。
- Invoice generate queryへ `acceptance_required = 0 AND acceptance_exemption_reason IS NOT NULL AND TRIM(acceptance_exemption_reason) != '' OR EXISTS(...)` 条件をSQL句として埋め込み、メモリフィルタを禁止する。

## 3. Document & PDF

- 受領注文書は `ORDER_RECEIVED`、注文請書は `ORDER_ACKNOWLEDGEMENT`、検収書は `ACCEPTANCE`。
  - **Fail-Closed アーカイブ契約（R09-P1-05）**: 注文請書PDF発行処理は `DocumentService.registerGenerated()` による文書台帳登録が成功した場合のみ注文状態を `注文請提出` へ進める。アーカイブ失敗時はトランザクションをロールバックする。
  - **発行法人印字（R09-P1-07）**: 注文請書PDFは `SalesOrder.legal_entity_id` から該当自社法人の社名、住所、インボイス登録番号を取得して印字する。
- **専用ダウンロードAPI・監査対応（R09-P1-08）**: 専用ダウンロードエンドポイント（`/download` プレフィックス）は `ActionPermissionResolver` で `file.download` 権限と紐付け、`ApiAuditFilter` により成功・拒否の双方を監査ログへ記録する。
- **文書アーカイブACL（R09-P0-01）**: `ORDER_RECEIVED`, `ORDER_ACKNOWLEDGEMENT`, `ACCEPTANCE` の3文書種別は HR ロールに対して全件不可視（0件/403）とし、`ACCEPTANCE` 文書のダウンロード/参照権限は `document_id -> acceptance.work_month -> allowedContractIdsAsOf(monthEnd)` で判定する。

## 4. UI/API

- `/sales-order`, `/acceptance`。注文一覧/詳細/差分/法人選択、月次検収grid。
- UIアクセシビリティ（R09-P2-03）: modal/フォームの `<label for="...">` 明示関連付け、アイコンボタンの accessible name、PO重複警告の `aria-live="polite"` 適用。
- quotationに「注文draft」、contract/work-recordに注文/検収status link。
- approval specのadapter: 条件差異、注文取消、検収取消。承認適用時に対象行ロック (`selectByIdForUpdate`) を保持して TOCTOU を防止する。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 注文金額/支払条件 | 見積・契約の現在値 | — | `total_amount_snapshot` / `payment_terms_snapshot` | **注文確定時点** | 未確定（下書き） |
| 検収対象工数 | `t_work_record_daily` | — | `t_acceptance`へwork record `version` (または `updated_at` CAS)＋amount snapshot | **提出時点**（確定状態とversionをロック下で検証）。以後の工数変更で検収額を変えない | — |
| 検収の要否・免除理由 | `t_contract.acceptance_required` / `acceptance_exemption_reason` | 変更は監査 | — | 請求生成時点 | `acceptance_required=0` の時は免除理由が**必須（非空）** |
| work_month | `t_acceptance.work_month` | — | `UNIQUE(contract_id, work_month)` | 対象月 | — |
| 検収権限範囲 (as-of) | 組織所属 | 異動履歴 | — | **対象月末時点 (monthEnd)** の契約母集団 | — |
| 顧客確認者 | `customer_contact_id` | contact側の期間 | 検収時の名称snapshot | 検収実行時点 | 内部代行入力（R3.5） |

- 検収は**提出時点の Contract -> WorkRecord ロック下で確定状態・versionを検証**しsnapshotする。提出後に工数が変更された場合は差戻し→再提出で処理する。
- `acceptance_required=0` の場合は理由（`acceptance_exemption_reason`）の入力が必須。理由なし免除はDB/請求SQLの双方で拒否する。

### 5.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 (`管理者`) | 全件（`isScoped=false` の全件許可は管理者のみ） | 全件 | 全て | 未検収/期限batch |
| マネージャー | 組織scope ∩ DataScope (検収・検収文書は対象月as-of) | 同左 | 自組織の未検収/差戻し | 同上 |
| 営業 | 既存DataScope（担当顧客/契約）。**組織で追加制限しない** | 同左 | 自担当の注文未受領/検収未提出 | 同上 |
| HR (`人事`) | **不可視（全検索・詳細・ダウンロード・カウント・KPIで0件/403）** | 不可視 | 不可視 | 不可視 |
| 要員 | 自分が対象の検収状態のみ（金額非表示） | 不可視 | 不可視 | 不可視 |
| scheduler principal | 全件 | — | 宛先は担当営業、管理者、対象月時点の自組織マネージャー | 期限超過、未提出 |

- 注文書原本・注文請書PDF・検収書のdownloadは、注文/検収一覧と**同じscope/as-of**を通す。空の許可集合は0件として抽出する。
- HRは受領注文・注文請・検収書・未検収カウント・KPIの全経路で拒否（0件/403）される。

### 5.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| order 下書き | →受領確認 / →取消 | 状態CAS | — | 下書きへ |
| 受領確認 | →注文請提出 / →取消 | 状態CAS | — | 受領確認へ |
| 注文請提出 | →契約化 / →取消 | 状態CAS＋`version` | 二重契約化click（DuplicateKey時は`order_line_id`で既存契約再読） | 注文請提出へ |
| 契約化 | →完了 / →取消（**承認必須**） | 状態CAS＋対象行ロック | 取消承認適用と状態変更の競合 | 取消はapproval経由 |
| 完了 / 取消 | 終端 | — | — | 新規注文 |
| acceptance 未提出 | →提出済 | 状態CAS＋Contract/WorkRecord行ロック | `reopenMonth`との並行競合 | 未提出へ（WorkRecord未確定時は拒否） |
| 提出済 | →検収済 / →差戻し | 状態CAS＋`version` | 顧客と内部代行の同時操作 | 提出済へ |
| 差戻し | →提出済（再提出） | 状態CAS | — | — |
| 検収済 | →取消（**承認必須**、R3.4） | 状態CAS＋対象行ロック | 請求生成との競合 | 取消はapproval経由 |

- 原本ファイルの二重アップロードは `tenant_id` + `document_type` + `file_hash` の DB UNIQUE / atomic claim で並行制御し、競合時は 409 Conflict を返す。

## 6. テスト

PO/hash重複、状態、複数明細、契約冪等、差分、acceptance CAS/Lock、invoice guard（免除理由チェック含む）、monthly closing、document ACL（HR拒否・as-of判定）、専用ダウンロード監査、実MySQL全通しBrowser Demo。


