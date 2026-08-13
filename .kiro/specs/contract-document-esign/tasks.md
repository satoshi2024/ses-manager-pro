# 契約書・CloudSign 本番署名閉ループ タスク

## 実行規約

- task ID は変更・再利用しない。分割が必要な場合は `HFP-02-XX-A` のように suffix を追加し、元 task を親として残す。
- `requirements.md`、`design.md`、`research.md` と repository root `AGENTS.md` を正とする。
- 一つの task は **実装、指定 test、Demo、証跡、独立 review** が揃った場合だけ `- [x]` にする。コードだけ、Mock だけ、説明だけでは完了にしない。
- 外部環境、Docker、Node、scanner、credential が無い場合は `BLOCKED`。skip や未実行を PASS にしない。
- 既存 V20 migration は編集しない。migration 番号は task 着手時の merge 済み latest + 1 を再確認する。
- provider mutation timeout/504 を再試行する実装を見つけた時点で作業を止め、HFP-02-04 の設計へ戻す。
- 変更は本 task の acceptance へ直接 trace する最小範囲に限定し、ついでの refactor を行わない。
- 各 task の着手/完了は `review-ledger.md` に同じ ID で一行追加する。

## 既存実装済み baseline（再実装しない）

| baseline | 実装 evidence | 残る差分 |
|---|---|---|
| local template/PDF/hash | `ContractDocumentServiceImpl#create`, `m_contract_template`, `t_contract_document` | source hash 不変化、送信前再検証 |
| page/API | `ContractDocumentApiController`, `contract-document/list.html`, `contract-document.js` | DTO/no-store/状態別UI/本当のqueue |
| scope/role | controller の DataScope/OrganizationScope/PreAuthorize | HR manual sync禁止、artifact全経路統一 |
| file security/ledger | `FileSecurityMetadata`, `DocumentService`, `register*ToLedger` | 正しい FileKind、atomic artifact、三hash |
| provider skeleton | `CloudSignClientImpl` | token、4工程、typed client、reconciliation |

## タスク

- [ ] **HFP-02-00 — 公式契約固定と sandbox 調査 gate**
  - **依存**: なし。
  - **Objective**: 実装 AI が endpoint/payload/status/認証を推測できないよう、公式 OpenAPI と sandbox 実測計画を固定する。
  - **対応要件**: HFP-02-REQ-01、HFP-02-REQ-02、HFP-02-REQ-11。
  - **実装ガイダンス**:
    1. `research.md` の公式 URL から OpenAPI を再取得し、version/SHA/Last-Modified を記録する。固定版が更新されていたら diff を保存し、破壊/追加変更を分類する。
    2. `/token`、送信4工程、status/file/certificate、error/status schema の最小 fixture を、実値を不可逆マスキングして `src/test/resources/cloudsign/` 等へ用意する。credential/PDF実体/メールを commit しない。
    3. sandbox 利用申請、plan、client ID owner、送信/受信テストアカウント、受信メール操作担当を確認する。
    4. HFP-02-BLK-01〜06 を `review-ledger.md` に OPEN/PASS/BLOCKED で登録する。
  - **対象ファイル/方法**: `.kiro/specs/contract-document-esign/research.md`, `review-ledger.md`、新規 contract fixture。production code は変更しない。
  - **テスト要件**: fixture が固定 OpenAPI の必須 field/media type/status/error と一致する schema test。OpenAPI 取得失敗や SHA 差異を成功扱いしない。
  - **Demo**: 公式 sandbox host に対する `POST /token` と read-only/preflight 呼出しを行い、secret を隠した request/response evidence を提示する。
  - **証跡**: OpenAPI version/SHA/diff、sandbox申請/権限（秘密を除く）、fixture test結果、blocking decision表。
  - **失敗/ロールバック判定**: sandbox/公式 schema を取得できなければ BLOCKED。古い blog/community endpoint で代替しない。HFP-02-03以降の wire契約を確定扱いしない。

- [ ] **HFP-02-01 — 既存挙動の characterization と失敗再現**
  - **依存**: HFP-02-00 の公式 schema 固定。sandbox の締結完了は未依存。
  - **Objective**: 現在の偽greenをテストで再現し、修正後の回帰防止基準を作る。
  - **対応要件**: HFP-02-REQ-03、04、07、08、11。
  - **実装ガイダンス**:
    1. 現行 `send()` が source PDF bytes を送らず JSON一回で成功扱いすることを失敗テストにする。
    2. 二重 send、CREATE response 後 DB update 失敗、sync transaction 内外呼出し、file download 例外握り潰しを再現する。
    3. `pdfSha256` 上書き、証明書 null、`FileKind.SKILL_SHEET` 誤用、entity/path/error露出、download cache header 欠落をテストで固定する。
    4. 既存正常系（local PDF生成、scope、font、ledger）を壊さない characterization も追加する。
  - **対象ファイル/方法**: `ContractDocumentServiceImplTest`, 新規 `CloudSignClientContractTest`, `ContractDocumentApiControllerTest`, 必要な fixture。
  - **テスト要件**: 修正前に各 defect ID が意図した理由で red になることを evidence に残す。Mock が核心 external call/transaction/hash を迂回しない。
  - **Demo**: 旧実装へ同時 send を二回与え、重複 risk をテストログで示す。secret/PII は使わない。
  - **証跡**: test method → baseline finding → AC の対応表、修正前 failure、既存正常系 result。
  - **失敗/ロールバック判定**: defect を再現できない場合は production code を先に変更せず、前提/fixtureを見直す。

- [ ] **HFP-02-02 — additive schema、状態 CAS、legacy backfill**
  - **依存**: HFP-02-01。migration 予約競合が無いこと。
  - **Objective**: source/signed/certificate の不変証跡と durable dispatch/reconciliation を DB で表現する。
  - **対応要件**: HFP-02-REQ-04、05、07、12。
  - **実装ガイダンス**:
    1. `design.md` §5 の field を必要最小限で `t_contract_document` に追加し、operation ID/payload hash/state/version の index/unique/CAS 条件を確定する。
    2. 既存 `pdf_sha256` は source hash の意味を固定し、signed/certificate hash/archive ID を別列にする。
    3. legacy shape を NONE/RECONCILIATION_REQUIRED/締結artifact移行候補/finding に分類し、外部再送を一切行わない backfill を実装する。
    4. `t_contract_document` は V20 で導入され V1 に存在しないため V1 へ追加しない。latest+1 Flyway、`schema-contract-document-h2.sql`、必要な統合H2、entity、fresh/legacy MySQL smoke/repairを同期し、適用済み V20 は編集しない。
  - **対象ファイル/方法**: 新規 Flyway、H2 schema、`ContractDocument`、Mapper/CAS methods、migration smoke/repair tests。`V1__create_tables.sql` は変更禁止。
  - **テスト要件**: fresh/legacy/partial/backfill矛盾/failed-history repair、unique/CAS、source hash 非上書き、既存件数/外部ID/path reconciliation。
  - **Demo**: V20相当のDBコピーを migrate し、件数・source hash・path・external ID の差分0、曖昧行が送信されず結果不明一覧に出ることを示す。
  - **証跡**: migration version/checksum、5形状結果、before/after reconciliation SQL（read-only）、rollback runbook。
  - **失敗/ロールバック判定**: latest version 競合、legacy shape 不明、backfill で一意判定不能なら採番/DDLを変更せず停止。rollback は additive列を即DROPせず feature flag off。

- [ ] **HFP-02-03 — Token Provider と typed CloudSign API client**
  - **依存**: HFP-02-00、HFP-02-01。HFP-02-02とは共有 entity を避ければ並行可。
  - **Objective**: 静的 token/Map client を廃止し、公式 wire契約を細粒度 typed method と error分類へ閉じ込める。
  - **対応要件**: HFP-02-REQ-01、02、03、09。
  - **実装ガイダンス**:
    1. `CloudSignProperties` と prod host/HTTPS/secret readiness validator を追加する。
    2. `POST /token` form request、expiry margin、JVM内single-flight、401一回再取得を `CloudSignTokenProvider` に実装する。OAuth/refresh token を作らない。
    3. `CloudSignApiClient` を create/upload/participant/get/send/decline/file/certificate に分離し、固定 OpenAPI の最小 DTO を作る。
    4. mutation は client 内 retry=0。GET/token だけ bounded retry policy を許可し、429/4xx/5xx/504/timeout を safe code に分類する。
    5. PDF upload は source bytes と同一であることを request capture で証明する。binary を `Result(byte[])` に混在させない。
  - **対象ファイル/方法**: `CloudSignClient.java`/`CloudSignClientImpl.java` の置換または互換 facade、新規 config/token/client/DTO、`AppConfig` のCloudSign専用timeout設定、4言語message。
  - **テスト要件**: form/multipart/header/順序、token single-flight/expiry/401、host allow-list、全error分類、mutation retry 0、schema必須field、log redaction。
  - **Demo**: local stub が受信した multipart PDF の SHA-256 と local source hash が一致し、token request が一回、変更 timeout 後の呼出し回数も一回であることを示す。
  - **証跡**: captured wire summary（token/PII除外）、test結果、config matrix、dependency追加理由。
  - **失敗/ロールバック判定**: 公式 schema と sandbox で media type/field が一致しない場合は推測修正せず HFP-02-00 へ戻る。旧 static token fallback を prod に残さない。

- [ ] **HFP-02-04 — durable dispatch、工程 checkpoint、結果不明 reconciliation**
  - **依存**: HFP-02-02、HFP-02-03。
  - **Objective**: 二重クリック、複数worker、process crash、provider 504でも外部書類を一件に保つ。
  - **対応要件**: HFP-02-REQ-03、04、05、09。
  - **実装ガイダンス**:
    1. send API を external同期呼出しから `queueSend()` の状態CASへ変更し、canonical payload hash/operation IDを永続化する。
    2. worker は CREATE/UPLOAD/PARTICIPANT/PREFLIGHT/SEND を一工程ずつ実行し、provider response 後に短い checkpoint transaction を commit する。
    3. external call 中に transaction active でないことをコード構造とtestで保証する。
    4. 各 mutation timeout/crash を `design.md` §6.3どおり照合する。CREATE ID不明の marker 自動照合は HFP-02-BLK-02 PASS時だけ有効化する。
    5. stale claim、重複候補、payload変更、CAS失敗を握り潰さず reconciliation finding にする。
  - **対象ファイル/方法**: `ContractDocumentService`, `ContractDocumentServiceImpl`, 新規 dispatch worker/service/transaction helper/reconciliation service、Mapper CAS、send controller。
  - **テスト要件**: 2/25/100同時send=operation/provider create各1、各工程crash、accepted-then-timeout、stale claim、payload hash change、CAS commit順反転、transaction inactive assert。
  - **Demo**: stub provider がCREATE処理後に504を返す。workerがCREATEを再送せず結果不明に停止し、照合後だけ同一document IDで次工程へ進むことを示す。
  - **証跡**: provider endpoint call count、operation history、DB state transition、transaction assertion、reconciliation操作監査。
  - **失敗/ロールバック判定**: 外部一件を証明できない結果不明を自動解除しない。kill switchでdispatchを止め、外部書類を自動削除しない。

- [ ] **HFP-02-05 — provider status mapping、polling、manual sync、取消境界**
  - **依存**: HFP-02-03、HFP-02-04。
  - **Objective**: 送信後の状態を競合なく収束させ、terminal逆戻りや意図しない reminder を防ぐ。
  - **対応要件**: HFP-02-REQ-05、06、09。
  - **実装ガイダンス**:
    1. provider status 0/1/2/3/4/未知を central mapper へ集約する。
    2. active row を上限件数/古い順で取得する polling scheduler を ShedLock 付きで実装し、provider GET をDB transaction外、保存をversion CASにする。
    3. manual sync も同じ service/mappingを使い、HRは参照のみのため更新操作を許可しない。
    4. 429/5xx/GET timeout のbounded backoff、4xx恒久分類、last success/滞留/結果不明metricsを追加する。
    5. HFP-02-BLK-06 を業務責任者が`ADOPT`または`NOT_ADOPT`で閉じる。`ADOPT`なら取消UI/APIを実装し、`NOT_ADOPT`ならroute/buttonを追加せず直接要求も更新0にする。未決時はfeatureを閉じたままBLOCKEDとする。
  - **対象ファイル/方法**: 新規 sync service/poll scheduler/status mapper、`ContractDocumentApiController`, Mapper query、ShedLock設定、metrics/messages。
  - **テスト要件**: status全値/未知、terminal逆戻り、manual vs scheduler commit反転、ShedLock、batch一件失敗継続、rate budget、reminder非発火、HR拒否。取消は`ADOPT`時のrole/CSRF/timeout、または`NOT_ADOPT`時のroute/UI非存在を相互排他的に検証する。
  - **Demo**: status 1→2 と、`ADOPT`なら自システム取消、`NOT_ADOPT`なら公式fixtureまたは受信者却下による1→3をpollで反映し、同時manual syncでも一方向に収束する。poll停止alertも意図的に発火する。
  - **証跡**: scheduler lock、status timeline、metric/alert screenshotまたはtest evidence、role結果。
  - **失敗/ロールバック判定**: polling host/credential不良時は新規送信もreadinessで止める。未知statusを既知へ丸めない。

- [ ] **HFP-02-06 — 署名済みPDF・証明書の安全回収と三hash**
  - **依存**: HFP-02-02、HFP-02-03、HFP-02-05。
  - **Objective**: source/signed/certificate を混同せず、検疫・台帳・hash・downloadまで閉じる。
  - **対応要件**: HFP-02-REQ-07、08、09。
  - **実装ガイダンス**:
    1. `FileKind.CONTRACT_PDF` 相当を追加し、size/MIME/magic/EOFを source/signed/certificate に適用する。`SKILL_SHEET` を代用しない。
    2. status=2 かつ送信時 file ID 一致後だけ signed file と certificate を streaming temp quarantine へ取得する。
    3. scanner CLEAN後に `DocumentService` のatomic storage/ledgerを使い、archive IDと別hashをCAS保存する。
    4. 同一artifact再取得同hash=no-op、相違hash=旧版保持+finding。`pdfSha256` は変更しない。
    5. source/signed/certificate downloadを分け、scope/no-store/Content-Disposition/監査を統一する。legacy pathは安全なread/backfillのみ。
  - **対象ファイル/方法**: `FileKind`, `ContractDocumentServiceImpl` のartifact部分、`DocumentService`連携、artifact DTO/controller、file reference/scope provider、ledger registration。
  - **テスト要件**: 三hash、正/偽PDF、size、CLEAN/INFECTED/UNAVAILABLE、途中storage/DB失敗、同hash/相違hash、path traversal、scope/no-store/audit。
  - **Demo**: 三PDFを取得し、source hashが作成時と同じ、signed/certificateが別hash・別台帳type・別download名であることを示す。scanner停止時は公開されない。
  - **証跡**: hash表、文書台帳/metadata行、scan result、download headers、orphan cleanup evidence。
  - **失敗/ロールバック判定**: scanner/document ledger/storageのどれかが unavailable なら artifact を公開せず、本番 enable不可。既存fileを上書き/即削除しない。

- [ ] **HFP-02-07 — DTO、権限、監査、状態駆動UI**
  - **依存**: HFP-02-04、HFP-02-05、HFP-02-06。
  - **Objective**: backendの正しい状態/操作を漏えいのないAPIと誤操作しにくいUIで提供する。
  - **対応要件**: HFP-02-REQ-03、08、10。
  - **実装ガイダンス**:
    1. list/detail/operation/artifact allow-list DTOを導入し、entity/path/renderedHtml/raw errorを除外する。
    2. template CRUD、create/send/sync/cancel/downloadのrole matrixと親契約scopeをAPIで統一する。HR manual sync/send/cancelは禁止。
    3. list/detail/downloadをno-store、artifactをattachment、send/sync/cancel/download成功/拒否を監査する。
    4. send確認modalに契約番号/source hash prefix/宛先/言語を表示し、queue受付とprovider送信完了を区別する。
    5. 状態別button、結果不明runbook、三artifact availabilityをdesktop/390pxで実装し、4言語i18nを同期する。
  - **対象ファイル/方法**: controller/DTO/page/JS/messages 4 bundles、ActionPermissionResolver/permission test（必要な最小差分のみ）。
  - **テスト要件**: 5 role×全endpoint、scope外404、CSRF、DTO field allow-list、no-store/download headers/audit、JS syntax、XSS、desktop/390px。
  - **Demo**: 管理者/営業/HR/マネージャー/要員で同一書類を確認し、許可操作・直接API・scope・結果不明表示・artifact downloadがmatrixどおりであることを示す。
  - **証跡**: role/API表、browser screenshot、response field/header、audit行、i18n consistency/JS check。
  - **失敗/ロールバック判定**: button非表示だけでAPIが通る、entity/path/raw errorが返る、download監査/no-store欠落ならFAIL。

- [ ] **HFP-02-08 — 全自動test、migration smoke、偽green排除**
  - **依存**: HFP-02-01〜07。
  - **Objective**: requirement/ACごとの自動証拠を揃え、外部/環境skipを可視化する。
  - **対応要件**: HFP-02-REQ-01〜12。
  - **実装ガイダンス**:
    1. HFP-02-01のred testをすべてgreen化し、各ACをtest methodへtraceする。
    2. provider contract/concurrency/transaction/file/security/controller/migration test matrixを埋める。
    3. log captureでsecret/PII/binary漏えいを攻撃的に検査する。
    4. `mvn test`だけでなく `scripts/verify-like-ci.ps1` を実行し、Docker/Node skipを明記する。
  - **対象ファイル/方法**: 全HFP-02 test、migration smoke/repair、`review-ledger.md`。production codeのついでrefactorは行わない。
  - **テスト要件**: requirements.md §4の各ACに最低一つの自動またはsandbox/manual evidence。mutation timeout call-count=1 は必須P0。
  - **Demo**: 意図的に二重send、malformed PDF、unknown status、scanner unavailable、scope外、token漏えい文字列を注入し、すべて安全側で失敗することを示す。
  - **証跡**: test command/result/count/skip、coverage trace表、failure injection結果、CI相当log。
  - **失敗/ロールバック判定**: Docker/Node不可、skip>0、flake、test順依存はBLOCKED。テストを削除/緩和してgreenにしない。

- [ ] **HFP-02-09 — CloudSign sandbox 閉ループ・障害注入 E2E**
  - **依存**: HFP-02-00、HFP-02-08。sandbox credential/受信操作担当。
  - **Objective**: Mock では証明できないprovider挙動とartifact byteを実測する。
  - **対応要件**: HFP-02-REQ-01〜07、09、11。
  - **実装ガイダンス**:
    1. sandbox専用secretをrepo外から注入し、本番host/client IDを絶対に使わない。
    2. local PDF生成→queue→create→upload→participant→send→受信者締結→poll→signed/certificate回収を実行する。
    3. 二重クリック、token expiry/401、429（providerへ負荷をかけない合意済み方法）、CREATE/upload/participant/sendのresponse切断/timeoutを安全なproxy/stub境界で注入し、sandbox実体と照合する。
    4. HFP-02-BLK-02〜04 の結果をresearch/design/review-ledgerへ反映し、推測部分を閉じる。
  - **対象ファイル/方法**: sandbox E2E script/test（secret非保存）、evidence、`research.md`, `review-ledger.md`。実契約データを使わない。
  - **テスト要件**: 外部書類一件、file一件、participant一件、status 1→2、三hash、証明書PDF、mutation timeout後の外部件数。BLK-06=`ADOPT`なら自システム取消、`NOT_ADOPT`ならcancel非公開と公式fixtureまたは受信者却下によるstatus=3 mappingを実施する。sandbox cleanupは公式条件内で別途行う。
  - **Demo**: CloudSign sandbox UIのdocument IDとSES側operation/document/file ID、三hash、状態timelineを照合する。
  - **証跡**: マスキング済みE2E report、request timeline、provider UI screenshot（PII隠蔽）、artifact hash、外部件数。credential/tokenは残さない。
  - **失敗/ロールバック判定**: sandbox unavailable/plan不足/受信者操作不可はBLOCKEDで、本番代替禁止。期待と違うprovider挙動はcodeを推測変更せずHFP-02-00へ戻す。

- [ ] **HFP-02-10 — production readiness、post-merge canary計画、停止/復旧演習**
  - **依存**: HFP-02-08、HFP-02-09、全P0/P1 finding CLOSED。
  - **Objective**: merge前に本番準備と安全なcanary手順を完成し、merge後はfeature offのまま検証してから限定canaryへ進めるようにする。
  - **対応要件**: HFP-02-REQ-02、06、08、09、11、12。
  - **実装ガイダンス**:
    1. client ID owner/plan/FQDN/clock/timeouts/rate budget/scanner/storage/ledger/ShedLock/alert/retention/runbook/kill switchを運用者と確認する。
    2. merge前は`enabled=false`のdeployment/canary手順と自動preflightを完成する。実際のdeployはmerge後に行い、`enabled=false` deploy→merge済みcommit Review→readiness→管理者限定canary一件→CloudSign UI/三hash照合→role段階開放の順に固定する。
    3. queue中、SENDING中、結果不明、artifact回収中の各時点でkill switch/rollback演習を行う。
    4. rollback時の未処理export、人手reconciliation、再開承認を実演する。provider書類の自動削除/取消は禁止。
  - **対象ファイル/方法**: production runbook/config/monitoring、`review-ledger.md`。秘密値は成果物へ書かない。
  - **テスト要件**: readiness fail-closed、kill switch、alert delivery、restore/restart、複数instance、canary dry-run/rollback。`verify-like-ci` skip0の再確認。
  - **Demo**: merge前はsandbox一件の正常閉ループと別operationのSENDING中kill switch→結果照合→安全再開、およびproduction canary手順のdry-runを運用担当が実施する。実production canaryはmerge後G5で行う。
  - **証跡**: merge前は承認者/日時/checklist、sandbox IDs（safe）、三hash、monitor/alert、rollback drill、残finding=0。merge後G5へcanary ID/結果を追記する。
  - **失敗/ロールバック判定**: P0/P1未解決、sandbox未完了、alert/runbook/運用担当未承認なら本番enable禁止。task checkboxも付けない。

## 完了時の総合 gate

1. HFP-02-00〜10 がすべて evidence 付きで完了。
2. HFP-02-REQ-01〜12 / AC の trace に空欄なし。
3. `review-ledger.md` の P0/P1 OPEN/BLOCKED が0。
4. provider mutation timeout の全caseで同mutation call count=1。
5. sandbox で source/signed/certificate 三hashを取得し、post-merge canary手順とownerが確定している。
6. `verify-like-ci` failure 0 / error 0 / skip 0。
7. merge前の独立 Review AI が `review-conversation.md` に従って `REVIEWABLE`。

merge後は、feature offでmerge済みcommitとmerge delta、共有consumer、main上の直接回帰を独立ReviewしてG6を閉じ、その後に管理者限定production canaryでG5を閉じる。G1〜G6が全てPASSした場合だけ本specを最終`PASS`とする。merge前の`REVIEWABLE`を最終PASSとして転記しない。
