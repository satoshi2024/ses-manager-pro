# NF-06 A1a 顧客raw CSV ingress 運用・配備前提（W2-B1 硬化・W3-05 初回リリースAPI）

- 対象: `/api/import-center` の初回リリースAPI（下表）と取込センター画面 `/import-center`。W2-B1 で upload と job 詳細、W3-05 で検証・preview・error export・source/mapping 管理と本番 admission port、W3-12 で画面・menu（V193）・`import.metadata.manage` の付与・行参照の鍵付きHMAC を追加した（§13・§14）。
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
| 画面 `GET /import-center`（W3-12） | 上表のAPIだけを呼ぶ取込センター画面（§13）。menu `import-center` は管理者だけ |

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
  - **複数 host へ切り替える JDBC URL**（RB-NF05、W2-NF06 review P3-2）。Connector/J の failover・load-balance proxy は通信異常時に別の接続へ切り替えるため、透過再接続と同じく結果不明の COMMIT の分類を崩す。
    - host 部に複数の host を書いた URL: `jdbc:mysql://h1:3306,h2:3306/db`、`jdbc:mysql://[h1,h2]/db`、`jdbc:mysql://address=(host=h1)...,address=(host=h2).../db`。host 部の key-value 形（`(host=h1,port=3306)`・`address=(...)`）は1 host でも拒否する（`host:port` 形に書き直す）。
    - host 部は Connector/J の authority と同じく `://` の後から最初の `/`・`?`・`#` の手前まで（`;` では終わらない）。host 部に `;` を含む URL（`jdbc:mysql://h1;x,h2/db` 等）も拒否する（RB-NF05 review P3-1。`;` を区切りとみなすと `h1` の1 host に見え、実際は failover になる）。
    - `jdbc:mysql:loadbalance://`・`jdbc:mysql:replication://`・`jdbc:mysql+srv://`（DNS SRV で複数 host に展開する）・`jdbc:mysqlx://`。
    - MariaDB driver の `failover`・`sequential`・`loadbalance`・`replication`・`aurora`。
  - ProxySQL・MySQL Router 等、client に見えない形で接続を張り替える透過再接続 proxy。
  - Hikari 以外の connection pool（`spring.datasource.type` の変更、`dbcp2`・`tomcat` pool の connection-properties）。Hikari は SQLState 08xxx の接続を pool から外し、その接続での rollback を失敗させる（結果は UNKNOWN になる）ため、分類はこの動作を前提にする。
- 許可する構成: アプリ → Hikari → MySQL の直結（単一 host の `jdbc:mysql://host:port/db`）、または接続断を client へそのまま返す（透過再接続しない）L4 LB。DB の切替え（failover）は接続先の DNS・LB 側で行い、JDBC URL には1 host だけを書く。

**起動時の fail-fast**
- `ImportJdbcReconnectGuard` が次の property を検査する（機能スイッチと関係なく常に動く）。
  - `spring.datasource.url`（`DB_URL` を含む）
  - `spring.datasource.hikari.jdbc-url`
  - `spring.datasource.hikari.data-source-properties.*`（`url` は JDBC URL と同じ規則で、autoReconnect と複数 host の両方を見る。他の key は autoReconnect 系と、下の接続の型・接続先・切替えを変える key を見る）
- `autoReconnect` / `autoReconnectForPools` が `true` / `yes` なら起動を中止する。URL の値は百分率符号化を戻してからも判定する（`%74rue` 等。Connector/J は戻した値を読む）。
- MySQL・MariaDB の URL が上の複数 host の形なら起動を中止する（違反は `spring.datasource.url(multiHost)` のように property 名と印だけで示す）。MySQL・MariaDB 以外の URL（H2 等）は対象外。
- **最終判定は Connector/J 自身の解析結果**（RB-NF05 delta review N-P3-1）: Connector/J が受け付ける URL は `ConnectionUrl.getConnectionUrlInstance` で解析し、単一接続（`SINGLE_CONNECTION`）かつ host 1件のときだけ通す。解析に失敗する URL は `(unparsable)` で拒否する（driver が解析できない URL は接続もできない）。自前の文字列判定は前段の速い拒否で、どちらかが拒否すれば拒否する。scheme と `//` の間の空白（`jdbc:mysql: //h1,h2/db`）、百分率符号化した scheme（`jdbc:%6Dysql:loadbalance://`）、user 情報の `,`（Connector/J は `,` で分けてから user 情報を取るため、`app:3306,x@db-a` は2つの host になる）も、Connector/J の解析どおりに拒否する。
- **解析には driver へ渡る Properties も渡す**（RB-NF05 delta review D-P3-1）: Hikari は data-source-properties をそのまま driver の Properties（info）として渡し（`spring.datasource.username`・`password` は info に無い時だけ `user`・`password` として足す）、Connector/J は info の値でも接続の型を決める。例えば data-source-properties に `dnsSrv=true` を置くと、単一 host の `jdbc:mysql://_mysql._tcp.db.example/db` が DNS SRV の failover になる（port 付きの host なら解析に失敗する）。解析には同じ info を渡し、その結果で判定する。
- **接続の型・接続先・切替えを変える key は data-source-properties に置けない**（D-P3-1）: 値と大文字小文字に関係なく `名前(connectionTopology)` で拒否する。Connector/J 8.3.0 の `PropertyKey` を照合して選んだ key は次のとおり。
  - 型: `dnsSrv`・`xdevapi.dns-srv`
  - 接続先: `host`・`port`・`protocol`・`path`・`address`・`priority`・`type`・`socketFactory`（8.3.0 では `host`・`port` は URL の host 部が空の時に使われる）。`PropertyKey` ではないが `MysqlDataSource` の接続先になる `serverName`・`portNumber` も拒否する。接続先は検査した JDBC URL の host:port だけで決める。
  - failover・load-balance・replication の動作: `failOverReadOnly`・`retriesAllDown`・`secondsBeforeRetrySource`・`queriesBeforeRetrySource`・`allowSourceDownConnections`・`allowReplicaDownConnections`・`readFromSourceWhenNoReplicas`（旧名の `secondsBeforeRetryMaster`・`queriesBeforeRetryMaster`・`allowMasterDownConnections`・`allowSlaveDownConnections`・`readFromMasterWhenNoSlaves` も）、`ha.loadBalanceStrategy`・`ha.enableJMX`・`loadBalance*` の10件（旧名 `loadBalanceBlacklistTimeout` も）・`replicationConnectionGroup`・`serverAffinityOrder`、再接続の `maxReconnects`・`reconnectAtTxEnd`。
  - 照合して対象から外した key: `localSocketAddress`（ローカル側の bind address）、`socksProxyHost`・`socksProxyPort`・`socksProxyRemoteDns`（TCP の中継で、切断は driver へ失敗として届き、別の接続へ切り替えない）、`resourceId`（XA の識別）。
  - `autoReconnect`・`autoReconnectForPools` は従来どおり値（`true`/`yes`）で判定し、key の大文字小文字と値の前後の空白は問わない（`false`・`no` は通す）。
- 例外文言には property 名だけを出し、URL・host 名・認証情報は出さない。
- 透過再接続 proxy と Hikari 以外の pool はアプリから検出できない（または検査の対象外）ため、配備 checklist で DBA / SRE が確認する（`feature-switches.md` 4.8.2 #7）。

## 6. 結果不確定 stage の照合と回収（A-NF06-2）

**記録**
- 照合台帳 `ImportStageReconciliationLedger` に記録する対象は次の2つ。
  - COMMIT 結果が確定せず、readback でも本 stage の行が見えないため保全した stage（`OUTCOME_UNKNOWN`）。
  - 未 commit 確定なのに補償削除に失敗した stage（`DISCARD_FAILED`）。
- 台帳は upload root 直下の `import-stage-ledger/` に置く。stage 1件につき properties file を1つ作り、内容は tenant ID・storage key・operation ID・理由・記録時刻と、`OUTCOME_UNKNOWN` では COMMIT を送った接続の server 側 ID（`commitConnectionId`。MySQL の `CONNECTION_ID()` を COMMIT 前に控えたもの。取得できた場合だけ）とその server の識別（`commitServerUuid`。`@@server_uuid` を小文字で）とする（CSV 本文・利用者情報は含めない）。`commitConnectionId` が数値でない・1未満、`commitServerUuid` が UUID の形式でない台帳は読めない file として飛ばし、時間だけで消さない。
- 孤児清掃（`FileCleanupServiceImpl`）は upload root・quarantine・published の直下だけを非再帰で走査し directory を対象にしないため、台帳 file は消されない。download API からも参照されない。

**回収**
- `ImportStageReconcileScheduler`（毎時17分、ShedLock `importStageReconcileHourly`）が `ImportStageReconciler` を呼ぶ。記録から1時間（`SAFETY_WINDOW`）を過ぎた台帳だけを判定する。
- 判定規則:
  - `t_document_version.storage_key` が同じ storage key を参照する版が1件でもある（tenant・論理削除を問わない）→ commit 済みと確定し、台帳だけを外す（object は文書台帳の管理下に残る）。照会は V200 の索引 `idx_dv_storage_key` を使う。
  - 参照が無く、台帳に `commitConnectionId` がある → まず、記録した時の server が今も再起動せずに動いているかを確かめる（RB-NF05 review P3-3）。接続 ID は server の再起動・failover の後に別の接続へ振り直されるため、次のどちらかなら ID を使わず、下の「`commitConnectionId` が無い」場合と同じく時間だけで判定する（元の接続は既に無く、COMMIT の結果は確定している）。
    - 現在の `@@server_uuid` が台帳の `commitServerUuid` と違う（別 server。failover）。
    - 現在の server の起動時刻（`UNIX_TIMESTAMP()` − `SHOW GLOBAL STATUS LIKE 'Uptime'`）が、台帳の記録時刻＋5分より後（記録後に再起動した）。5分は DB とアプリの時計のずれと `Uptime` の秒の丸めの許容で、再起動していない server を再起動と誤らない側に取る（記録の5分以内に再起動した場合は ID を使う。回収が遅れるだけで消し過ぎはしない）。
    - server を確かめられない（照会の失敗）台帳は失敗として残す。
  - 同じ server のままなら、その接続の server thread が文を実行中か（`information_schema.PROCESSLIST` の `ID` が一致し `COMMAND <> 'Sleep'`）を確かめる（RB-NF05、W2-NF06 review P3-1）。
    - 実行中 → COMMIT はまだ確定していない。判定を見送り（pending に数え）、台帳を残す。COMMIT が server 側で1時間を超えて止まり（disk full・FTWRL 待ち等）、その間に client 側の接続だけが L4 LB に切られた場合でも、時間だけで原本を消さない。
    - 実行していない（`Sleep`、または一覧に無い）→ 参照件数を数え直す。確かめる間に COMMIT が確定した場合は commit 済み、0件なら未 commit と確定して回収する。`Sleep` の thread は COMMIT を実行しておらず、client が既に切れているため以後も COMMIT は送られない。`Killed` は後始末中のため実行中に数える。
    - 同じ DB account（同じ user 名）の thread は PROCESS 権限なしで見えるため、アプリの account に権限を足す必要は無い（業務権限だけの専用 user で `ImportCsvIngestionRealMySqlTest` が確かめる）。**全 instance が同じ user 名を使い、alternating-users 方式の rotation をしないことが前提**（`feature-switches.md` 4.8.2 #10、`deploy-runbook.md` §1.2）。別の user の接続は見えず「実行していない」と判定され、時間だけの判定へ戻る。照会に失敗した台帳は失敗として残す。
  - 参照が無く `commitConnectionId` が無い（MySQL 以外の DB、ID の取得失敗、`DISCARD_FAILED`）→ 従来どおり、記録から1時間を過ぎていれば未 commit と確定して回収する。`DISCARD_FAILED` は未 commit が確定しているため接続を確かめる必要が無い。
  - DB 照会・削除の失敗 → 台帳を残して次回再試行する。
  - storage key が既定形式（32桁hex/UUID）でない台帳は自動削除せず、手作業の照合へ回す。
- 残る前提: 記録の5分以内に server が再起動し、再起動後の別の接続が同じ ID で長い文を実行している場合だけ、その文が終わるまで回収が遅れる（消し過ぎにはならない）。**再起動が記録より前に起きた場合**（接続 ID を控えた後・台帳へ記録する前の再起動。COMMIT の失敗そのものが再起動による場合）も、起動時刻が記録時刻より前になるため ID を使う。この場合も結果は回収が遅れるだけである（PROCESS 権限が無いため同じ ID に見えるのはアプリ自身の接続だけで、遅れはアプリの最も長い文を超えない。RB-NF05 delta review N-P3-3）。再起動・failover が記録の5分より後なら、ID を使わずに判定する。
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
2. DB で `SELECT COUNT(*) FROM t_document_version WHERE storage_key=?` を実行する。台帳または error log に `commitConnectionId` があれば、先に `SELECT COMMAND, TIME FROM information_schema.PROCESSLIST WHERE ID = ?` でその接続が文を実行していない（行が無いか `Sleep`）ことを確かめ、実行中なら終わるまで待つ。
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
| 照合台帳と判定規則 | `ImportStageReconciliationLedgerTest`、`ImportStageReconcilerTest`（専用 H2。COMMIT 接続が実行中の間は見送り、確認中の commit を数え直しで拾い、確認できなければ残す）、`ImportCsvIngestionRealMySqlTest`（実 MySQL・KILL 注入からの回収と commit 済み保持、COMMIT 接続 ID の記録、`DO SLEEP` 中の接続を実行中と判定する process 一覧の照会） |
| storage key の索引（V200） | `ImportStageStorageKeyIndexContractTest`（V200 と H2 schema の索引の一致、Docker 不要）、`FlywayMigrationSmokeTest`（実 MySQL の索引） |
| 透過再接続・複数 host の fail-fast | `ImportJdbcReconnectGuardTest`（autoReconnect・百分率符号化・multi-host・loadbalance・replication・SRV・mysqlx・MariaDB の failover 等。data-source-properties の `dnsSrv` を driver の解析へ渡すことと、接続の型・接続先・切替えを変える key の拒否） |
| apply 入口の閉鎖 | `ImportCenterApplyEntryClosedTest` |
| 実 Tomcat エンドツーエンド | `ImportRawUploadServletContainerE2ETest`：素の `text/csv`（UTF8・SHIFT_JIS）、parameter 付き 415、chunked + service 413。RANDOM_PORT のため TestIsolationAuditTest の allowlist に登録 |
| 本番 admission（Q-5） | `ImportAdmissionPortTest`（既定 deny・承認時の許可と必須監査・部分設定/改ざん/placeholder/失効/未承認 source）、`ImportAdmissionStartupValidatorTest`（NF06 OFF は起動継続、ON は構造的不備で起動失敗） |
| 検証編成 | `ImportValidationRunServiceTest`（H2：mapping 束縛→publish、冪等、別法人 404、進行中 409、lease 切れ takeover、期限超過時の abort と 503） |
| preview・error export | `ImportValidationResultServiceTest`（マスク・page 上限・disposition・別法人 404・未完了 409・監査必須・行 hash 不一致 409・CSV injection・page ごとの flush・途中の権限取消し・最終行 `#END` と、1 page の flush 後に切れた CSV に最終行が無いこと） |
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
- W3-12 で追加した ON の前提（§14）: 行参照の HMAC 鍵、`import.metadata.manage` の付与（V193）、単一法人前提（複数法人対応は Owner 判断）、LB idle timeout 130 秒以上。

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
- **source code の並べ方**（RB-NF05、W3-05 review P3-7）: 小文字化してから重複を除き、**ASCII（文字コード）の昇順**に並べる。`-`（0x2D）＜数字＜`_`（0x5F）＜英小文字の順になる。locale に依存する `sort` は記号を無視して並べるため digest が一致しない。shell では必ず `LC_ALL=C sort` を使う。決定 ID・tenant ID は前後の空白だけを除く。
- **二人による再計算**（`feature-switches.md` 4.8.2 #1）: 署名者と転記者が、決定書の値からそれぞれ正規形を作って SHA-256 を計算し、決定書に記載された digest と一致することを確かめる。アプリの `computeDigest` に頼らない（digest は鍵の無い完全性の束縛で、署名ではない。設定を変えられる者はアプリで値を作り直せる）。
- **golden vector**（合成値。`ImportAdmissionPortTest` の固定値と同じ。source code に `CRM_2`・`crm-2`・`Crm`・`crm2`・`crm` を設定した場合）:

```sh
printf 'nf06-import-admission-approval-v1\ndecision-id=DO-NF06-2026-001\ntenant-id=default\nsigned-on=2026-12-01\nexpires-on=2027-11-30\nentity=CUSTOMER\nschema-version=ses-import-v1\nsource-codes=crm,crm-2,crm2,crm_2\n' | sha256sum
# c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550
# 並べ方の確認（C locale）: crm, crm-2, crm2, crm_2 の順になること（en_US 等では crm, crm_2, crm2, crm-2 になり一致しない）
printf 'crm\ncrm-2\ncrm2\ncrm_2\n' | LC_ALL=C sort
```

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
- 行一覧は DB の検証行（PII なし）から読む。`PageUtils.safePage` で既定 50・上限 200 に正規化する（手書きの LIMIT/OFFSET）。page 番号は 100,000（1 job の行数上限）へ丸め、OFFSET の桁あふれによる 500 を防ぐ（W3-12。W3-05 review P3-3。source・mapping の一覧も同じ）。
- 表示値は source 原本を exact snapshot（`ImportMetadataService#openSource` の ACL・hash 再確認）から再 parse し、当該 page の行だけをマスクして返す。各行の source 行 hash が保存済み hash と一致しなければ 409。
- 応答の `sourceRowHash` は保存済みの source 行 hash ではなく、鍵付き HMAC の行参照（§14.1）である。
- マスク規則（`ImportPreviewMasker`。分類表に無い項目・entity は完全マスク）: 外部 key・会社名・カナ・商流・信用度・納品希望は表示（64 文字で切詰め）、担当者名は先頭 1 文字、メールは local 部先頭 1 文字と domain、電話は末尾 4 桁、住所・備考は値の有無だけ（`＊＊＊`）。
- 閲覧の前に必須監査（`IMPORT_PREVIEW`）。記録できなければ返さない（503）。

**error export（`GET /jobs/{id}/error-export`）**
- REJECTED 行の finding code を、source 行番号・行参照と合わせて UTF-8（BOM 付き）CSV で返す。列は `sourceRowNumber,sourceRowHash,code`（`sourceRowHash` 列の値は preview と同じ鍵付き HMAC の行参照。§14.1）。原文・値は出さない。
- DB から keyset（行番号順、500 行ずつ）で読み、page ごとに flush する（全件を memory へ載せない）。page 読取の前ごとに認可を再確認し、権限取消し・法人変更があれば以後を出力しない。
- **最終行**（RB-NF05、W3-05 review P3-1）: 全 page を書き終えた場合だけ、最後に `#END,<code 行の件数>,<REJECTED 行の件数>` の1行を書く（列数は header と同じ3列）。flush 済みの応答は途中の失敗（権限取消し・DB 障害・行の不一致 409）でも 200 のまま切れ、page 境界で切れると完全な CSV に見えるため、**この行が無い CSV は途中で切れている**。利用者・運用者は最終行の有無と、code 行の件数が実際の行数と一致することを確かめる（画面の「エラーCSV」も同じ file）。
  - **最終行はデータ行ではない**（RB-NF05 review P3-7）。件数の2つが `sourceRowHash` 列と `code` 列の位置に入るため、そのまま Excel の filter・pivot や script の集計にかけると、code が数字の行として数えられる。集計・filter の前に、先頭の cell が `#END` の行を除く。script は「最終行が `#END` で始まる」「件数が本文と一致する」を確かめてからその行を除いて処理し、どちらかを満たさない CSV は使わずに出力し直す。`sourceRowNumber` 列は数字だけなので、`#` で始まる先頭 cell で見分けられる。
- 各 cell は `ImportErrorCsvWriter#sanitize` で CSV injection を無害化する（先頭文字が TAB・CR、または前置空白の後の先頭が `=`・`+`・`-`・`@` なら `'` を前置。値全体が十進数の負数はそのまま）。
- 一時 file を作らないため、`FileReferenceProvider`・`FileScopeValidationService` への追加登録は不要。出力前に必須監査（`IMPORT_ERROR_EXPORT`）。

## 12. 補償削除の失敗検知（N6-2）

- `LocalDocumentStorage#delete` は削除失敗をログだけで握りつぶす（既存利用者の意味は変えない）。取込 stage の補償削除だけは新しい `DocumentStorage#deleteStrict` を使い、失敗（または削除後も実体が残る）を例外で返す。
- 対象は `DocumentServiceImpl` の取込 stage 破棄経路（stage 途中失敗時の補償と `discardImportStage`）だけ。失敗は 500 `storageCleanupFailed` の原因に `ImportStageCleanupFailure`（storage key・operation ID だけ）を付ける。
- 検知と回収:
  - stage を返した後の破棄失敗: 従来どおり照合台帳（`DISCARD_FAILED`）＋ metric `path=winner|failure`。
  - stage 処理の途中失敗時の補償失敗: `ImportCsvIngestionService` が原因の印から照合台帳へ記録し、metric `path=stage` で通知する（元の失敗はそのまま返す）。
  - 照合 batch（§6）は回収時の削除失敗を検知し、台帳を残して次回再試行する（従来は失敗が見えず台帳だけが外れ得た）。

## 13. 取込センター画面（W3-12）

- 画面: `GET /import-center`（`ImportCenterPageController`、view `import-center/index`、JS `static/js/modules/import-center.js`）。menu `import-center`（V193、管理者だけ）。機能スイッチ NF06 の画面prefix・menu key として `Feature.NF06` に登録し、OFF の間は画面も404・menu も非表示になる。
- 呼ぶ API は §1 の表だけ。画面独自の API・一覧 API は追加していない（`ImportCenterApplyEntryClosedTest` が controller と route を固定する）。
  - job の一覧 API は無いため、画面は「この端末で upload・表示した job」だけを localStorage に最大10件記憶し、それ以外は job ID を入力して開く（記憶するのは job ID と source code だけで、PII は保存しない）。
- 操作の流れ:
  1. 「取込元・mapping」タブで取込元（source code・表示名）を登録し、mapping 版（顧客の各項目に対応する CSV 見出し）を登録する。版は不変で、内容を変えると次の版になる。取込元の停止・再開は確認ダイアログを出す（version が上がり既存 job の preview が 409 になる。W3-05 review P3-9）。
  2. 「取込ジョブ」タブで取込元・文字コード・CSV を選んで upload する（body は file そのもの、`Content-Type: text/csv`、header `X-Import-Source-Code` / `X-Import-Encoding`、CSRF header 付き）。
  3. UPLOADED の job では mapping 版を選んで検証を開始する。**mapping の束縛は検証 API の `mappingId` で行い、画面から `bindMapping` 相当を直接呼ぶ経路は無い**（W3-05 review P3-2。束縛は `ImportValidationRunService` の可見性確認の後だけ）。
  4. 検証は同期 API（最長 120 秒＋α、途中で 1 byte も返らない）。画面は要求を送ったまま **`GET /jobs/{id}` を3秒ごとに確認**し（W3-05 review P3-5）、応答が LB 等で途切れても（0・502・504、および LB・proxy が返す JSON でない 5xx）状態確認を続ける。アプリの ApiResult で返った 5xx（120 秒の上限超過の 503 等）は確定した失敗として確認を止める。VALIDATED になれば要約（公開済み job への検証 API は再検証せず要約を返す・冪等）と preview を読む。VALIDATING 以外へ戻れば「検証を再開」（takeover で続きから）を案内する。409 は job を読み直し、VALIDATING なら確認を続ける。確認は最長200秒で打ち切る。
  5. preview（マスク済み、判定で絞込み、page 送り）と、却下行があればエラー CSV を出力する（`SES.download`、失敗は toast）。
- 403 は「管理者 role・`import.metadata.manage`・法人の設定を確認する」固定文言にする（server の応答は汎用の拒否文言のため）。画面の表示制御は利便のためだけで、権限の根拠は API の service 層である。
- 一覧画面と同じ絞込み panel の構造（`card > card-body > form#searchForm`）で、スマホ幅では折りたためる。表は `table-responsive` の中で横スクロールし、ページ全体は横にはみ出さない。CDN 等の外部資産は使わない。
- 試験: `ImportCenterPageControllerTest`（管理者だけ到達・サイドバー導線・H2 seed）、`ImportCenterPageFeatureOffTest`（OFF で404）、`FeatureSwitch*`、`ImportCenterBrowserMTest`（`@Tag("browser")`、desktop・390px で upload→検証→preview、console error 0）。

## 14. NF-06 を ON にする前提（W3-12。W3-05 review P2-1・P2-3）

手順の正本は `.kiro/specs/production-launch/feature-switches.md` 4.8。ここでは設計上の要点だけを記す。

### 14.1 行参照の鍵付き HMAC（P2-1）

- 問題: 検証行に保存する source 行 hash は鍵の無い SHA-256（各列の UTF-8 に4 byte の長さを前置した連結）で、preview はこれを平文列・部分値（外部キー・会社名・担当者名の先頭1文字・メール domain・電話末尾4桁・住所/備考の有無）と一緒に返していた。住所・備考が空の行では、未知量が担当者名の残りと電話の前半程度になり、手元の総当たりでマスクした値を復元できた。error export の CSV も、持ち出された後に「ある行が存在するか」を確かめる oracle になっていた。
- 対処: 外部へ返す値（preview の `sourceRowHash`・error export の `sourceRowHash` 列）だけを、`ImportSourceRowReference` が保存済み hash を秘密鍵で写した HMAC-SHA256（16進64桁）にした。入力は domain（`SES-NF06-SOURCE-ROW-REFERENCE-V1`）・tenant（長さ前置）・job ID・保存済み hash（32 byte）の連結。
  - 同じ job の中では preview と error export の値が一致し、行を照合できる。別 job・別 tenant では同じ内容の行でも別の値になり、job をまたいだ突合はできない。
  - 保存値（`t_import_validation_row.source_row_sha256`）と内部の整合確認（原本再読込・seal・再計算の fence）は変えない。DDL・migration は不要で、既存 job の整合性に影響しない。
  - 既存データへの影響: 表示値だけが変わる。本番では NF06 を一度も ON にしていないため、以前に出力した CSV は存在しない（dev/test で以前に出力した CSV の値とは一致しない）。鍵を差し替えた場合も同様に全ての表示値が変わる。
- 鍵: `app.import-center.row-reference.hmac-key`（環境変数 `IMPORT_ROW_REFERENCE_HMAC_KEY`）。Base64（標準・URL-safe）で復号後32 byte以上。`application.yml` の既定値は開発用で、prod では使えない（`application-prod.yml` は既定を空にし、開発用鍵と test 用鍵は実行時にも拒否する。表記を変えても復号後の byte 列で比べるため、`=` の省略や URL-safe 表記では通らない）。
  - 鍵が使えない間は、preview・error export を認可確認の直後・監査と原本読込の前に 503 `error.import.rowReferenceUnavailable` で拒否する（fail-closed）。
  - prod の起動検査 `ImportRowReferenceKeyStartupValidator`（`ImportAdmissionStartupValidator` と同じ考え方）: NF06 OFF は起動を止めない（設定済みで使えない鍵だけ WARN）。NF06 ON で未設定・形式不正・開発用鍵なら起動失敗。message は `state=` の固定値だけで、鍵の値を log・例外へ出さない。
- 試験: `ImportSourceRowReferenceTest`、`ImportValidationResultServiceTest`（preview と CSV が同じ行参照で保存 hash を出さない・鍵の差替えで値が変わる・鍵が無ければ監査と原本読込の前に503）。

### 14.2 `import.metadata.manage` の明示許可（P2-3(a)）

- service 層（`ImportMetadataAuthorizationService`）は `import.metadata.manage` の明示許可行を要求し、全体 baseline `'*'` を許可根拠に数えない（`*`・`import.*`・同 key の拒否は優先）。従来は seed が無く、ON にしても全管理者が403だった。
- V193 で、管理者専用 group（`role-admin`・`privacy-admin`）へ明示許可を入れ、他の全 group では拒否する（baseline＋deny。V191 の `privacy.*` と同型。利用者の割当は変えない）。group を追加する migration・運用作業は、管理者専用でなければ同じ拒否行も入れること。
- 試験: `ActionPermissionMatrixTest`（H2 fixture `permission-group-seed-h2.sql` の最終状態と判定）、`FlywayNf06ImportCenterMenuMigrationTest`（実 MySQL、mysql shard 3。fresh と V192 からの更新、既存行の補正）。

### 14.3 単一法人前提（P2-3(b)）

- 管理者の法人 context は共通の `LegalEntityContextService` が `m_organization_unit` の全法人 ID（`AttendanceScopeMapper#selectAllLegalEntityIds`）から一意に決める。法人が2つ以上あると取込の全操作が403（`LEGAL_ENTITY_CONTEXT_REQUIRED`）になる。
- tenant での限定は本バッチでは行わない。理由: `m_organization_unit.tenant_id` は将来用の BIGINT 列で、現行は tenant ごとの独立 DB 運用のため値を持たず、tenant で絞っても結果は変わらない。複数法人の企業で使うには管理者に法人を選ばせる設計変更が必要で、書込み境界の全 writer が使う共通 service の変更になるため、Owner 判断とする。
- したがって初回は**単一法人前提**で運用する。ON の前に `SELECT COUNT(DISTINCT legal_entity_id) FROM m_organization_unit WHERE legal_entity_id IS NOT NULL AND deleted_flag = 0;` が 1 であることを確認する。複数法人の環境で ON にしても漏れる方向には働かない（全操作が403で閉じる）。
  - この SQL は `selectAllLegalEntityIds` と同じ条件で、論理削除（`deleted_flag`）だけを見る。組織の状態（status）と有効期間は見ないため、無効・期間外の組織の法人IDも数える（W3-12 review P3-3）。
  - 新規導入では V60 の移行組織 LEGACY（法人ID NULL）しか無く、結果は0件になる。0件でも全操作が403になるため、ON の前に法人IDを持つ組織を登録する。
- 防御の重ね（P3-2）: `ImportMetadataService#bindMapping` は job 行の参照と `FOR UPDATE` に現在の法人を条件として付け、別法人の job と lock 前に消えた job を同じ404にする（以前は null を参照して500）。試験: `ImportMetadataBindMappingScopeTest`。

### 14.4 配備前提（P3-5 ほか）

- LB / reverse proxy の idle timeout は130秒以上にする。検証は同期要求で、最長120秒＋αの間 1 byte も返さない。60秒等の既定では LB が先に切断し、利用者には504が見える（server 側は続行し得る。画面は §13 の状態確認で結果を取りこぼさない）。
- 透過再接続の禁止（§5）、単一 instance または sticky session（§6）、raw CSV の保持期間と preview のマスク分類の Privacy 受容（W3-05 review P3-8）は従来どおり ON の前提に含める。
- 取込元CSVの保持期間（2026-11-16 確認）: NF06 には保持日数で原本を purge する経路が無い。取込元CSVの原本は文書台帳の `IMPORT` 文書（V197。受領・取引日を設定しない）としてだけ保存され、保存期限は NULL のまま処分候補にならない（消えるのは文書台帳へ登録する前の一時 file だけで、使用後に消し、残れば孤児清掃が回収する）。同日の依頼者決定で履歴取込の副本は取込原本の purge に保存期限を揃えたが（`production-bridge-phased-plan.md` §3.4.1「D4 と文書台帳の副本」）、揃える相手の purge が無い `IMPORT` には及ばない。このため「保持期間は開放前に Privacy が書面で受理する」条件はそのまま ON の前提に残る。
