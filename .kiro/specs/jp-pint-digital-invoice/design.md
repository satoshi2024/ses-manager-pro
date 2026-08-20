# Design — JP PINTデジタルインボイス

> Test実行範囲は test-execution-policy-s03-s17.md のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V107）

- t_peppol_participant(owner_type/id, scheme_id, participant_id, provider, verified_at, status)。
- t_digital_invoice(id, invoice_id NULL, direction, profile, specification_version, message_id,
  provider_message_id, xml_document_id, validation_document_id, status, sent/received_at, version,
  supplier_company_id, purchase_order_id, contract_id, match_status)。
- t_digital_invoice_event(digital_invoice_id, provider_event_id, event_type, event_at, payload_hash, signature_valid)。
- customerへdelivery preference。

## 2. Canonical model

- CanonicalInvoice は既存invoice snapshotから生成するimmutable DTO。
- JpPintRenderer はversion別renderer/validator。XML libraryはXXE無効、安全なnamespace handling。
- 金額assert: line+tax+rounding=total。差があれば送信拒否。

## 3. Provider adapter

- DigitalInvoiceProvider: verifyParticipant, send, getStatus, parseWebhook, downloadReceived。
- **冪等キー**: sendInvoice(xml, specificationVersion, messageId) とし、messageId を Idempotency-Key として渡す。
- HTTP/job/error/idempotencyはaccounting integration基盤を再利用。
- webhookはraw body hash/署名検証後、provider event IDで冪等。古いeventで終端statusを巻き戻さない。

## 4. 受信

- XML security parse→schema validate→archive→supplier/amount/date/PO match→review queue。
- BP purchase作成はreview確定後にaccounting canonical DTOへ渡す。

## 5. 決定表

既定解は customer-product-expansion-2026/platform-invariants.md （特に§7 外部連携）。
HTTP/job/error/idempotencyの基盤は accounting spec を再利用するため、
本表は**JP PINT固有の差分だけ**を書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| participant | t_peppol_participant.verified_at あり | 検証履歴 | — | 送信時点 | **未検証**。送信しない（R1.2） |
| spec version | 送信runへ保存した specification_version | run単位 | **送信時のversionで固定** | 送信時点 | — |
| canonical invoice | 既存invoice snapshotから生成 | — | **immutable DTO** | invoice確定時点 | — |
| 送信状態 | t_digital_invoice.status | t_digital_invoice_event | — | 現在値 | invoice_id IS NULL ＝**受信invoice**（送信の未紐付けではない） |
| delivery preference | customer側の現在値 | — | 送信時に採用方式を記録 | 送信時点 | 既定（PDF/email） |

- verified_at IS NULL の宛先へ送信しない（R1.2）。未検証を「検証不要」と扱わない。§1.1に該当。
- t_digital_invoice.invoice_id IS NULL は**受信invoice**を意味する業務値。
  「送信invoiceだが未紐付け」と混同すると受信分が送信一覧に現れる。direction と併せて判定する。
- **spec versionの自動upgradeを禁止**（前提節）。送信runへ使用versionを保存し、
  version切替はconfigの明示変更＋fixture再検証を伴う。

### 5.2 金額の扱い（本specの最重要境界）

**既存 Invoice/InvoiceItem/税snapshotが唯一の正であり、JP PINT側で再計算して上書きしない**（R2.4）。

| 論点 | 決定 |
|---|---|
| 金額の源 | 既存invoice snapshot。canonical変換は**写像のみ**、再計算しない |
| 検算 | line合計 + 税 + rounding = total を**assertする** |
| 検算NG | **送信拒否**。丸めて辻褄を合わせない（design §2） |
| 端数 | 既存invoiceの税snapshotの値をそのまま使う |
| 通貨 | 円。多通貨は初期版対象外 |
| 負数（取消/訂正） | JP PINT/providerの許可方式に従う。**旧messageを上書きしない**（R4.1） |

検算が通らない場合は「送信できない請求書がある」という**業務上の問題**として提示する。
XML生成側で丸め直すと、会計・請求・外部で3つの数字が生まれる。

### 5.3 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件。participant/preference編集可 | XML/receipt/validation report | 全障害 | 送信job、webhook処理 |
| 財務担当（permission） | 全件の送信状態・validation結果 | 同左 | reject/failed | — |
| マネージャー | 自組織に関わる請求の送信状態 | 同左 | — | — |
| 営業 | 担当顧客の**送信済/未送信の別のみ**。XML本文は不可視 | — | — | — |
| HR / 要員 | 不可視 | — | — | — |
| portal user | 不可視（顧客へはPeppol経由で届く） | — | — | — |
| scheduler / job worker | 全件 | — | 宛先は財務/管理者 | 送信、status pull、webhook |

- 送信状態の母集団は**元invoiceのscope**に従う。digital invoice側で別ACLを作らない。
- XML本文・validation reportは archive の document として保存し、archive のscopeで制御する。

### 5.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| queued | →sent / →failed / →**cancelled** | 状態CAS（accounting jobを再利用） | worker二重起動 | queuedへ |
| sent | →delivered / →rejected / →failed / →**revoked** | **event_atとprovider_event_idで順序制御** | webhookとstatus pullの競合 | — |
| delivered | →**revoked**（**アプリ起因の打消のみ**。webhookでは遷移しない） | 状態CAS | 遅延webhook | **巻き戻さない** |
| rejected | 終端。訂正は新message | — | — | 新規送信 |
| failed | →queued（手動retry） | 状態CAS | — | — |
| **cancelled** | **終端**（遅延webhookで戻さない） | — | — | — |
| **revoked** | **終端**（遅延webhookで戻さない） | — | — | — |
| 受信 received | →matched→review確定 / →ignored | 状態CAS | 二重取込 | receivedへ |

- **REVOKED / CANCELLED は終端**: 遅延 webhook・status pull いずれもこれらを上書きしない。`processProviderEvent` は event 記録のみ行う。
- **DELIVERED の webhook 扱い**: 遅延 webhook による `sent` 等への巻き戻しは禁止（event_at 順序制御）。`delivered → revoked` は **cancelInvoice（アプリ操作）のみ**許可し、webhook では行わない。
- **webhookの冪等と順序**（design §3）:
  - provider_event_id にUNIQUE。重複webhookは1件目のみ処理。
  - **古いeventで終端statusを巻き戻さない。** event_at が現在stateより古いeventは記録のみ。REVOKED/CANCELLED/REJECTEDは終端。
  - 署名検証は**raw body**に対して行う。parse後のオブジェクトで検証しない。
  - 署名不正は signature_valid=false で記録し、**状態遷移させない**（fail-closed）。
- **受信の重複検知**: message_id / supplier invoice number / payload hash の3系統（R3.4）。
- **受信invoiceを自動で支払確定しない**（R5）。必ずreview queueを経由し、人が確定する。
- XML parseは**XXE無効・external entity禁止・DTD禁止**（design §2）。
  受信XMLは信頼できない入力として扱う。

### 5.5 送信の冪等・Cancel・重複制約 (R4.1 / R5)

本スペックにおいて、同一請求書に対する多重送信とキャンセルは以下のロジックで制御する（**DB UNIQUE制約に依存しない**。`uk_digital_invoice_send` は V107_2 で DROP 済み）。

#### 有効送信 1 件の定義（enqueue ガード）

| 行の種別 | direction | profile | status が有効件数に数える条件 |
|---|---|---|---|
| 通常請求送信 | SEND | `Standard` | `status NOT IN ('CANCELLED', 'REVOKED')` |
| 打消し電文 | SEND | `CreditNote`（Credit Note 等） | **数えない**（別 message_id・別 provider 送信） |
| 取消済み旧行 | SEND | 任意 | `CANCELLED` / `REVOKED` は**数えない** |

- `enqueueInvoiceForSend` は上表の「通常請求送信」行が 1 件でもあれば拒否する。
- 打消し行（`profile != 'Standard'`）は有効件数に含めないため、旧行を `REVOKED` にした後は**同一 invoice へ再 Queue 可能**（R4.1）。

| 事象 | 制約・動作 | 冪等性 / 補償 |
|---|---|---|
| Send冪等 (R5) | 同一invoiceで**通常請求（Standard）**の有効送信レコードは1件（R5, platform-invariants §7）。 | `DigitalInvoiceProvider.sendInvoice(xml, specificationVersion, messageId)` で **messageId = Idempotency-Key**。retry HTTP でも Peppol 上は 1 message。mock も同一 messageId で同一 provider_message_id を返す。 |
| Cancel: QUEUED | 未送信（QUEUED/FAILED）の送信取消。 | 既存行の status を **CANCELLED** に更新。provider API は呼ばない。CANCELLED 行は有効件数から外れ、再 Queue 可能。 |
| Cancel: SENT/DELIVERED | 既に Peppol 網へ送信された請求の打消し（R4.1: 旧 message 上書き禁止）。 | 既存行を **REVOKED**（終端）に更新し有効件数から外す。**別行**を `profile=CreditNote`・**新 message_id** で QUEUED 作成し provider へ送信。旧行の message_id / provider_message_id は変更しない。 |

### 5.6 受信時の照合と Purchase 候補 (P1-06)

受信請求書の照合は design §4 の supplier / amount / date / PO **完全キー一致**。類似度マッチは使わない。

| 照合キー | XML 抽出元（例） | DB 保存列 | Review API / DTO フィールド | match_status | ACCEPT 後の accounting canonical 写像 |
|---|---|---|---|---|---|
| supplier | `AccountingSupplierParty` / EndpointID | `supplier_company_id` | `supplierCompanyId` | 全キー一致 → `MATCHED`、いずれか不一致 → `UNMATCHED` | `InboundPurchaseRequest.supplierCompanyId` |
| amount | `LegalMonetaryTotal/TaxInclusiveAmount` | （照合時に parsed DTO へ保持。永続は match 結果のみ） | `taxInclusiveAmount` | 上記に含む | `InboundPurchaseRequest.amount` |
| date | `IssueDate` | （照合時に parsed DTO へ保持） | `issueDate` | 上記に含む | `InboundPurchaseRequest.issueDate` |
| PO / 契約 | `OrderReference/ID`、契約参照ノード | `purchase_order_id`, `contract_id` | `purchaseOrderId`, `contractId` | PO/契約が XML に無い場合は NULL のまま（supplier+amount+date のみで判定） | `InboundPurchaseRequest.purchaseOrderId`, `contractId` |

- **保存タイミング**: 受信 webhook 処理（secure parse 後）で上記列と `match_status` を **1 回で永続化**。Review 画面は DB 列＋parsed snapshot を表示する。
- **ACCEPT**: `POST /api/inbound-invoices/{id}/review?action=ACCEPT` で status=`ACCEPTED` にし、保存済み ID 列を **accounting integration の InboundPurchaseRequest（canonical DTO）** へ渡して BP purchase 候補を生成する。自動支払確定はしない（R5）。
- **REJECT**: status=`REJECTED_MANUAL`。purchase は作らない。

## 6. テストマトリクス

公式fixture/golden XML、XXE、rounding、participant、provider status、webhook signature/order/duplicate、受信duplicate/照合、spec version切替、PDF fallbackを包含する。

| 分類 | テスト対象 | アサーション |
|---|---|---|
| **Build** | コンパイル | mvn compile および mvn test-compile が全件成功する（BusinessExceptionの引数エラー等の不在）。 |
| **Migration** | Flyway latest | 空DBから latest (V107, V107_1改, V107_2, V107_3) が正常適用される。menu 2件、connection_id NULL、UNIQUE不在、権限seed成功。 |
| **XML Render** | Renderer R2.2ノード | JpPintRendererが DueDate, BuyerReference, AccountingSupplierParty, AccountingCustomerParty, TaxTotal, **税区分, 税率, 注文/契約参照** を正しく出力すること。 |
| **Cancel** | Cancel動作 | QUEUEDのCancelは同レコードを更新。SENTのCancel時は旧行をREVOKEDとし、新レコード(打消し)を生成しAPIへ送ること。 |
| **Inbound** | Review → 仕入 (P1-06) | 受信XMLの supplier / amount / date / PO 照合を行い、結果をDBに保存し、一致したBP purchase候補を画面・DTOへ正しく提示すること。 |
| **Webhook** | event_at処理 | Webhookで受領した event_at をそのまま利用し、順序逆転を防ぐこと。 |
| **Idempotency**| HTTP + DB障害 | HTTP成功直後にDB永続化が失敗した場合でも、再試行時に外部プロバイダ側で2件の重複メッセージとならないこと（message_id 冪等）。 |

## 7. Migration Fixture と修復手順

V107_1 の不正なDDLによるFlyway適用失敗に対応するため、以下の2経路をサポートするFixtureと修復手順を定める。

### 経路1: 空DBからの新規起動 (Latest)
- V107 → V107_1（修正済） → V107_2 → V107_3 の順に実行される。
- **V107_1 成功化手順**: V107_1__jp_pint_digital_invoice_fixes.sql を編集し、不正なINSERT文を削除する。中身を ALTER TABLE t_integration_job MODIFY connection_id BIGINT NULL; と SELECT 1; のみとする。
- V107_2 にて menu, 権限シード, UNIQUE DROP（uk_digital_invoice_send）を投入する。
- V107_3 にて inbound 照合用の列を t_digital_invoice に追加する。
- ※ H2環境 (engineer-schema-h2.sql) からも uk_digital_invoice_send を削除する。
- ※ V107_2 の権限付与において、Inboundメニューには管理者とマネージャーのみを含め、財務 ロール(存在しない) や HR は含めない。

### 経路2: V107適用済・V107.1失敗済環境の復旧
- MySQL等の環境でV107_1が部分適用（ALTERのみ成功しINSERTで失敗）されている場合：
  1. データベースのバックアップを取得。
  2. V107_1 のファイルを上記「成功化」の通り修正。
  3. flyway repair を実行して checksum を再計算・記録し、V107_1 のステータスを修復する。
  4. 続けてアプリを再起動（またはFlyway実行）し、V107_2 および V107_3 を適用して残りのDDL/DMLを完了させる。