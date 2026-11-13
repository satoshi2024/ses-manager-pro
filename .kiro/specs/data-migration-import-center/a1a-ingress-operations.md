# NF-06 A1a 顧客raw CSV ingress 運用・配備前提（W2-B1 硬化）

- 対象: `POST /api/import-center/uploads`（顧客CSVのraw upload）と `GET /api/import-center/jobs/{id}`
- 位置づけ: 2026-11-13 の本番公開判断（B-NF06-1〜5）を実装へ反映した結果と、配備・運用で守るべき前提をまとめる。
- 本文書は承認・署名を代替しない。Owner / Privacy / Security / DBA / SRE の確認事項は末尾に残す。

## 1. 初回リリースの範囲（B-NF06-3）

- 初回リリースに開放するのは upload・validation・preview だけで、apply 系（apply・restart・rollback・compensation）は含めない。
- apply 系の入口は既存の DENY 機構で閉じており、機能スイッチ（`com.ses.config.feature`）は使わない。
  - 取込API controller が公開する route は `POST /uploads` と `GET /jobs/{id}` の2本だけ。
  - `/api/import-center/**` のそれ以外の path は `ActionPermissionResolver` が action key を返さず、`MenuPermissionFilter` が管理者を含め 403 にする（fail-closed）。
  - 本番 classpath の `ImportAdmissionPort` 実装は `DefaultDenyImportAdmissionPort` だけで、承認済み source の admission port（A-NF06-8）が入るまでは upload も body 読取前に 403 になる。
  - 本番 classpath に apply 実装 package は無い。
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
- 残る差: 別法人の内容に当たった upload は成功せず 403 になる。これは identity が法人横断で一意である限り消せない。403 は他の認可拒否と区別できない応答にしてある。法人ごとの identity 分離には DDL が必要で、本バッチの範囲外。
- F1a の `registerJob`（HTTP 入口なし）の重複時 409 は未変更。

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
- guard filter（`ImportRawBodyTransportGuardFilter`）の拒否応答も message key を出さない。画面と同じ `LocaleResolver`（`SES_LOCALE` cookie、既定は日本語）で locale を決め、`SafeErrorPolicy` 経由で文言に解決する。

## 8. 試験

| 対象 | 試験 |
|---|---|
| 413・汎用403・照合台帳・winner アラート | `ImportCsvIngestionServiceTest`、`GlobalExceptionHandlerTest`、`ImportCenterApiSecurityTest` |
| 照合台帳と判定規則 | `ImportStageReconciliationLedgerTest`、`ImportStageReconcilerTest`（専用 H2）、`ImportCsvIngestionRealMySqlTest`（実 MySQL・KILL 注入からの回収と commit 済み保持） |
| 透過再接続の fail-fast | `ImportJdbcReconnectGuardTest` |
| apply 入口の閉鎖 | `ImportCenterApplyEntryClosedTest` |
| 実 Tomcat エンドツーエンド | `ImportRawUploadServletContainerE2ETest`：素の `text/csv`（UTF8・SHIFT_JIS）、parameter 付き 415、chunked + service 413。RANDOM_PORT のため TestIsolationAuditTest の allowlist に登録 |

## 9. 未決・外部確認事項

- 硬い期限（async servlet 化）、scanner の有界 Future、JDBC `socketTimeout`、再利用照会内の stored bytes 再 hash の確認点（`DocumentService` の interface 変更を伴う）は後続課題。
- clamd `StreamMaxLength` と 64MiB の整合（運用確認）。
- 透過再接続 proxy を使っていないことの DBA / SRE 確認と配備 checklist への反映。
- 法人ごとに identity を分離するか（DDL を伴うため Owner 判断）。
