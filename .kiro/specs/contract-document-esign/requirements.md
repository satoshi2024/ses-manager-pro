# 契約書・CloudSign 本番署名閉ループ 要件

## 1. 目的と完了定義

契約情報から生成済みの PDF **そのもの**を CloudSign に送信し、宛先確認、送信、状態追跡、締結済み PDF・合意締結証明書の安全な保管と監査までを、再送・タイムアウト・複数インスタンスでも重複させず完結させる。

本 spec の「完了」は、Mock が成功することではない。公式契約を固定した自動テストに加え、CloudSign サンドボックスで `PDF生成 → 書類作成 → PDF追加 → 宛先追加 → 送信 → 締結 → 署名済PDF/証明書取得` が一度だけ成立し、運用開始条件と停止・復旧手順が承認された状態をいう。

## 2. 現在の境界

### 2.1 既存資産として維持するもの

- `m_contract_template` / `t_contract_document`、テンプレート置換、ローカル PDF 生成、CJK フォント埋め込み、SHA-256 算出。
- 契約単位の DataScope / OrganizationScope、ロール境界、CSRF、API 監査。
- ファイル検疫、法定文書台帳、ShedLock、外部 SaaS 用 `RestTemplate` の既存基盤。
- 管理者・営業・マネージャーによる作成/送信、HR の参照権限という業務境界。

既存実装済み部分を一から作り直さず、本番閉ループに不足する差分だけを追加する。

### 2.2 対象外

- CloudSign の入力項目（押印・フリーテキスト等）、並列署名、組込み署名、マイナンバーカード署名、共有先、受信者ファイルアップロード。
- SES Manager 独自の電子署名・タイムスタンプ生成、CloudSign 以外の署名事業者。
- Webhook を一次同期経路にすること。公式公開資料で署名検証契約が確認できないため、本 spec はポーリング＋手動同期を正とする。
- 外部 API 契約に無い独自ヘッダーや、未確認の検索・冪等機能への依存。

## 3. 優先度

- **P0**: 満たさないと誤送信、情報漏えい、契約原本喪失または重複契約を生む条件。
- **P1**: 本番リリースを止める条件。
- **P2**: リリース後の改善として残せるが、残置承認が必要な条件。

## 4. 要件

### HFP-02-REQ-01 — 公式 API 契約の固定（P0）

実装は CloudSign 公式 Web API の確認済みバージョンを唯一の外部契約とし、推測した endpoint、media type、status、認証方式を使用してはならない。

- **HFP-02-AC-01-01**: 実装開始時に公式 OpenAPI の version、取得 URL、取得日時、SHA-256 を `research.md` に固定する。
- **HFP-02-AC-01-02**: `POST /token`、`POST /documents`、`POST /documents/{documentID}/files`、`POST /documents/{documentID}/participants`、`POST /documents/{documentID}`、`GET /documents/{documentID}`、`GET /documents/{documentID}/files/{fileID}`、`GET /documents/{documentID}/certificate` の method、Content-Type、必須項目、成功/失敗 schema を契約テストにする。
- **HFP-02-AC-01-03**: 公式契約との差異、レスポンスの任意プロパティ追加、未知 status は、成功扱いで黙殺せず安全側の `要確認` として可視化する。
- **HFP-02-AC-01-04**: OpenAPI 更新時は固定版との差分レビューを行い、互換性を確認するまで本番 provider の更新を止める。

### HFP-02-REQ-02 — 資格情報・接続先・Token（P0）

CloudSign の現在の認証はユーザー OAuth ではなく、環境ごとの `client_id` で `POST /token` を呼び出して得る有効期限付き Bearer Token として実装する。

- **HFP-02-AC-02-01**: 本番 host は `https://api.cloudsign.jp`、sandbox host は `https://api-sandbox.cloudsign.jp` の allow-list から選択し、任意 URL、HTTP、userinfo、query/fragment 付き URL を prod で拒否する。
- **HFP-02-AC-02-02**: `client_id` と access token は secret として扱い、DB、レスポンス、監査詳細、例外、application log に平文保存しない。
- **HFP-02-AC-02-03**: Token は `expires_in` より安全余裕を引いてメモリ cache し、同一インスタンスの同時取得を single-flight 化する。401 後の再取得は一操作につき一回だけとする。
- **HFP-02-AC-02-04**: `cloudsign.enabled=true` なのに client ID、host、timeout、scanner、文書台帳が不備なら起動または readiness を fail-closed にする。
- **HFP-02-AC-02-05**: client ID 発行ユーザーが対象書類の作成・取得権限と契約プランを持つことを sandbox/preflight で証明する。

### HFP-02-REQ-03 — 実 PDF の送信準備と誤送信防止（P0）

送信対象は `ContractDocument.pdfPath` が指す生成済み PDF とし、タイトルだけの外部書類を「送信済み」にしてはならない。

- **HFP-02-AC-03-01**: 送信直前に PDF の存在、upload root 内の正規化 path、PDF magic/終端、size、保存済み原本 SHA-256 との一致を検査し、不一致時は外部 API を一度も呼ばない。
- **HFP-02-AC-03-02**: 公式順序 `書類作成 → PDF multipart upload → 宛先追加 → 書類送信` を直列実行し、各レスポンスを確認してから次へ進む。
- **HFP-02-AC-03-03**: CloudSign 書類 ID、file ID、participant ID を取得・保存し、作成した外部書類に送信元 PDF が一件、確認済み宛先が一件以上存在することを送信前に再確認する。
- **HFP-02-AC-03-04**: UI の最終確認には契約番号、文書 SHA-256 の短縮表示、宛先名、会社、メールアドレス、送信言語を表示し、ユーザーが明示確認した payload だけを queue する。
- **HFP-02-AC-03-05**: queue 後に宛先または原本が変わった場合は payload hash 不一致で送信を停止し、再確認を要求する。

### HFP-02-REQ-04 — 冪等・並行・結果不明の復旧（P0）

公式 OpenAPI に provider-side idempotency key の保証が無いことを前提に、同一ローカル文書の二重送信、孤児外部書類、タイムアウト後の盲目的再作成を防ぐ。

- **HFP-02-AC-04-01**: 送信受付は状態 CAS と永続 operation ID / payload hash により、二重クリック、並列 request、worker 再実行を同じ operation として扱う。
- **HFP-02-AC-04-02**: 外部変更 API は DB transaction 外で一つずつ呼び、各成功結果を短い transaction で永続化してから次へ進む。
- **HFP-02-AC-04-03**: 書類作成・upload・宛先追加・送信の timeout/504/接続切断は「失敗」ではなく `結果不明` とし、同じ変更 API を自動再実行しない。
- **HFP-02-AC-04-04**: document ID が既知なら GET で外部状態を照合する。document ID が不明な書類作成結果は、sandbox で証明した一意 marker 照合だけを自動利用し、証明できなければ `要確認` に停止して CloudSign 管理画面で人手照合する。
- **HFP-02-AC-04-05**: process crash 後の stale claim は、自動的に「未実行」へ戻さず `結果不明` の可能性を判定する。外部書類が一件であることを証明できた場合だけ再開する。
- **HFP-02-AC-04-06**: 重複または孤児を検出した場合は自動削除・自動送信せず、外部 ID、operation ID、発見日時を監査して運用者判断へ回す。

### HFP-02-REQ-05 — 状態機械（P0）

CloudSign の数値 status とローカルの業務状態・配送工程を分離し、許可された遷移だけを CAS で実行する。

- **HFP-02-AC-05-01**: CloudSign status `0=下書き`, `1=先方確認中`, `2=締結済`, `3=取消・却下`, `4=テンプレート` を明示 mapping し、送信対象では 4 を拒否する。未知値は `要確認` にする。
- **HFP-02-AC-05-02**: ローカル工程は少なくとも `未送信`, `送信待ち`, `外部準備中`, `先方確認中`, `締結済`, `取消・却下`, `結果不明`, `再試行待ち`, `恒久エラー` を区別する。
- **HFP-02-AC-05-03**: `締結済` と `取消・却下` は terminal とし、状態の逆戻り、締結後再送、送信 endpoint による意図しない reminder を拒否する。
- **HFP-02-AC-05-04**: `completedAt` は provider の確定情報を取得した時だけ設定する。同期失敗や証明書取得失敗を締結取消へ変換しない。
- **HFP-02-AC-05-05**: HFP-02-BLK-06 を `ADOPT` と決定した場合は、取消操作が公式の先方確認中条件、理由、二重確認、権限、監査を満たし、結果不明時に同じ安全規則を適用する。`NOT_ADOPT` の場合は cancel UI/API/route を公開せず、直接要求も更新を起こさないことを証明する。この二経路を混在させない。

### HFP-02-REQ-06 — 状態同期・ポーリング・手動同期（P1）

送信後の状態を、複数インスタンスでも一回だけ動く定期ポーリングと、権限付き手動同期の両方で回収する。

- **HFP-02-AC-06-01**: scheduler は ShedLock を持ち、active 状態のみを上限件数・古い順に取得し、request scope の principal/DataScope を暗黙利用しない。
- **HFP-02-AC-06-02**: status GET は DB transaction 外で行い、CAS で保存する。manual sync と scheduler の競合で状態を逆戻りさせない。
- **HFP-02-AC-06-03**: 429/5xx/network error は上限付き exponential backoff + jitter、4xx validation/permission は再試行せず運用エラーにする。
- **HFP-02-AC-06-04**: 公式が示す反映遅延を考慮し、変更直後に stale GET を根拠として変更 API を即再実行しない。
- **HFP-02-AC-06-05**: ポーリング停止、最終成功時刻、滞留件数、結果不明件数、429/5xx を監視可能にし、手動同期失敗を UI に安全な文言で表示する。

### HFP-02-REQ-07 — 署名済み PDF・証明書・三つの hash（P0）

送信原本、締結済み PDF、合意締結証明書を別物として保存・検証し、一つの hash/path で上書きしない。

- **HFP-02-AC-07-01**: `sourcePdfSha256`（既存 `pdfSha256` の論理名）、`signedPdfSha256`, `certificateSha256` を分離し、既存 `pdfSha256` は送信原本 hash として保持する。重複する source hash 列は追加しない。
- **HFP-02-AC-07-02**: status=2 を確認後、送信時に保存した file ID の PDF と `/certificate` の PDF を個別取得する。証明書を `.dat` や `application/octet-stream` 固定で扱わない。
- **HFP-02-AC-07-03**: 外部 PDF は quarantine → size/MIME/magic/終端検証 → `CONTRACT_PDF` 相当の正しい FileKind で malware scan → SHA-256 → 原子的保存 → DB/法定文書台帳登録の順で fail-closed に処理する。
- **HFP-02-AC-07-04**: 同じ provider artifact の再取得は同一 hash なら no-op、異なる hash なら既存版を上書きせず finding と新版を残す。
- **HFP-02-AC-07-05**: ファイル保存成功後の DB 失敗、DB 成功後の promote 失敗を既存 storage 補償規約で復旧し、部分ファイルを download 可能にしない。
- **HFP-02-AC-07-06**: source/signed/certificate を別 endpoint で選択 download でき、Content-Type、Content-Disposition、`Cache-Control: no-store`、scope、監査を全経路で統一する。

### HFP-02-REQ-08 — 認可・情報最小化・監査（P0）

- **HFP-02-AC-08-01**: template CRUD は管理者、文書作成/送信は管理者・営業・マネージャー、HR は list/detail/status/file 参照のみとし、直接 API でも強制する。取消はHFP-02-BLK-06=`ADOPT`の場合だけ同じ更新権限で提供し、`NOT_ADOPT`では全roleへ非公開とする。
- **HFP-02-AC-08-02**: list/detail/sync/download/cancel は親契約の DataScope と OrganizationScope を同じ母集団で検証し、scope 外は存在を漏らさない 404 とする。
- **HFP-02-AC-08-03**: API は entity を直接返さず allow-list DTO を使い、storage path、rendered HTML、client ID/token、provider raw body、内部 error/stack trace を公開しない。
- **HFP-02-AC-08-04**: list/detail と全 download は `no-store`。download は成功・拒否の両方、送信/同期と、提供する場合の取消は operation ID と結果を監査する。
- **HFP-02-AC-08-05**: log/監査は文書 ID、契約 ID、operation ID、provider document ID、safe error code に限定し、access code、token、PDF 本文、宛先メール全文を記録しない。
- **HFP-02-AC-08-06**: 送信・同期と、提供する場合の取消の更新 API は既存 Cookie CSRF を維持する。

### HFP-02-REQ-09 — エラー分類・可観測性・容量制御（P1）

- **HFP-02-AC-09-01**: 400/401/403/404/409/413/415/429/5xx/504/timeout を safe error code へ分類し、変更系の結果不明と確定失敗を混同しない。
- **HFP-02-AC-09-02**: 401 は token 再取得一回、429 は公式上限 800 request/token/min を超えない共通 limiter と retry-after 相当の待機、5xx/GET timeout は上限付き再試行とする。
- **HFP-02-AC-09-03**: PDF の件数/総量/HTTP body 上限は固定 OpenAPI と自システム上限の小さい方で事前拒否し、全 binary を application log や provider error message に展開しない。
- **HFP-02-AC-09-04**: alert は credential/permission failure、結果不明、stale queue、polling 停止、artifact hash change、scan unavailable/rejected、rate-limit 継続を区別する。

### HFP-02-REQ-10 — UI と運用者復旧（P1）

- **HFP-02-AC-10-01**: 状態に応じて許可される操作だけを表示し、下書き以外の送信、terminal 状態の再送、HR の更新操作を UI と API の両方で禁止する。
- **HFP-02-AC-10-02**: 送信は確認 modal → durable queue 受付までとし、外部処理完了を同期成功 toast で偽装しない。進行中、結果不明、要運用確認を明示する。
- **HFP-02-AC-10-03**: 結果不明画面は operation ID、既知の外部 ID、最終工程、最終確認時刻、安全な復旧手順を表示し、「もう一度送信」だけを提示しない。
- **HFP-02-AC-10-04**: desktop と 390px で宛先、状態、警告、download が判読・操作でき、色だけに依存しない。

### HFP-02-REQ-11 — 自動試験・sandbox E2E・運用 gate（P0）

- **HFP-02-AC-11-01**: 固定 OpenAPI fixture に対する request/response contract test、状態遷移、CAS concurrency、transaction 境界、timeout/504 reconciliation、token single-flight、log redaction を自動化する。
- **HFP-02-AC-11-02**: controller test は role matrix、scope 404、CSRF、DTO allow-list、no-store、download 監査を成功/拒否で検証する。
- **HFP-02-AC-11-03**: file test は source/signed/certificate の三 hash、magic/EOF、size、scan clean/infected/unavailable、atomicity、再取得同一/相違を検証する。
- **HFP-02-AC-11-04**: sandbox で正常閉ループ、二重クリック、401、429、変更 API timeout/結果照合、締結、status=3 mapping、署名 PDF・証明書 hash を実測する。HFP-02-BLK-06=`ADOPT`なら自システムの取消でstatus 1→3、`NOT_ADOPT`ならcancel UI/API非公開を検証し、公式fixtureまたは受信者の却下でstatus 3を確認する。credential 不足は `BLOCKED` であり PASS ではない。
- **HFP-02-AC-11-05**: MySQL fresh/legacy/partial/repair、H2 二系統、Node JS syntax、全 suite を `verify-like-ci` で skip 0 にする。
- **HFP-02-AC-11-06**: 本番 enable 前に client ID owner、plan、FQDN、clock、scanner、storage、ShedLock、alert、runbook、kill switch、rollback、データ保持を運用者が署名承認する。

### HFP-02-REQ-12 — 後方互換・停止・rollback（P1）

- **HFP-02-AC-12-01**: `t_contract_document` は V20 で導入され V1 に存在しないため V1 へ重複追加しない。既存 V20 を編集せず、実装開始時の最新 migration + 1、専用 H2 schema、必要な統合 H2 schema、entity、fresh/legacy MySQL smoke を同一 task で同期する。
- **HFP-02-AC-12-02**: schema は additive を基本とし、既存ローカル PDF と既存 CloudSign ID を失わず backfill 分類する。曖昧な既存行を自動再送しない。
- **HFP-02-AC-12-03**: `cloudsign.enabled=false` は新規 queue/dispatch/poll を停止する kill switch とし、ローカル PDF の参照を維持する。
- **HFP-02-AC-12-04**: rollback は外部書類を自動削除・取消しない。処理中/結果不明を export し、人手 reconciliation 後にのみ再開する。

## 5. リリース不可条件

以下の一つでも残る場合は、本番 CloudSign を有効化しない。

1. HFP-02 の P0/P1 acceptance に未検証または FAIL がある。
2. sandbox で実 PDF と証明書の byte/hash を証明できない。
3. 変更 API timeout 後に盲目的 retry する経路が残る。
4. access token/client ID/raw PDF/宛先 PII が log または API に漏れる。
5. scanner、法定文書台帳、ShedLock、alert、kill switch のいずれかが無い。
6. Docker/Node/provider sandbox test の skip を PASS としている。
