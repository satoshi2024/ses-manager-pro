# CloudSign 公式契約・現行実装 調査記録

## 1. 調査メタデータ

- 調査日: 2026-08-12（Asia/Tokyo）
- 対象: CloudSign Web API と repository の契約書・電子署名実装
- 方針: 外部仕様は CloudSign / 弁護士ドットコムの公式資料と公式 SwaggerHub 定義だけを事実根拠とする。community/blog は契約根拠に使用しない。
- 制約: 調査時点では sandbox credential を使用した E2E は未実施。sandbox でしか確認できない項目は `HFP-02-BLK-*` として残す。

## 2. 固定した公式資料

| Evidence ID | 資料 | URL | 確認内容 |
|---|---|---|---|
| HFP-02-EV-001 | クラウドサイン Web API 利用ガイド | https://help.cloudsign.jp/ja/articles/2681259 | 認証、host、標準送信順、endpoint、rate limit、直列呼出し、504/反映遅延 |
| HFP-02-EV-002 | クラウドサイン Web API 紹介 | https://help.cloudsign.jp/ja/articles/936884 | 公式仕様書の所在と対象plan |
| HFP-02-EV-003 | 公式 OpenAPI | https://api.swaggerhub.com/apis/CloudSign/cloudsign-web_api/0.36.0/swagger.json | request/response schema、media type、status/error |
| HFP-02-EV-004 | Webhook 機能 | https://help.cloudsign.jp/ja/articles/417935 | 締結/取消・却下/メール不達の通知条件 |
| HFP-02-EV-005 | Webhook 実行時の挙動 | https://help.cloudsign.jp/ja/articles/9977727 | body/status/retry、公開されている送信元/HTTP契約 |
| HFP-02-EV-006 | 締結済み書類 download | https://help.cloudsign.jp/ja/articles/6009061 | 締結済み PDF の保存・検証用途 |

### 2.1 OpenAPI pin

- `info.version`: `0.36.0`
- 取得 URL: `https://api.swaggerhub.com/apis/CloudSign/cloudsign-web_api/0.36.0/swagger.json`
- 取得日: 2026-08-12
- response `Last-Modified`: 2026-08-04 09:40:39 GMT
- raw response length: 147111 bytes
- raw response SHA-256: `f832681318e67b9fb5fe9a0bb368a570762401dcd4a62b98a934deebb192a240`

実装着手時と HFP-02-09 sandbox E2E 前に再取得する。version または SHA が変わったら、固定 fixture/typed DTO/error mapping の差分を Review するまで provider adapter の更新を止める。

## 3. 公式契約で確認済みの事実

### HFP-02-EV-F01 — 認証は OAuth ではない

- client ID を `application/x-www-form-urlencoded` の `client_id` として `POST /token` へ送る。
- response は `access_token`, `expires_in`, `token_type`。公式説明では access token は発行から 3600 秒有効。
- API call は `Authorization: Bearer ...`。
- client ID は利用者ごとに発行され、漏えいすると成りすましにつながると公式ガイドが警告している。

したがって、旧 task の「OAuth」は誤りである。authorization code/redirect/refresh token を作らず、client ID secret と短寿命 token provider を実装する。

### HFP-02-EV-F02 — environment host

- production: `api.cloudsign.jp`
- sandbox: `api-sandbox.cloudsign.jp`
- sandbox は本番から完全分離され、利用には申込みが必要。本番データ/テンプレート/ユーザーは移行されない。

prod で任意 base URL を受け入れる設計は SSRF/credential leakage risk になるため、環境 enum と host allow-list に固定する。

### HFP-02-EV-F03 — 入力項目なし PDF の標準フロー

公式利用ガイドが示す順序は次のとおり。

1. `POST /token`
2. `POST /documents`
3. `POST /documents/{documentID}/files`
4. `POST /documents/{documentID}/participants`
5. `POST /documents/{documentID}`

書類 (`document`) は file、participant、widget 等を含む送信単位であり、`POST /documents` だけでは送信準備が完了しない。

### HFP-02-EV-F04 — request media type

OpenAPI 0.36.0 で確認した最小契約:

| endpoint | media type / 主な field |
|---|---|
| `POST /token` | form-urlencoded: `client_id` required |
| `POST /documents` | form-urlencoded: `title`, `note`, `message`, `template_id`, `can_transfer`, `private` |
| `POST /documents/{id}/files` | multipart/form-data: `name`, binary `uploadfile` |
| `POST /documents/{id}/participants` | form-urlencoded: `name`, `email`, `organization`, `access_code`, `language_code` 等 |
| `POST /documents/{id}` | 送信または、status=1 の場合は reminder |
| `PUT /documents/{id}/decline` | form-urlencoded: `comment`（最大1000） |

participant schema の `required` 配列は name のみだが、400説明は email/name の空・不正を error としている。単一宛先送信では name/email を必須とし、sandbox で wire behavior を確認する。

### HFP-02-EV-F05 — file 制約

OpenAPI 0.36.0 の upload error 契約:

- PDF 先頭 `%PDF-`、末尾 `%%EOF` が必要。
- file 合計 200MB / 100件、HTTP request body 50MB 超は error。
- PDF 以外または multipart でない場合は 415。
- file name の `"`、末尾 `\` 等は拒否される。

本 spec は一送信一PDFとし、自システム上限と公式上限の小さい方を事前適用する。上限値をコードへ重複散在させず config/validation に集約する。

### HFP-02-EV-F06 — status

`documentModel.status`:

| 値 | 意味 |
|---:|---|
| 0 | 下書き |
| 1 | 先方確認中 |
| 2 | 締結済 |
| 3 | 取消または却下 |
| 4 | テンプレート |

schema description は imported document `13` にも言及する一方 enum は 0〜4 のため、typed client は未知整数を parse 可能にし、自動成功扱いせず `要確認` にする。

participant にも別の status enum がある。document status と混用しない。

### HFP-02-EV-F07 — signed PDF と証明書

- `GET /documents/{documentID}/files/{fileID}` は締結済み書類の PDF を自社 file server へ保存する用途として公式に説明される。
- `GET /documents/{documentID}/certificate` は合意締結証明書を PDF として返す。status が締結済みでなければ 404 契約。
- source PDF、signed PDF、certificate は別 artifact であり、同じ path/hash へ上書きする根拠はない。

### HFP-02-EV-F08 — rate/順序/結果不明

公式利用ガイド:

- 同一 access token は 1分 800 request 超で 429、1分の停止期間。
- 複数 request は一つ前の response を受けてから送る。並行すると競合し正常処理されない場合がある。
- API は最大180秒接続を維持し、超過時は 504 を返すが、**504返却後も CloudSign 側処理が継続する場合がある**。
- create/update/delete 後の反映に時間を要し、次 request 前に数秒〜10秒程度の待機が推奨される。

このため mutation timeout/504 を通常の retryable failure に分類してはならない。`結果不明 → GET/一覧/人手照合` が必須である。

### HFP-02-EV-F09 — provider idempotency は契約されていない

固定 OpenAPI 0.36.0 全文で `idempotency`, `request-id`, `correlation` に該当する request 契約は確認できなかった。したがって provider がこれらを保証する前提にしない。

これは「provider 内部に絶対存在しない」という断定ではなく、**公開契約として依存できない**という判断である。将来公式契約へ追加された場合だけ、version diff と sandbox test 後に利用する。

### HFP-02-EV-F10 — Webhook は本 spec の一次経路にしない

公式 Webhook は status 2/3 と email bounce を POST し、5xx の場合10分間隔で最大3回再送する。公開資料には body signature または shared-secret の検証契約が見当たらない。

本 spec は polling/manual sync を正とする。Webhook を追加する場合は別 decision で送信元検証、replay、重複、payload照合、poll reconciliation を設計し、Webhook body だけで artifact を確定しない。

## 4. repository 現状調査

### 4.1 実装済み

- `V20__contract_document_esign.sql`: template/document table と menu。
- `ContractDocumentServiceImpl#create`: template/contract 参照、sanitization、CJK PDF、source SHA、local storage。
- `ContractDocumentApiController`: list/create/send/sync/download と親契約 scope。
- `ContractDocumentServiceImpl`: file security metadata、外部 artifact scan hook、法定文書台帳登録 hook。
- UI: template作成、local document作成、send/sync/download。
- tests: source PDF metadata、external signed PDF scan、download fail-closed、legacy backfill の一部。

「API controller しかない」のではなく、local PDF/UI/DB/security の骨格はある。ただし provider 閉ループは未完成である。

### 4.2 確認した defect

| Finding ID | evidence | 影響 |
|---|---|---|
| HFP-02-FND-001 | `CloudSignClientImpl#send` は JSON `{title,name,email}` を `/documents` へ送るだけ | 公式media type/工程と不一致、source PDF未upload |
| HFP-02-FND-002 | `cloudsign.token` を静的設定 | 1時間token契約と不一致、更新不能/secret運用不備 |
| HFP-02-FND-003 | `ContractDocumentServiceImpl#send` は外部成功後にDBへID/state保存 | 二重クリック、response喪失、DB失敗で重複/孤児 |
| HFP-02-FND-004 | `sync` は `@Transactional` 内でprovider GET/file download | 長時間transaction、lock/rollbackと外部副作用混在 |
| HFP-02-FND-005 | `sync` はfileを書いてscan/ledger後にdocument更新 | file/metadata/DB partial failureの復旧不十分 |
| HFP-02-FND-006 | signed PDF取得例外を空catch | 締結済みなのにfile未取得を成功に見せる |
| HFP-02-FND-007 | `CloudSignClient.Result` がstatusと2つの`byte[]`を混在 | API責務混在、無制限memory、certificate常時null |
| HFP-02-FND-008 | signed PDF取得後に `pdfSha256` を上書き | 送信原本hashを失い、送付原本の同一性を証明不能 |
| HFP-02-FND-009 | external contract PDF scan に `FileKind.SKILL_SHEET` | 誤った許可種別/size契約 |
| HFP-02-FND-010 | certificate path は `.dat`、ledger content typeもoctet-stream | 公式のcertificate PDF契約と不一致 |
| HFP-02-FND-011 | polling schedulerなし、UI manual syncのみ | 状態/artifactが自動収束しない |
| HFP-02-FND-012 | send buttonはstate無関係、成功toastは即「送信しました」 | invalid operation/外部未送信の偽表示 |
| HFP-02-FND-013 | controller listが`ContractDocument` entityを返す | storage path/rendered HTML/internal error露出risk |
| HFP-02-FND-014 | downloadに明示no-store/attachment filenameなし | sensitive PDF cache/UX/監査不整合 |
| HFP-02-FND-015 | HRがPOST sync可能 | 「HR参照のみ」の業務境界と不一致 |
| HFP-02-FND-016 | provider wire/OAuth/token/sandbox E2E testなし | Mock greenでも本番契約を証明しない |

これらは `review-ledger.md` で task/AC/test/Demo と結び、説明だけで CLOSED にしない。

## 5. sandbox で未確認の事項

| Blocker ID | 確認内容 | 合格条件 | 未確認時 |
|---|---|---|---|
| HFP-02-BLK-01 | sandbox/plan/client ID owner | token、document作成・取得可能 | 本番enable禁止 |
| HFP-02-BLK-02 | CREATE response喪失後のmarker照合 | 全page検索で同operationが常に一件 | 人手照合、CREATE再送禁止 |
| HFP-02-BLK-03 | 各mutationの反映遅延/GET整合 | bounded wait後に一意に工程判定 | mutation再送禁止 |
| HFP-02-BLK-04 | signed/certificate bytes/content-type | 両方valid PDF、hash取得、ID対応 | artifact完了扱い禁止 |
| HFP-02-BLK-05 | scanner/storage/ledger readiness | failure injection含むfail-closed | 本番enable禁止 |
| HFP-02-BLK-06 | 取消機能の業務採用 | `ADOPT`: 業務承認＋自システム取消でsandbox status=1→3。`NOT_ADOPT`: route/UI非公開＋公式fixtureまたは受信者却下でstatus=3 mapping | 未決時はUI/API非公開 |

## 6. 採用しない推測

- CloudSign に OAuth authorization code/refresh token がある、という推測。
- `POST /documents` に name/email を同梱すれば宛先登録・送信まで終わる、という推測。
- mutation timeout は未処理なので同じ request を retryしてよい、という推測。
- 独自 `Idempotency-Key` / correlation header が重複を防ぐ、という推測。
- status文字列が日本語で返る、または status=2 の response に signed PDF/certificate byte が同梱される、という推測。
- Webhook body が署名済みなので polling 不要、という推測。
- certificate が任意binary `.dat` である、という推測。

実装中に新たな未確認事項が見つかった場合、この節へ追加し、公式資料または sandbox で解消するまで安全側に停止する。
