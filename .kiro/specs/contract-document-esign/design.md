# 契約書・CloudSign 本番署名閉ループ 設計

## 1. 設計原則

1. `research.md` に固定した公式契約を外部 API の唯一の正とする。
2. 外部変更 API を DB transaction 内で呼ばない。
3. CloudSign 公式契約に provider-side idempotency key が無いため、**timeout を失敗とみなして同じ変更を再送しない**。
4. 送信原本、締結済み PDF、合意締結証明書を別 artifact として不変 hash で追跡する。
5. 既存テンプレート/PDF/契約 scope/文書台帳を再利用し、別の汎用 workflow engine や独自 storage を作らない。
6. provider credential、scanner、storage、契約 scope が欠けた場合は fail-closed とする。

既定解は `.kiro/specs/customer-product-expansion-2026/platform-invariants.md` §2.5、§3.2、§3.3、§4、§7 に従う。本 spec に固有の決定だけを以下へ記す。

## 2. 現状差分

| ID | 現在 | 本設計 |
|---|---|---|
| HFP-02-DD-01 | `CloudSignClientImpl.send()` が JSON で `/documents` を一回呼び、ローカル PDF を upload しない | 公式の form/multipart 4工程を細粒度 client で直列実行 |
| HFP-02-DD-02 | 長寿命の静的 `cloudsign.token` を Bearer に設定 | secret `client_id` から `POST /token`、3600秒 token を single-flight cache |
| HFP-02-DD-03 | 外部書類作成成功後に初めて DB 更新。二重クリック/timeout で重複・孤児化 | queue CAS、永続 operation、工程 checkpoint、結果不明 reconciliation |
| HFP-02-DD-04 | `sync()` が `@Transactional` のまま GET/download を呼ぶ | 外部呼出しと binary staging は transaction 外、短い CAS transaction だけで保存 |
| HFP-02-DD-05 | status 取得と PDF download を一つの `Result` に混在 | token/document/file/participant/send/status/certificate を独立 method/DTO に分離 |
| HFP-02-DD-06 | file download 例外を握り潰し、証明書は常に null | 締結後 artifact 回収を独立 job とし、失敗を状態・監査・alert へ残す |
| HFP-02-DD-07 | `pdfSha256` を締結済み PDF hash で上書き | source/signed/certificate の hash と archive ID を分離 |
| HFP-02-DD-08 | 外部 PDF scan に `FileKind.SKILL_SHEET` を誤用 | PDF 専用 `CONTRACT_PDF` を追加し、source/signed/certificate を fail-closed 検証 |
| HFP-02-DD-09 | entity を API へ返し、download に no-store/filename がない | allow-list DTO、artifact 別 download、no-store、attachment、監査 |
| HFP-02-DD-10 | 定期 polling なし | ShedLock 付き polling、manual sync、滞留/結果不明 metrics |

## 3. 外部契約

### 3.1 環境と認証

| 用途 | 公式契約 |
|---|---|
| production server | `https://api.cloudsign.jp` |
| sandbox server | `https://api-sandbox.cloudsign.jp` |
| token | `POST /token`, `application/x-www-form-urlencoded`, `client_id` |
| token response | `access_token`, `expires_in`（公式例 3600）, `token_type=Bearer` |
| API auth | `Authorization: Bearer <access_token>` |

`cloudsign.client-id` は環境変数/secret manager から注入する。Token は memory のみとし、`expires_in - safetyMargin` まで cache する。複数 JVM 間で token を共有するためだけの DB table は作らない。401 の一回再取得で足りず credential failure が続く場合は circuit を開き、書類を勝手に再送しない。

### 3.2 入力項目なし・単一宛先の送信順序

| 工程 | request | media type | 保存する証跡 | 次工程条件 |
|---|---|---|---|---|
| TOKEN | `POST /token` | form-urlencoded | expiry、safe error code（token 自体は保存しない） | Bearer token が得られた |
| CREATE | `POST /documents` | form-urlencoded | document ID、operation marker、provider status | status=0 の document が一件 |
| UPLOAD | `POST /documents/{id}/files` | multipart/form-data (`name`,`uploadfile`) | file ID、source hash、filename | GET でも対象 file が確認できる |
| PARTICIPANT | `POST /documents/{id}/participants` | form-urlencoded | participant ID、宛先 payload hash | GET でも対象 participant が確認できる |
| PRE-FLIGHT | `GET /documents/{id}` | - | file/participant/status snapshot hash | status=0、file/recipient が期待値と一致 |
| SEND | `POST /documents/{id}` | 公式 schema に従う | provider status、sent time | status=1 を GET で確認 |

変更 API の直後は provider 反映に数秒〜10秒かかる可能性がある。直後の GET 不一致を変更失敗と断定せず、待機を含む bounded reconciliation を行う。各 request は前 request の response を受けてから直列実行する。

### 3.3 状態・artifact 取得

| 用途 | request | 条件 |
|---|---|---|
| 状態取得 | `GET /documents/{documentID}` | active/結果不明/手動同期 |
| 締結済み PDF | `GET /documents/{documentID}/files/{fileID}` | provider status=2、送信時 file ID が一致 |
| 合意締結証明書 | `GET /documents/{documentID}/certificate` | provider status=2 |
| 取消（採用時） | `PUT /documents/{documentID}/decline` | provider status=1、理由1000文字以内、二重確認 |

certificate の成功内容は PDF である。レスポンスの Content-Type だけを信用せず、PDF magic/EOF と上限を検査する。

## 4. コンポーネント

### 4.1 設定と provider client

- `CloudSignProperties`
  - `enabled`, `environment`, `baseUrl`, `clientId`, token safety margin、connect/read timeout、poll batch/cron、retry 上限。
  - prod validator は HTTPS と host allow-list、client ID、scanner/storage/document-ledger の readiness を検証する。
- `CloudSignTokenProvider`
  - `getToken()`、`invalidateAndGetOnce()`。Token 値を `toString()`、exception、metric tag に載せない。
  - JVM 内 single-flight。失敗時に古い token を無期限利用しない。
- `CloudSignApiClient`
  - `createDocument`, `uploadFile`, `addParticipant`, `getDocument`, `sendDocument`, `declineDocument`, `downloadFile`, `downloadCertificate` を独立させる。
  - Map ではなく、固定版 OpenAPI から手書きした最小 allow-list DTO を使う。未知 response field は許容し、必須 field 欠落は schema error。
  - mutation method は内部で自動 retry しない。GET/token のみ error policy に従い retry 可能。

### 4.2 application service

- `ContractDocumentServiceImpl`
  - 既存 `create()` と source PDF 生成を維持する。
  - `queueSend(id, ConfirmedSendRequest)` は scope/role の後、source/recipient/payload hash を検証して状態 CAS するだけ。外部 API を呼ばない。
- `CloudSignDispatchService` / `CloudSignDispatchWorker`
  - due operation を状態 CAS で claim し、工程単位に provider call → short checkpoint transaction を繰り返す。
  - crash/stale claim を工程別に reconciliation し、CREATE 中断で document ID 不明なら `結果不明` へ送る。
- `CloudSignSyncService`
  - provider GET、状態 mapping、artifact 回収を transaction 外で行い、version/status CAS で更新する。
- `CloudSignPollingScheduler`
  - ShedLock 付き。active と artifact 未回収の行を batch 処理し、一行の失敗で batch 全体を rollback しない。
- `CloudSignReconciliationService`
  - 変更結果不明の照合。自動証明できない場合は finding を作り、人手操作なしに mutation を再開しない。

外部 client と DB update を同じ `@Transactional` method に置かない。transaction helper は別 bean または `TransactionTemplate` で短く明示し、self-invocation による annotation 無効化を避ける。

### 4.3 API/UI

- `ContractDocumentApiController`
  - entity を返さず `ContractDocumentListDto`, `ContractDocumentDetailDto`, `ContractDocumentArtifactDto`, `CloudSignOperationDto` を返す。
  - send は確認済み payload を JSON body で受け、durable queue operation を返す。
  - source/signed/certificate download を別 path にする。
- `contract-document/list.html` / `contract-document.js`
  - business/dispatch 状態、最終同期、artifact availability、安全な次操作を表示。
  - HTML の button 非表示だけに依存せず、API 認可を正とする。

## 5. 永続化

`t_contract_document` は V20 で初めて導入され V1 に存在しないため、V1 へ重複追加しない。既存 V20 は編集せず、実装時点の merge 済み latest + 1 migration、`schema-contract-document-h2.sql`、必要なら `engineer-schema-h2.sql`、entity、fresh/legacy MySQL smoke を同期する。

### 5.1 `t_contract_document` の追加/意味固定

| 論理 field | 目的 |
|---|---|
| `pdf_sha256` | **送信原本 hash のまま固定**。締結済み PDF で上書き禁止 |
| `signed_pdf_sha256` | 締結済み PDF hash |
| `certificate_sha256` | 合意締結証明書 PDF hash |
| `signed_archive_document_id` | 文書台帳の署名済み PDF ID |
| `certificate_archive_document_id` | 文書台帳の証明書 ID |
| `cloudsign_participant_id` | 公式 participant ID |
| `cloudsign_status` | provider の raw numeric status。未知値も保存可能 |
| `dispatch_state` | 技術的な配送工程 |
| `operation_id` | 一送信操作の UUID。外部照合 marker の元 |
| `send_payload_sha256` | source/recipient/title/options を canonicalize した hash |
| `dispatch_attempt_count`, `next_attempt_at` | bounded retry 制御 |
| `claimed_at`, `claim_owner` | stale worker 検出 |
| `last_provider_error_code` | PII を含まない分類 code |
| `version` | 状態 CAS / sync 競合防止 |

既存 `signed_pdf_path` / `certificate_path` は legacy read/backfill 用に残し、新規取得では文書台帳 ID を正とする。backfill は次の分類を記録し、曖昧な行を送信しない。

| 既存形状 | backfill |
|---|---|
| `cloudsign_document_id` 無し、status=下書き | `dispatch_state=NONE` |
| 外部 ID 有り、status が送信中/確認中 | `dispatch_state=RECONCILIATION_REQUIRED`、provider GET 待ち |
| status=締結済/完了、artifact path 有り | hash を再計算して legacy artifact を台帳へ移行候補化。provider 自動再送なし |
| hash/path/外部 ID が矛盾 | finding として停止 |

### 5.2 operation marker

公式 OpenAPI 0.36.0 に idempotency/request-id 契約は無い。`operation_id` から生成した非 PII marker を CloudSign の title/note の確認済み field に含め、CREATE の response 喪失時に document list から一意照合できる可能性を持たせる。

ただし、**list の全ページから marker を一意検索でき、遅延後も一件だけ返ることを sandbox で証明するまで自動 reconciliation に使用しない**。証明できない場合の正規動作は `RECONCILIATION_REQUIRED` と人手確認であり、再 CREATE ではない。

## 6. 状態機械と競合

### 6.1 provider status mapping

| provider status | local business status | 意味 |
|---:|---|---|
| 0 | 下書き | CloudSign 側下書き。dispatch 工程と合わせて操作可否を決める |
| 1 | 先方確認中 | 送信済み。`POST /documents/{id}` を再実行すると reminder になるため禁止 |
| 2 | 締結済 | terminal。artifact 回収が別途未完了の場合がある |
| 3 | 取消・却下 | terminal |
| 4 | 要確認/対象外 | template。送信対象の外部 ID として拒否 |
| その他 | 要確認 | mapping を追加するまで自動遷移・自動送信しない |

### 6.2 dispatch state

| state | 許可遷移 | 防重/competing writer | timeout/crash |
|---|---|---|---|
| `NONE` | `QUEUED` | `(id, version, state)` CAS | 変化なし |
| `QUEUED` | `CREATING` | worker claim CAS | stale 前なら他worker禁止 |
| `CREATING` | `DOCUMENT_CREATED` | operation ID + CAS | ID未保存なら `RECONCILIATION_REQUIRED` |
| `DOCUMENT_CREATED` | `UPLOADING` | known document ID + CAS | GET document で照合 |
| `UPLOADING` | `FILE_UPLOADED` | unique filename/source hash + CAS | GET files→candidate download/hash。証明不能なら停止 |
| `FILE_UPLOADED` | `ADDING_PARTICIPANT` | recipient payload hash + CAS | GET participants で照合 |
| `ADDING_PARTICIPANT` | `READY_TO_SEND` | participant exact match + CAS | 証明不能なら停止 |
| `READY_TO_SEND` | `SENDING` | preflight snapshot + CAS | 二worker send禁止 |
| `SENDING` | `SENT` | known document ID + CAS | GET status。staleを考慮し、盲目的POST禁止 |
| `SENT` | `COMPLETED` / `CANCELED` | provider status + version CAS | polling/manual syncで継続 |
| any mutable | `RETRY_WAIT` | retryable GET/token only | attempt上限後 `FAILED_FINAL` |
| any mutation uncertain | `RECONCILIATION_REQUIRED` | 明示的な運用解除だけ | 自動 mutation 禁止 |

`RECONCILIATION_REQUIRED` からの復旧は、`同一外部書類を一件に特定`, `原本/recipient/status が一致`, `reviewer と理由を監査` の三条件を満たす専用操作だけとする。一般の send ボタンは使用しない。

### 6.3 504/timeout の判断表

| 工程 | 自動 retry | reconciliation |
|---|---|---|
| token | 可（bounded） | token 自体に外部業務副作用なし |
| GET status/file/certificate | 可（bounded） | 同一 resource の read |
| CREATE | 不可 | sandbox 証明済み marker 一意照合、または人手 CloudSign 一覧照合 |
| UPLOAD | 不可 | GET files、candidate download、source hash 一致を証明 |
| ADD PARTICIPANT | 不可 | GET participants の email/name/organization/order を照合 |
| SEND | 不可 | 反映遅延後 GET status。status=1/2/3なら確定。0が続く場合も運用確認後のみ再操作 |
| DECLINE | 不可 | GET status=3 を確認。1のままでも運用確認なしに再送しない |

## 7. artifact 保存

### 7.1 三 artifact

| artifact | source | immutable evidence | 文書台帳 type |
|---|---|---|---|
| 送信原本 | local generated PDF | `pdf_sha256`, template/version/rendered snapshot | `CONTRACT` |
| 締結済み PDF | CloudSign file endpoint | `signed_pdf_sha256`, provider document/file ID | `SIGNED_PDF` |
| 合意締結証明書 | CloudSign certificate endpoint | `certificate_sha256`, provider document ID | `ESIGN_CERT` |

`pdf_sha256` を signed hash で更新しない。同一 provider ID/file ID を再取得して hash が変わった場合、既存版を上書きせず integrity finding を上げる。法定文書台帳の `(source_type,business_key,version_discriminator)` 冪等キーには provider document/file ID と hash version を含め、同一 artifact の二重登録を防ぐ。

### 7.2 binary pipeline

1. HTTP response を size 上限付き temp quarantine に stream する。
2. Content-Type allow-list、`%PDF-`、末尾 `%%EOF`、空でないことを検査する。
3. `FileKind.CONTRACT_PDF` で scanner を呼ぶ。scanner 不在/例外/INFECTED は quarantine のまま fail-closed。
4. SHA-256 を計算する。
5. `DocumentService.registerReceived` 相当の既存 atomic storage/metadata/ledger pipeline へ渡す。
6. DB に archive document ID/hash を CAS 保存する。
7. DB/storage の一方だけが成功した場合は既存 orphan cleanup safety window と finding で補償する。

memory に全 artifact を持つ現行 `CloudSignClient.Result(byte[])` は廃止し、size 制限付き stream/temp reference を返す。

## 8. scope・権限・API

### 8.1 主体 × 操作 × 母集団

| 主体 | list/detail/status | create/send/cancel | download | scheduler/worker |
|---|---|---|---|---|
| 管理者 | 全件 | 可 | 可 | - |
| 営業 | DataScope 許可契約 | 可 | 可 | - |
| マネージャー | OrganizationScope ∩ DataScope | 可 | 可 | - |
| HR | 既存 role/DataScope 境界 | **不可**（manual syncも不可） | 可 | - |
| 要員 | 不可 | 不可 | 不可 | - |
| system worker | active operation 全件、明示 system context | provider 処理のみ | artifact 回収 | ShedLock/CAS |

全 API は document ID から parent contract ID を解決して scope を判定する。scope 外と不存在は同じ 404 とする。scheduler/worker に request の `SecurityContext` や request-scoped DataScope を持ち込まない。

### 8.2 API shape

| operation | endpoint（案） | response |
|---|---|---|
| list | `GET /api/contract-documents/contract/{contractId}` | allow-list list DTO, no-store |
| detail | `GET /api/contract-documents/{id}` | detail DTO, no-store |
| create local PDF | `POST /api/contract-documents` | local draft DTO |
| queue send | `POST /api/contract-documents/{id}/send` | operation DTO（queue受付であり送信完了ではない） |
| manual sync | `POST /api/contract-documents/{id}/sync` | latest operation/status DTO |
| cancel | `POST /api/contract-documents/{id}/cancel` | operation DTO |
| artifact | `GET /api/contract-documents/{id}/artifacts/{source|signed|certificate}` | attachment PDF, no-store |

request parameter に宛先 PII を載せず JSON body を使う。DTO に `pdfPath`, `signedPdfPath`, `certificatePath`, `renderedHtml`, provider raw response, internal stack/error を含めない。

## 9. retry・rate limit・可観測性

- 同一 access token 800 req/min の公式上限より低い global limiter を設定し、poll/manual/dispatch/token 再試行を一つの budget で扱う。
- GET/token は exponential backoff + jitter、max attempts。4xx validation/permission と mutation の timeout は retry 対象外。
- metric は低 cardinality とし、document/operation/email/token を tag にしない。
- 必須 metric/alert:
  - queue age/count、dispatch state count、reconciliation count/age。
  - token failure、401 repeat、403 plan/permission、429、5xx/504。
  - polling last success/duration、artifact pending、scan failure、hash change。
- correlation はローカル operation ID を log MDC と audit に使う。公式契約に無い correlation header を provider が受理する前提にしない。

## 10. UI

- 一覧は業務状態と配送工程を分けて表示する。
- 送信 modal は契約番号、template version、source hash prefix、宛先名/会社/email/言語を表示し、checkbox または明示ボタンで確認する。
- queue 成功メッセージは「送信処理を受け付けました」。CloudSign の status=1 を確認する前に「送信しました」と表示しない。
- `結果不明` は赤色だけでなく icon/text と operation ID を表示し、再送ボタンを隠して運用確認手順へ導く。
- source/signed/certificate の available/pending/rejected を別表示する。
- desktop と 390px で操作名、宛先、警告を省略しない。

## 11. テスト設計

### 11.1 provider contract test

MockWebServer/WireMock 等で実際の HTTP request を検査する。

- token の form Content-Type/client_id、Bearer header、token expiry/single-flight/401一回。
- create/form → upload/multipart binary byte equality → participant/form → preflight GET → send の厳密な順序。
- 追加 response field は許容、必須 ID/status 欠落、HTML error、空 binary、巨大 body は拒否。
- status 0/1/2/3/4/未知、400/401/403/404/409/413/415/429/500/504/timeout。
- mutation timeout 時に同じ mutation が二回呼ばれないこと。

fixture は固定 OpenAPI/version/SHA と対応付け、sandbox のマスキング済み response から作成する。token、email、PDF 本文、実 ID を commit しない。

### 11.2 concurrency/transaction test

- 2/25/100 同時 send request で operation 一件、provider create 一件。
- worker crash を CREATE response 前/後、upload、participant、send の各境界で注入する。
- provider server が request を受理して client が timeout する case を再現する。
- manual sync と polling の commit 順を反転しても terminal 状態が逆戻りしない。
- provider call 時に Spring transaction が active でないことを assert する。

### 11.3 file/security/controller test

- source/signed/certificate 三 hash が不変かつ別列、既存 source hash 非上書き。
- PDF magic/EOF/size、scanner CLEAN/INFECTED/UNAVAILABLE、storage/DB partial failure、hash 相違。
- role matrix、CSRF、scope 外 404、entity/path/raw error 非露出、no-store、Content-Disposition、download 成功/拒否監査。
- log capture で client ID/token/access code/full email/PDF 本文が 0 件。

### 11.4 migration/E2E

- fresh、公開済み V20 形状を含む legacy、partial columns/index、backfill contradiction、failed history repair。
- sandbox 正常閉ループと timeout reconciliation。provider credential 不在で自動 skip せず、通常 unit suite と明示 sandbox gate を分け、release gate は未実行なら BLOCKED。

## 12. rollout・停止・rollback

1. merge前にsandbox E2E、運用checklist、alert、kill switch/rollback、canary dry-runをPASSし、独立Reviewで`REVIEWABLE`にする。
2. merge後、`cloudsign.enabled=false`でadditive migrationとread-only UIをdeployし、merge済みcommit ReviewをPASSする。
3. production credential preflightをread-only/token取得で確認する。
4. 管理者限定canaryで一件送信し、source/signed/certificate hashとCloudSign UIを人手照合する。
5. G1〜G6を閉じてから対象roleを段階開放する。

kill switch は新規 queue、dispatch、poll、cancel を停止する。既存ローカル PDF/download と結果不明一覧は維持する。rollback 時に provider document を自動削除/取消しない。queued/processing/reconciliation 行を export し、CloudSign 管理画面と照合後に再開する。

## 13. 実装前に必ず実測する blocking decision

| ID | 未確定事項 | 実測方法 | 未確定時の動作 |
|---|---|---|---|
| HFP-02-BLK-01 | sandbox の申請/plan/client ID owner | 公式 sandbox で token と一件作成 | 本番 enable 不可 |
| HFP-02-BLK-02 | CREATE timeout 後の operation marker 一意照合 | response 切断を注入し、GET list 全pageで marker 照合 | 自動再開せず人手 reconciliation |
| HFP-02-BLK-03 | upload/participant/send 反映遅延と GET 照合 | 各 mutation 後 0/数秒/10秒超で GET | mutation 自動 retry 禁止 |
| HFP-02-BLK-04 | signed file/certificate の content-type、bytes、file ID | sandbox 締結後に両 endpoint を取得 | artifact 回収を PASS にしない |
| HFP-02-BLK-05 | scanner と文書台帳の本番 readiness | EICAR相当の安全な検査fixture、storage partial failure | CloudSign enable 不可 |
| HFP-02-BLK-06 | 取消 API を UI 提供するか | 業務責任者が`ADOPT`または`NOT_ADOPT`を署名決定。`ADOPT`はsandbox取消、`NOT_ADOPT`はroute非公開とstatus=3受信mappingを実測 | 未決時はcancel endpoint/buttonを公開しない |

未決事項を AI が推測で埋めてはならない。未確定の機能だけを feature flag で閉じ、`review-ledger.md` に BLOCKED と再開条件を残す。
