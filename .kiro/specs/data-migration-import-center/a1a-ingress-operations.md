# NF-06 A1a 顧客raw CSV ingress 運用・配備前提（W2-B1 硬化・W3-05 初回リリースAPI）

- 対象: `/api/import-center` の初回リリースAPI（下表）。W2-B1 で upload と job 詳細、W3-05 で検証・preview・error export・source/mapping 管理と本番 admission port を追加した。画面と menu は W3-12。
- 位置づけ: 2026-11-13 の本番公開判断（B-NF06-1〜5）と 2026-11-14 の Q-5・Q-15 を実装へ反映した結果と、配備・運用で守るべき前提をまとめる。
- 本文書は承認・署名を代替しない。Owner / Privacy / Security / DBA / SRE の確認事項は末尾に残す。

## 1. 初回リリースの範囲（B-NF06-3）

- 初回リリースに開放するのは upload・validation・preview（＋error export・source/mapping 管理）だけで、apply 系（apply・restart・rollback・compensation）は含めない。
- 公開 route（全て管理者 role ＋明示 action `import.metadata.manage`。`SecurityConfig` が `/api/import-center/**`・`/import-center/**` を管理者に静的限定し、service が操作ごとに再確認する）:

| route | 用途 |
|---|---|
| `POST /uploads` | raw CSV upload（§2〜§6） |
| `GET /jobs/{id}` | job 詳細（PII なし） |
| `POST /jobs/{id}/validations` | 検証の同期実行（§11） |
| `GET /jobs/{id}/preview` | 公開済み検証 run の preview（マスク済み、§11） |
| `GET /jobs/{id}/error-export` | REJECTED 行の finding code CSV（§11） |
| `GET /sources`・`POST /sources`・`PUT /sources/{id}` | 取込元の一覧・登録・有効/停止 |
| `GET /sources/{id}/mappings`・`POST /sources/{id}/mappings` | mapping 版の一覧・登録（版は不変、更新＝新しい版） |

- apply 系の入口は DENY 機構で閉じる（機能スイッチには依存しない）。
  - `/api/import-center/**` の上表以外の method・path は `ActionPermissionResolver` が action key を返さず、`MenuPermissionFilter` が管理者を含め 403 にする（fail-closed）。route 単位の閉鎖であり、role 別の action allow-list ではない（action 層は baseline＋deny のまま。新しい API root は追加していない）。
  - 本番 classpath の `ImportAdmissionPort` 実装は `ApprovedSourceImportAdmissionPort` だけ。署名済み Data Owner 決定 ID と承認 source 一覧（§10）が揃うまで、upload は body 読取前に 403 になる（Q-5）。
  - 本番 classpath に apply 実装 package は無い。
- 公開可否は機能スイッチ NF06（`app.features.nf06.enabled`、環境変数 `FEATURE_NF06_ENABLED`、既定 OFF）で別に制御する。OFF の間は `/api/import-center/**` が管理者を含め 404 になる。照合台帳の回収 batch（§6）は OFF でも止めない。
- 上記は `ImportCenterApplyEntryClosedTest` で固定している。apply を開放するバッチ（W3-B6）は、DG-06 と admission port の承認条件に合わせて同 test を更新すること。

## 2. 120秒総期限の意味（B-NF06-1）

| 項目 | 決定 |
|---|---|
| 起算点 | `ImportCsvIngestionService#upload` の最初の文。HTTP受信・servlet filter・controller までの時間は含まない |
| 上限の性質 | soft 上限。phase 境界と read/hash loop で期限を確認し、各操作は自身の timeout で打ち切る |
| 超過時の応答 | 503 `error.import.uploadDeadlineExceeded`（文言は4言語 bundle） |
| 超過時の状態 | commit 前なら stage を破棄し DB に何も残さない。COMMIT 結果が確定しない場合だけ stage を保全し、照合台帳から後で回収する（§6） |
| 硬い中断 | 後続課題（async servlet I/O、scanner の有界 Future 等）。本バッチでは実装しない |

- 単一の blocking 操作は中断できないため、最悪超過量はその操作の timeout 分になる。期限の外にある操作の一覧は `ImportIngressDeadline` の Javadoc を正とする。
- 1回の servlet read の上限は Tomcat `server.tomcat.connection-timeout`。本バッチで `application.yml` に既定 20 秒（環境変数 `TOMCAT_CONNECTION_TIMEOUT`）を設定した。
  - 従来は未設定で Tomcat 既定の 60 秒だった。keep-alive の待機は `server.tomcat.keep-alive-timeout` で従来どおり 60 秒（`TOMCAT_KEEP_ALIVE_TIMEOUT`）を明示し、LB の idle timeout との関係を変えない。
  - connection-timeout は全 request に効く。20 秒間1 byte も届かない request（request line・header・body の途中停止）は Tomcat が切断する。
- COMMIT 文自体と接続取得待ち（Hikari `connection-timeout` 既定 10 秒）は query timeout の対象外。JDBC `socketTimeout` は全 query に効くため本バッチでは設定せず、DBA 確認事項に残す。

## 3. 上限超過はエンドツーエンドで 413（B-NF06-4）

- filter（Content-Length が 64MiB 超）と service（chunked 等で filter を通過し、実 body が 64MiB 超）のどちらも HTTP 413 `error.import.uploadTooLarge` を返す。
- そのため共有の `SafeErrorPolicy.safeHttpCode` に 413 を加え、`GlobalExceptionHandler` の status 変換にも 413 を加えた。413 の既定文言は「送信データのサイズが上限を超えています。」。
- 他 API への影響（本バッチで調査済み）:
  - `ServiceRequestAttachmentService`（NF-02 添付）のサイズ超過・空 file は、従来 500 だったものが 413 になる。
  - Copilot transport の request 上限超過（`COPILOT_REQUEST_TOO_LARGE`）は、controller まで伝播する経路では 500 から 413 になる。
  - 旧 Gemini 経路は provider の 4xx を同じ code で返しており、provider が 413 を返した場合だけ 500 から 413 になる。
  - これら以外に 413 を投げる業務例外は無い。415・408・504 等の許可外 code は従来どおり 500 に丸める。

## 4. 別法人の同一内容は汎用 403（B-NF06-5）

- 取込 identity（tenant・source・entity・schema・source SHA-256）の一意制約は法人を含まない。そのため別法人の利用者が同じ内容を upload しても新しい job は作れない。
- 従来はここで 409 `error.import.jobIdentityConflict` を返しており、「同じ内容が別法人に存在する」ことを判定できる oracle になっていた。
- 本バッチから、同一 identity の job が別法人に属する場合は、認可 context の変化と同じ 403 `error.forbidden` で拒否する。
  - 対象は3経路: 再利用照会、一意性競合（409）後の readback、COMMIT 結果不明後の readback。
  - 後二者の stage は破棄する。別法人の job が identity を保持している以上、本 stage の job は commit され得ないため。
  - 同一法人内の条件不一致（encoding・source version・document hash）は、利用者自身のデータなので従来どおり 409 とする。
- F1a の `registerJob`（HTTP 入口なし）の重複時 409 は未変更。

**既知の残余リスク: 存在 oracle は 403 化の後も残る（N6-3、Q-15 で受容）**
- 403 化で消えたのは「409 という専用の応答」だけで、存在の判定そのものは消えていない。
- 判定できる者: 管理者 role ＋明示 action `import.metadata.manage` を持ち、承認 source（§10）へ upload できる利用者（別法人の利用者）。
- 判定方法: 同じ tenant・source・entity・schema へ、ある内容（bytes 完全一致）を upload する。
  - その内容が未登録なら upload は成功する。
  - 同じ内容の job が別法人に既にあれば、upload は 403 で失敗する。
  - この利用者は他の内容なら upload できる（認可されている）ことを知っているので、この 403 は「同じ内容が別法人に存在する」ことを示す。
- 403 の status・本文・message key は他の認可拒否と同一だが、上の比較で区別できるため、oracle は残る。
- 判定できるのは「完全に同じ bytes の存在」だけで、内容・job ID・法人名は返らない。既知の内容を推測して当てる場合（定型 CSV、少数行、他法人と共有した原本など）に限って意味を持つ。
- 消すには identity の一意制約へ法人を含める DDL が必要。Q-15 で「DDL はせず残余リスクとして受容し、文言を正確にする」と決定した（コード上の記載: `ImportMetadataService#findOrReuseExistingUpload`、`ImportCsvIngestionService#crossLegalEntityRejection` の Javadoc）。
- 運用上の緩和: upload できるのは管理者 role ＋明示 action ＋承認 source の三重の条件を満たす利用者だけ。拒否は admission と同じく監査に残る（`ApiAuditFilter` の POST 監査）。
- job ID からの oracle は無い: `GET /jobs/{id}`・検証・preview・error export は、別法人の job を存在しない job と同じ 404 で返す（検証は lifecycle へ渡す前に判定する。lifecycle の入力読取は法人不一致を 409 にするため）。

## 5. 配備前提: 透過再接続の禁止（B-NF06-5 / N-P3-2）

**禁止事項**
- 取込 metadata commit は、接続が切れれば COMMIT / rollback が必ず失敗することを前提に、失敗を次の2種類に分けている。
  - 未 commit 確定 → stage を破棄。
  - 結果不明 → readback のうえ保全。
- 透過再接続があると、commit 済み transaction に対する rollback が再接続先で「成功」してしまう。その結果、commit 済み metadata が参照する原本 object を補償削除する事故になる。
- したがって次を禁止する。
  - JDBC URL や data source property の `autoReconnect=true` / `autoReconnectForPools=true`。
  - ProxySQL・MySQL Router 等、client に見えない形で接続を張り替える透過再接続 proxy。
- 許可する構成: アプリ → Hikari → MySQL の直結、または接続断を client へそのまま返す（透過再接続しない）L4 LB。

**起動時の fail-fast**
- `ImportJdbcReconnectGuard` が次の property を検査する。
  - `spring.datasource.url`（`DB_URL` を含む）
  - `spring.datasource.hikari.jdbc-url`
  - `spring.datasource.hikari.data-source-properties.*`
- `autoReconnect` / `autoReconnectForPools` が `true` / `yes` なら起動を中止する。
- 例外文言には property 名だけを出し、URL・認証情報は出さない。
- 透過再接続 proxy はアプリから検出できないため、配備 checklist で DBA / SRE が確認する。

## 6. 結果不確定 stage の照合と回収（A-NF06-2）

**記録**
- 照合台帳 `ImportStageReconciliationLedger` に記録する対象は次の2つ。
  - COMMIT 結果が確定せず、readback でも本 stage の行が見えないため保全した stage（`OUTCOME_UNKNOWN`）。
  - 未 commit 確定なのに補償削除に失敗した stage（`DISCARD_FAILED`）。
- 台帳は upload root 直下の `import-stage-ledger/` に置く。stage 1件につき properties file を1つ作り、内容は tenant ID・storage key・operation ID・理由・記録時刻だけとする（CSV 本文・利用者情報は含めない）。
- 孤児清理（`FileCleanupServiceImpl`）は upload root・quarantine・published の直下だけを非再帰で走査し directory を対象にしないため、台帳 file は消されない。download API からも参照されない。

**回収**
- `ImportStageReconcileScheduler`（毎時17分、ShedLock `importStageReconcileHourly`）が `ImportStageReconciler` を呼ぶ。記録から1時間（`SAFETY_WINDOW`）を過ぎた台帳だけを判定する。
- 判定規則:
  - `t_document_version.storage_key` が同じ storage key を参照する版が1件でもある（tenant・論理削除を問わない）→ commit 済みと確定し、台帳だけを外す（object は文書台帳の管理下に残る）。
  - 参照が無い → 未 commit と確定し、object を補償削除してから台帳を外す。
  - DB 照会・削除の失敗 → 台帳を残して次回再試行する。
  - storage key が既定形式（32桁hex/UUID）でない台帳は自動削除せず、手作業の照合へ回す。
- `app.scheduling.enabled=false` の環境では動かない。多 instance で upload root が instance ごとの local directory の場合、lock を取らなかった instance の台帳は次に lock を取るまで残る（初回本番は単一 instance または sticky session が前提）。

**監視 metric**（いずれも tag は固定値だけで PII なし）

| metric | 意味 | アラートの目安 |
|---|---|---|
| `ses.import.stage.retained` | COMMIT 結果不明で保全した件数 | 増加したら DB 障害の有無を確認する |
| `ses.import.stage.discard.failures{path=winner}` | 別 winner を返す途中の補償削除失敗。応答は 500（B-NF06-5 で受容） | 1件でもアラート |
| `ses.import.stage.discard.failures{path=failure}` | 元の失敗を返す途中の補償削除失敗 | 1件でもアラート |
| `ses.import.stage.discard.failures{path=stage}` | stage 処理（scan 後の storage 書込・検証）の途中失敗時の補償削除失敗（N6-2、§12） | 1件でもアラート |
| `ses.import.stage.ledger.failures` | 台帳への記録失敗。自動回収されない | 1件でもアラートし、手作業で照合する |
| `ses.import.stage.reconciled{result=reclaimed\|committed\|failed}` | 照合結果 | `failed` が続けば調査する |
| `ses.import.raw.temp.cleanup.failures` | raw CSV 一時 file の削除失敗（既存） | 1件でもアラート |

**手作業 runbook**（台帳記録に失敗した場合、または形式外の台帳）
1. error log の `[取込] stage照合台帳への記録に失敗しました` または `[取込] COMMIT結果が不確定` から、operationId と storageKey を控える。log には storage key と operation ID だけが出る。
2. DB で `SELECT COUNT(*) FROM t_document_version WHERE storage_key=?` を実行する。
3. 1件以上ある → commit 済み。何もしない（台帳があれば削除する）。
4. 0件 → 文書 storage の `documents/quarantine/<key>` と `documents/published/<key>` を削除し、台帳 file があれば削除する。
5. 実施記録（時刻・storage key・判定・実施者）を運用記録へ残す。

## 7. message key（A-NF06-1）

- `error.import.*` 43件を `messages{,_en,_ko,_zh_CN}.properties` に追加した。各言語で個別に翻訳しており、日本語の流用はしていない。
- W3-05 で6件を追加した（同じく4言語で個別に翻訳）: `processingDeadlineExceeded`（検証・preview の 120 秒超過、503）、`validationInProgress`（409）、`mappingRequired`（400）、`validationNotCompleted`（409）、`auditUnavailable`（必須監査を記録できない、503）、`invalidPreviewRequest`（400）。
- guard filter（`ImportRawBodyTransportGuardFilter`）の拒否応答も message key を出さない。画面と同じ `LocaleResolver`（`SES_LOCALE` cookie、既定は日本語）で locale を決め、`SafeErrorPolicy` 経由で文言に解決する。

## 8. 試験

| 対象 | 試験 |
|---|---|
| 413・汎用403・照合台帳・winner アラート | `ImportCsvIngestionServiceTest`、`GlobalExceptionHandlerTest`、`ImportCenterApiSecurityTest` |
| 照合台帳と判定規則 | `ImportStageReconciliationLedgerTest`、`ImportStageReconcilerTest`（専用 H2）、`ImportCsvIngestionRealMySqlTest`（実 MySQL・KILL 注入からの回収と commit 済み保持） |
| 透過再接続の fail-fast | `ImportJdbcReconnectGuardTest` |
| apply 入口の閉鎖 | `ImportCenterApplyEntryClosedTest` |
| 実 Tomcat エンドツーエンド | `ImportRawUploadServletContainerE2ETest`：素の `text/csv`（UTF8・SHIFT_JIS）、parameter 付き 415、chunked + service 413。RANDOM_PORT のため TestIsolationAuditTest の allowlist に登録 |
| 本番 admission（Q-5） | `ImportAdmissionPortTest`（既定 deny・承認時の許可と必須監査・部分設定/改ざん/placeholder/失効/未承認 source）、`ImportAdmissionStartupValidatorTest`（NF06 OFF は起動継続、ON は構造的不備で起動失敗） |
| 検証編成 | `ImportValidationRunServiceTest`（H2：mapping 束縛→publish、冪等、別法人 404、進行中 409、lease 切れ takeover、期限超過時の abort と 503） |
| preview・error export | `ImportValidationResultServiceTest`（マスク・page 上限・disposition・別法人 404・未完了 409・監査必須・行 hash 不一致 409・CSV injection・page ごとの flush・途中の撤権） |
| source・mapping 管理 | `ImportCatalogServiceTest`（tenant 境界・CAS・版の不変・権限）、`ImportCenterApiSecurityTest`（CSRF・非管理者 403・未公開 route 403） |
| strict delete（N6-2） | `LocalDocumentStorageStrictDeleteTest`、`DocumentServiceImplTest`、`ImportCsvIngestionServiceTest`（stage 途中の補償失敗の台帳記録） |
| 実 MySQL | `ImportValidationRunRealMySqlTest`（upload→検証→preview→error export、job 行 lock 待ちの確認、takeover 後の旧 worker の seal の fence、同時開始で1 run だけ公開、未承認 source の拒否と監査）。mysql shard 3 |

## 9. 未決・外部確認事項

- 硬い期限（async servlet 化）、scanner の有界 Future、JDBC `socketTimeout`、再利用照会内の stored bytes 再 hash の確認点（`DocumentService` の interface 変更を伴う）は後続課題。
- clamd `StreamMaxLength` と 64MiB の整合（運用確認）。
- 透過再接続 proxy を使っていないことの DBA / SRE 確認と配備 checklist への反映。
- 法人ごとに identity を分離するか（DDL を伴うため Owner 判断）。Q-15 で現時点は残余リスクとして受容（§4）。
- Data Owner の admission 判断（§10 の決定 ID・承認 source 一覧・有効期間の署名）。
- 検証の 120 秒は soft 上限で、1 回の検証は source を3回（検証・append・seal 後の再計算）先頭から読む。上限 64MiB・100,000 行に近い原本は 120 秒に収まらない可能性があり、その場合は 503 で abort される（硬い上限化・非同期化は後続課題）。
- preview の表示値は page ごとに source を先頭から当該 page の最終行まで再 parse する。末尾に近い page ほど遅い。

## 10. 本番 admission の承認設定（Q-5）

- 本番の `ImportAdmissionPort` は `ApprovedSourceImportAdmissionPort`。既定は deny で、承認設定が全て有効な場合だけ upload を受け付ける。評価は `ImportAdmissionApproval`（I/O なし）が行う。考え方は NF08 外部送信承認（`AiExternalSendApproval`）と同じ（ID 形式・placeholder 検出・有効期間・承認 digest・fail-closed）。
- 設定（値は署名済み Data Owner 決定書の転記。秘密値は置かない。既定は全て未設定）:

| 設定キー | 環境変数 | 形式・検査 |
|---|---|---|
| `app.import-center.admission.decision-id` | `IMPORT_ADMISSION_DECISION_ID` | `[A-Za-z0-9][A-Za-z0-9._:-]{3,63}`。`TBD`・`TODO`・`DRAFT`・`SAMPLE` 等の placeholder を含む値は拒否 |
| `app.import-center.admission.tenant-id` | `IMPORT_ADMISSION_TENANT_ID` | 承認対象 tenant。現在の認可 context の tenant と完全一致しなければ拒否 |
| `app.import-center.admission.signed-on` | `IMPORT_ADMISSION_SIGNED_ON` | ISO-8601 日付（JST 業務日）。この日より前は拒否 |
| `app.import-center.admission.expires-on` | `IMPORT_ADMISSION_EXPIRES_ON` | ISO-8601 日付。この日以降は拒否。署名日より後で、署名日から 366 日以内 |
| `app.import-center.admission.source-codes` | `IMPORT_ADMISSION_SOURCE_CODES` | 承認 source code のカンマ区切り（1〜50 件、source code 形式。比較は小文字） |
| `app.import-center.admission.approval-digest` | `IMPORT_ADMISSION_APPROVAL_DIGEST` | 承認内容の SHA-256（小文字 hex 64 桁）。現行設定から再計算した値と一致しなければ拒否 |

- 承認 digest の正規形（UTF-8、各行 LF 終端、最終行も LF）:

```
nf06-import-admission-approval-v1
decision-id=<決定ID>
tenant-id=<tenant ID>
signed-on=<YYYY-MM-DD>
expires-on=<YYYY-MM-DD>
entity=CUSTOMER
schema-version=ses-import-v1
source-codes=<小文字化し昇順に並べたsource codeのカンマ区切り>
```

  署名者が決定書へこの SHA-256 を記載し、運用者が `approval-digest` へ転記する。決定 ID を変えずに承認 source を足す・期限を延ばす等の編集は digest 不一致で拒否される（新しい決定が必要）。
- 判定: 承認が有効でも、現在の tenant が承認 tenant と一致し、source code が承認一覧に含まれ、entity が `CUSTOMER`・schema が `ses-import-v1` の場合だけ許可する。body 読取前（`admitBeforeBody`）と内容 hash 確定後（`admitContent`）の両方で評価し直し、日付の変わり目の失効と、token 発行時と異なる決定（決定 ID・digest）を拒否する。
- 監査: 判定結果を `t_audit_log` へ残す（method `ADMISSION`、uri `/api/import-center/uploads;phase=…;result=…;decision=…;source=…`）。許可は `recordRequired`（`IMPORT_ADMISSION_ALLOWED`）で、記録できなければ許可しない（503 `error.import.auditUnavailable`）。拒否（`IMPORT_ADMISSION_DENIED`、`reason=` に固定の違反コード）は best-effort で、記録の成否にかかわらず拒否する。CSV 本文・hash 全体は書かない（content phase は SHA-256 の先頭 12 桁だけ）。
- 起動時（`ImportAdmissionStartupValidator`、prod profile のみ）:
  - NF06 OFF: 起動を止めない（初回リリースは OFF で配備）。部分的な設定があれば WARN だけを出す。
  - NF06 ON: 構造的な不備（未設定・部分設定・形式不正・placeholder・digest 不一致・有効期間上限超過等）があれば起動失敗。メッセージには固定の違反コードだけを出す。
  - NF06 ON で不備が時間要因だけ（署名日前・失効）: 起動は継続し、upload だけを拒否したまま WARN `NF06_IMPORT_ADMISSION_STOPPED_AT_STARTUP` を出す。再承認後に設定を差し替えて再起動する。

## 11. 検証・preview・error export（W3-05）

**検証（`POST /jobs/{id}/validations`、`ImportValidationRunService`）**
- 同期実行。順序は mapping 束縛（UPLOADED の job だけ。body の `mappingId`）→ start（MAPPED）または lease 切れの takeover（VALIDATING）→ source 先頭からの検証と row append → seal → SEALED snapshot の再計算 → atomic publish。状態遷移は全て既存 lifecycle の CAS（version・lease 世代・expires_at・run slot の完全一致）と fence で行う。
- VALIDATED の job は再検証せず現在の公開 run の要約を返す（冪等）。lease 有効中の VALIDATING は 409 `validationInProgress`。
- 120 秒の soft 上限を入口から適用し、段階境界と 250 行ごとの lease 更新時に確認する。超過・lifecycle 内部 gate の期限超過は 503 `processingDeadlineExceeded`。中断時は自分の最新 lease で run を abort して job を MAPPED へ戻す（abort できない場合は lease 期限後の次の要求が takeover して続きから進める）。
- 別 tenant・別法人の job は存在しない job と同じ 404。

**preview（`GET /jobs/{id}/preview?page=&size=&disposition=`、`ImportValidationResultService`）**
- 公開済み run（job VALIDATED・run COMPLETED・published_slot=1）だけ。未完了は 409 `validationNotCompleted`。
- 行一覧は DB の検証行（PII なし）から読む。`PageUtils.safePage` で既定 50・上限 200 に正規化する（手書きの LIMIT/OFFSET）。
- 表示値は source 原本を exact snapshot（`ImportMetadataService#openSource` の ACL・hash 再確認）から再 parse し、当該 page の行だけをマスクして返す。各行の source 行 hash が保存済み hash と一致しなければ 409。
- マスク規則（`ImportPreviewMasker`。分類表に無い項目・entity は完全マスク）: 外部 key・会社名・カナ・商流・信用度・納品希望は表示（64 文字で切詰め）、担当者名は先頭 1 文字、メールは local 部先頭 1 文字と domain、電話は末尾 4 桁、住所・備考は値の有無だけ（`＊＊＊`）。
- 閲覧の前に必須監査（`IMPORT_PREVIEW`）。記録できなければ返さない（503）。

**error export（`GET /jobs/{id}/error-export`）**
- REJECTED 行の finding code を、source 行番号・source 行 hash と合わせて UTF-8（BOM 付き）CSV で返す。列は `sourceRowNumber,sourceRowHash,code`。原文・値は出さない。
- DB から keyset（行番号順、500 行ずつ）で読み、page ごとに flush する（全件を memory へ載せない）。page 読取の前ごとに認可を再確認し、撤権・法人変更があれば以後を出力しない。
- 各 cell は `ImportErrorCsvWriter#sanitize` で CSV injection を無害化する（先頭文字が TAB・CR、または前置空白の後の先頭が `=`・`+`・`-`・`@` なら `'` を前置。値全体が十進数の負数はそのまま）。
- 一時 file を作らないため、`FileReferenceProvider`・`FileScopeValidationService` への追加登録は不要。出力前に必須監査（`IMPORT_ERROR_EXPORT`）。

## 12. 補償削除の失敗検知（N6-2）

- `LocalDocumentStorage#delete` は削除失敗をログだけで握りつぶす（既存利用者の意味は変えない）。取込 stage の補償削除だけは新しい `DocumentStorage#deleteStrict` を使い、失敗（または削除後も実体が残る）を例外で返す。
- 対象は `DocumentServiceImpl` の取込 stage 破棄経路（stage 途中失敗時の補償と `discardImportStage`）だけ。失敗は 500 `storageCleanupFailed` の原因に `ImportStageCleanupFailure`（storage key・operation ID だけ）を付ける。
- 検知と回収:
  - stage を返した後の破棄失敗: 従来どおり照合台帳（`DISCARD_FAILED`）＋ metric `path=winner|failure`。
  - stage 処理の途中失敗時の補償失敗: `ImportCsvIngestionService` が原因の印から照合台帳へ記録し、metric `path=stage` で通知する（元の失敗はそのまま返す）。
  - 照合 batch（§6）は回収時の削除失敗を検知し、台帳を残して次回再試行する（従来は失敗が見えず台帳だけが外れ得た）。
