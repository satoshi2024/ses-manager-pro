# Design — JP PINTデジタルインボイス

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V107）

- `t_peppol_participant(owner_type/id, scheme_id, participant_id, provider, verified_at, status)`。
- `t_digital_invoice(id, invoice_id NULL, direction, profile, specification_version, message_id,
  provider_message_id, xml_document_id, validation_document_id, status, sent/received_at, version)`。
- `t_digital_invoice_event(digital_invoice_id, provider_event_id, event_type, event_at, payload_hash, signature_valid)`。
- customerへdelivery preference。

## 2. Canonical model

- `CanonicalInvoice`は既存invoice snapshotから生成するimmutable DTO。
- `JpPintRenderer`はversion別renderer/validator。XML libraryはXXE無効、安全なnamespace handling。
- 金額assert: line+tax+rounding=total。差があれば送信拒否。

## 3. Provider adapter

- `DigitalInvoiceProvider`: verifyParticipant, send, getStatus, parseWebhook, downloadReceived。
- HTTP/job/error/idempotencyはaccounting integration基盤を再利用。
- webhookはraw body hash/署名検証後、provider event IDで冪等。古いeventで終端statusを巻き戻さない。

## 4. 受信

- XML security parse→schema validate→archive→supplier/amount/date/PO match→review queue。
- BP purchase作成はreview確定後にaccounting canonical DTOへ渡す。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）。
HTTP/job/error/idempotencyの基盤は accounting spec を再利用するため、
本表は**JP PINT固有の差分だけ**を書く。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| participant | `t_peppol_participant.verified_at`あり | 検証履歴 | — | 送信時点 | **未検証**。送信しない（R1.2） |
| spec version | 送信runへ保存した`specification_version` | run単位 | **送信時のversionで固定** | 送信時点 | — |
| canonical invoice | 既存invoice snapshotから生成 | — | **immutable DTO** | invoice確定時点 | — |
| 送信状態 | `t_digital_invoice.status` | `t_digital_invoice_event` | — | 現在値 | `invoice_id IS NULL`＝**受信invoice**（送信の未紐付けではない） |
| delivery preference | customer側の現在値 | — | 送信時に採用方式を記録 | 送信時点 | 既定（PDF/email） |

- `verified_at IS NULL`の宛先へ送信しない（R1.2）。未検証を「検証不要」と扱わない。§1.1に該当。
- `t_digital_invoice.invoice_id IS NULL`は**受信invoice**を意味する業務値。
  「送信invoiceだが未紐付け」と混同すると受信分が送信一覧に現れる。`direction`と併せて判定する。
- **spec versionの自動upgradeを禁止**（前提節）。送信runへ使用versionを保存し、
  version切替はconfigの明示変更＋fixture再検証を伴う。

### 5.2 金額の扱い（本specの最重要境界）

**既存`Invoice`/`InvoiceItem`/税snapshotが唯一の正であり、JP PINT側で再計算して上書きしない**（R2.4）。

| 論点 | 決定 |
|---|---|
| 金額の源 | 既存invoice snapshot。canonical変換は**写像のみ**、再計算しない |
| 検算 | `line合計 + 税 + rounding = total` を**assertする** |
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
| queued | →sent / →failed | 状態CAS（accounting jobを再利用） | worker二重起動 | queuedへ |
| sent | →delivered / →rejected / →failed | **event_atとprovider_event_idで順序制御** | webhookとstatus pullの競合 | — |
| delivered | 終端 | — | 遅延webhook | **巻き戻さない** |
| rejected | 終端。訂正は新message | — | — | 新規送信 |
| failed | →queued（手動retry） | 状態CAS | — | — |
| cancelled | 終端 | — | — | — |
| 受信 received | →matched→review確定 / →ignored | 状態CAS | 二重取込 | receivedへ |

- **webhookの冪等と順序**（design §3）:
  - `provider_event_id`にUNIQUE。重複webhookは1件目のみ処理。
  - **古いeventで終端statusを巻き戻さない。** `event_at`が現在stateより古いeventは記録のみ。
  - 署名検証は**raw body**に対して行う。parse後のオブジェクトで検証しない。
  - 署名不正は`signature_valid=false`で記録し、**状態遷移させない**（fail-closed）。
- **送信の冪等**: `(invoice_id, direction, specification_version)`にUNIQUE ＋ accounting基盤の
  `idempotency_key`。同一invoiceの再送でmessage 1件（R5）。
- **受信の重複検知**: `message_id` / supplier invoice number / payload hash の3系統（R3.4）。
- **受信invoiceを自動で支払確定しない**（R5）。必ずreview queueを経由し、人が確定する。
- XML parseは**XXE無効・external entity禁止・DTD禁止**（design §2）。
  受信XMLは信頼できない入力として扱う。

## 6. テスト

公式fixture/golden XML、XXE、rounding、participant、provider status、webhook signature/order/duplicate、
受信duplicate/照合、spec version切替、PDF fallback。

## R3 UNIQUE Key Update
To support cancellation where a new message ID must be sent without replacing the original record (R4.1), the uk_digital_invoice_send unique key on t_digital_invoice (invoice_id, direction, specification_version) is dropped. It is no longer possible to prevent multiple SEND records via DB schema alone, so the application logic will handle deduplication.
