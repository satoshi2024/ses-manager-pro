# NF-10 scheduler claim fence 修正計画

状態: **実装済・`821076c0` の対象限定fast 292/0/0/0・対象限定MySQL 41/0/0/0・browser 1/0/0/0（PowerShell環境）通過・統合候補 `328c2d8b` へ取込済・統合候補 `8000d94e` のCI 7 job通過。** COMPLETE/PASSではない（§10のSRE/DBA承認、§11のOwner判断が未了）。対象は `m_report_schedule` のscheduler claim所有権競合とclaim時刻の正確性に限定する。NF-10全体の再承認、production/運用gateの承認、remote/release承認を意味しない。

- 当初Planの基準Head: `9bac40c9624e7d964bcf6e18f480b3457662206e`（§1〜§8は当時の記述を保持し、変更点は各節の「改訂」注記と§9以降に記す）。
- 2026-11-12改訂の実装基準: `73aa70004484db96ffd9100ffa13dd7acaaf365e`（PR #118候補）。migration番号は **V185** をユーザーが承認済み（root/統括経由）。
- 実装branch: `codex/snf-nf10-v185-integration-20261112`。独立レビュー（REQUEST CHANGES: P0×2、P1×5、P2×12）の指摘ごとの対応は§12。
- テストは統括（唯一のTestRunner）が実行する。`93c11457c17e7f082d2773500e8c47de2d50ed95` に対する独立レビュー（静的）はPASS。同SHAの対象限定fast（9 class）は288/0/0/0、対象限定MySQL（`-Pmysql-tests`、7 class）は41件中1件FAIL（`ReportScheduleClaimFenceMySqlTest` の「同一snapshotの同時claimはInnoDB行lock待ちの後で一件だけが勝つ」、errors/skipped 0）。証拠は統括のrun記録 `nf10-93c11457-fast-targeted` / `nf10-93c11457-mysql-targeted`。
- 上記FAILはtestの観測方法の欠陥（`information_schema.innodb_trx` のcache固定）と判断し、観測を `performance_schema.data_lock_waits` へ置き換えた（§13）。
- 修正commit `821076c0066119b035e3a7a1a84bdc3170341521` は独立レビュー（静的、第3回）PASS。統括Runnerの対象限定実行は、fast 10 class 292/0/0/0（上記9 class＋`AssetComprehensiveSecretScanTest`）、MySQL 7 class 41/0/0/0（`ReportScheduleClaimFenceMySqlTest` 17/0/0/0、前回FAILのcaseを含む）、browser `ManagementReportBrowserMTest` 1/0/0/0（PowerShell環境）で、いずれもzero skip。Bash tool環境のbrowser実行はNIO loopbackの環境要因で起動不能となり、環境FAILとして記録を保持している。証拠は統括のrun記録 `nf10-821076c0-fast-targeted` / `nf10-821076c0-mysql-targeted` / `nf10-821076c0-browser-targeted-ps`。`821076c0` は `8dcdbe28` で統合候補へ取り込まれ、`328c2d8b` に含まれる。2026-11-14追記: 統合候補に対するCIは、第1・2波の全batchを取り込んだ `8000d94e` で7 job（fast・MySQL shard 1〜3・backup integration・browser・performance）すべて通過した（run 43387006667、統括の記録）。手元での `verify-like-ci` 全体実行は本書の記録対象外。それ以外の記述はtest名・設計の対応であり、実行結果を意味しない。
- 2026-11-17追記: ON前のP2/P3（引き継ぎを含むretry上限、entityの書き戻し、tenant単位の隔離、metricの結果区分、時計の差の観測、alert rule草案）をRB-NF10で扱った（§15、§10.6）。MySQL testは統括のRunnerの実行待ちである。

## 1. 確認した欠陥と影響

`ReportScheduleMapper.claimDue` は `processing_claimed_at` をleaseとして設定するが、所有者を表す値を保存していない。`markSuccess`/`markFailure` は `processing_claimed_at IS NOT NULL` だけを条件に更新する。そのため、worker Aのleaseが30分を超えてworker Bが同じ論理実行を再claimした後、復帰したAがBのleaseを消し、`next_run_at`、retry、failure count、error情報を更新できる。B側の完了更新が0行になっても、schedulerは戻り値を確認しない。

`dispatchDueForTenant` はpoll開始時の `now` と `staleBefore` を最大50 scheduleへ使い回している。長い処理後に後続scheduleをclaimすると、DBに保存されるlease取得時刻が実時刻より古くなり、次pollで即stale扱いされうる。failureのretry基準も同じpoll開始時刻を用いる。

## 2. 不変条件

1. 各claimには、workerが新規生成する暗号学的に予測困難なclaim token（UUID等）を割り当て、DBの現在claimへ保存する。tokenはclaimごとに一意であり、再claimでは必ず新しい値を使う。単調generation方式を選ぶ場合も、同じrow上でCAS増加し、同等の予測不能性ではなく一意世代で旧ownerと区別する。本案の第一候補はランダムtoken。
2. 完了更新はtenant/idに加え、`processing_claim_token` の完全一致と `processing_logical_run_at` の完全一致を条件にする。logicalRunAt単独は同一論理実行を再claimすると同値のため所有者識別子にならない。可能なら `processing_claimed_at` もtokenと共に一致条件へ含める。
3. `markSuccess`/`markFailure` が0行を返したらclaim所有権喪失として扱う。古いworkerは別UPDATE、generic entity update、fallback処理でBの状態を上書きしてはならない。所有権喪失をgeneration failureとして再記録しない。
4. claimはdue行の期待状態に対するCASとする。selectDueで読んだtenant/idだけでclaimせず、expected next/retry/logical run/claimedAt/token等の必要な状態をUPDATE条件で再照合する。未claimの初回取得は `processing_claimed_at IS NULL AND processing_claim_token IS NULL` の状態だけを許可し、NULLを未占有として明示CASする。通常completionは非NULLのclaim tokenを必須とし、`processing_claim_token = :claimToken` の通常完全一致を使う。NULL-safe比較、NULL token completion、tokenを省略する旧overloadや後方互換completion経路は設けない。tokenなしの旧snapshot、別tenantの行、leaseを既に更新した競合workerはclaimできない。
   - **2026-11-12改訂（独立レビューP1-1）**: 「未claimは `processing_claimed_at IS NULL AND processing_claim_token IS NULL` だけ」とすると、旧版completionが `processing_claimed_at` だけをclearした行（`processing_claimed_at IS NULL AND processing_claim_token IS NOT NULL`、以下「孤児token」）が永久に選ばれず、retry行はLIMIT枠を占有し続ける。lease占有は `processing_claimed_at` だけで判定し、tokenは `processing_claimed_at` が非NULLの間だけowner識別子として扱うよう改める。孤児tokenはdue snapshotのtoken完全一致を含む全列CASで新tokenへ置換する（§9）。NULL token completion・tokenを省略するcompletionを設けない点は変更しない。
5. DB日時列は秒精度に正規化し、Java時刻、token生成、SQL引数、テスト比較の精度を統一する。MySQL `DATETIME` の秒精度を前提にし、H2 timestampを秒精度で扱う。
6. claimの時刻、stale cutoff、failure時刻/retry計算はscheduleごとの現在時刻から求める。poll開始時のbatch timeを後続scheduleへ流用しない。

## 3. 実装設計

### 3.1 tokenとclaim CAS

新しいlease token列（候補名 `processing_claim_token`、nullable `VARCHAR(36)`）を追加する。`claimDue` は次を一回の条件付きUPDATEで行う。

> 2026-11-12改訂（独立レビューP2-9）: 列は `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL` とし、表既定 `utf8mb4_unicode_ci` による大文字小文字非区別の一致を避ける。`AFTER` 句は付けず末尾追加とする。NULL期待値の照合は `#{x} IS NULL` ではなくMyBatis `<choose>` で `col IS NULL` / `col = #{x}` を明示的に出し分ける。

- tenant/id、enabled、deleted状態とdue条件を確認する。
- due一覧取得時点の期待状態を再照合する。通常dueは `expectedNextRunAt`、retryは `expectedRetryScheduledAt`、stale takeoverは既存 `expectedProcessingClaimToken` と `expectedProcessingClaimedAt` / logicalRunAtを照合する。NULL値も明示的なNULL一致条件として扱う。
- leaseが未取得、またはexpected stale leaseが現在も期限切れであることを確認する。
- 新token、秒精度へ正規化したper-claim `claimedAt`、logicalRunAtを同じUPDATEで保存する。

既存lease行の例外は `processing_claimed_at IS NOT NULL AND processing_claim_token IS NULL` のlegacy状態に限定する。これは新規未占有状態と扱わず、旧scheduler nodeの停止・in-flight処理drain完了後に限り、lease期限切れかつdue snapshotに記録した旧claimedAt/logicalRunAt/token=NULLが現在も完全一致するCASでtakeover可能とする。legacy状態をtokenなしでcompletionする経路は持たない。期限切れ前、expected旧状態の変化後、旧node drain未確認ではclaimしない。

claim結果が1でも、claim UPDATEを含む短いDB transactionのcommit成功が確認されるまでrun処理へ進まない。0行、例外、commit結果不明のいずれも処理を開始せず、claimをclearする補償UPDATEや別tokenでの自動再claimを行わない。結果不明時はDB上の状態を推測せず、leaseが残ればexpiry後の通常CAS takeoverに委ねる。commit成功が確認された場合だけservice内の処理contextへ新tokenを渡す。selectDueが古い一覧を返していても、retry時刻・nextRunAt・lease/tokenが変わっていればclaim CASは0となる。既存の`processing_logical_run_at`は同じ月のstale再開やretryに使う論理run識別として保持し、所有権tokenの代用にしない。

### 3.2 completion CASと失権

`markSuccess(tenantId, scheduleId, claimToken, logicalRunAt, nextRunAt)` と `markFailure(tenantId, scheduleId, claimToken, logicalRunAt, failureAt, retryAt, ...)` は非NULL claim tokenとlogicalRunAt（加えて必要なclaimedAt）を通常の完全一致条件にし、成功/失敗の既存列更新とlease token/claimedAtのclearを一つのUPDATEで行う。成功は`processing_logical_run_at`もclearし、retry/error/countをclearする。失敗は`processing_logical_run_at`を保持し、retry/error/countを記録する。同一論理runの失敗後retryはこのlogicalRunAt（または一意に同等性を証明できる保存済みrun source）を継続し、retry実時刻が次の月を越えていても新logical taskを作らない。retry claim時は所有者tokenだけ新規発行する。

schedulerは各completionの更新件数を必ず確認する。0件は「このworkerは現在ownerではない」として記録して処理を閉じ、schedule stateを再取得して上書きしたり、旧方式のUPDATEへfallbackしたりしない。`runOneInternal` の例外処理と `markSuccess` の例外処理を分離する。generation/delivery処理が例外を投げた場合だけ、保持中tokenによる一回の`markFailure`を試みる。success completionのDB例外またはcommit結果不明は成功/失敗どちらにも推定せず、`markFailure`を続けず、run/deliveryを再実行せず、leaseを補償clearしない。failure completion自体の0行/例外/commit不明も同じく有界に閉じ、completion再試行をしない。実行本体の例外後のfailure completionは最大一回だけ行い、その結果不明でも再試行しない。

report生成やdeliveryの副作用がcompletion CASより前に発生する現在の流れについて、token fencingだけで重複副作用が消えるとは主張しない。再claimによる同一logical runの重複生成/配布が既存のidempotency境界で収束することを並行MySQLシナリオで確認し、必要な追加の副作用排他が見つかった場合は別設計判断にする。

### 3.3 時刻とretry

`Clock`をschedulerへ注入できる構成にし、productionではsystem clock、unit testでは固定/可変clockを使う。poll開始時刻は候補一覧取得のためだけに使う。各schedule処理開始直前に`clock.instant()`相当からtenant zoneのLocalDateTimeを取り、秒精度へ切り捨て、そこから`staleBefore = claimedAt - leaseDuration`を導出してclaim SQLへ渡す。claim token発行時刻もこのclaim時刻に束縛する。

処理失敗時はcatchへ入った実際の時刻を再取得し、秒精度に正規化して`failureAt`とし、failureAtを基点にretry delayを計算する。poll時刻へ遡ってretryを設定しない。retry回数の計算に必要なfailure countは、claim対象のsnapshotが古い場合に過少/過大更新しないようclaim CAS後の既存値を維持するか、owner token条件下のDB式で安全に加算する。

## 4. API・CRUD・rolling deployment境界

claim tokenは内部所有権データであり、API入力から設定・変更できないこと。現行のschedule APIは `ManagementReportScheduleApiController.list/create/enable/disable` のみで、作成入力は `ReportScheduleCreateRequest`、list/操作応答は `ReportSchedule` entityを返す。token列をentityへ追加する場合はJSON応答に含めず `@JsonIgnore` 等で明示的に隠すか、tokenを返さないresponse DTOへ切り替える。またLombok `@Data` の生成`toString()`等にもtokenを含めないよう `@ToString.Exclude` 相当を適用する。tokenをlog、例外文、監査payload、APIに出力せず、SQL/mapper debug loggingでもbind値を記録しない運用設定を確認する。HTTP clientがtokenを偽造、再利用、更新できないAPI contract testを追加する。

`ReportScheduleServiceImpl.create` はtokenを受け取らず初期値NULL。`setEnabled` は現在entityを読み戻して `update(entity, wrapper)` するため、新lease列を含むentity全体を更新してowner stateを潰さないよう、enabledと監査列だけを更新する明示的mapper updateへ変更する。listはtokenをredactしたDTO/entity viewを返す。その他の`BaseMapper` schedule更新経路がないことを再検索し、追加/発見時は同じowner fenceを守る。

rolling deploymentでは旧version nodeはtokenを知らず、token条件なしの`markSuccess`/`markFailure`で新nodeのclaimを完了扱いできる。よってschema適用後に新旧nodeを同時稼働させない。適用前にschedulerを停止し、旧nodeのin-flight schedulerがすべてdrain完了したことを確認し、schemaを適用してから新artifactだけでschedulerを再開する。未知の旧owner/NULL token stateは自動的に新ownerへ帰属させずstale lease再claimまで待つか運用手順で処置する。production migrationとdrain/runbookは後続運用gate。

> 2026-11-12改訂: リリース前提・検出SQL・復旧案の草案を§10に記す（SRE/DBA承認待ち）。`setEnabled` は `updateEnabled`（enabled・updated_by・version・updated_atだけ、`deleted_flag = 0` 条件付き）へ変更し、応答は更新後の行を再読込して返す。lease/retry/token/failure count列は `FieldStrategy.NEVER` でentity由来のINSERT/UPDATEから除外し、next/last runはentity由来のUPDATEから除外した（独立レビューP2-6/P2-7）。

## 5. Schema / test schema / migration境界

新列を追加する場合のmigration候補は `V185__report_schedule_claim_token.sql`。現時点の最大versionはV184だが、V185は未割当であり、本Planでは採番・DDL追加を行わない。正式受理時にrootが採番を確定する。V1/V112/V114は書き換えず、後続migrationでnullable token列と必要なindex検討だけを追加する。既存lease行はtoken NULLのままとし、migration時に実行ownerを捏造しない。

> 2026-11-12改訂: 73aa7000を基準に **V185** の採番がユーザー承認済みとなり、`V185__report_schedule_claim_token.sql` を作成した。V185はどの環境にも未適用であるため、独立レビューP2-9に合わせて直接修正した（適用後の改変禁止規則の対象外）。他branchがV185以上を先に統合した場合は、空き番号を埋めず次の空き番号へ繰り上げる。latest期待値は `src/test/java/com/ses/migration/LatestMigrationVersion.LATEST` に集約した（P2-12）。index追加は行わない（completion/claimは主キー `id` とtenant条件で1行を特定するため）。

実装時に同期するschema箇所:

- `src/main/resources/db/migration/V185__...sql`（rootがversion確定後に作成）
- `src/test/resources/sql/schema-scheduled-management-reporting-h2.sql`（H2 curated schema）
- `src/test/resources/application-test.yml` は当該H2統合schemaを読込済み。新しいH2 scriptを分割する設計に変える場合のみschema-locationsも同期
- `ReportSchedule` entity、およびschedule API JSON/DTO契約
- H2 mapper/service testsとMySQL Testcontainers migration/claim tests

### latest migration assertion

V185がrootにより割り当てられた場合、latest version=184を期待する一般latest assertionを以下4 test classで185へ進める（`FlywaySelfServiceSchemaSmokeTest`内のfresh/legacy双方の2 assertionを含む）。これらは「最新版が184」とする古い期待値を更新するだけで、他のschema/history assertionsは削除しない。

- `src/test/java/com/ses/migration/AssetMySqlIntegrationTest.java` — latest migration assertion
- `src/test/java/com/ses/migration/FlywayCustomerSuccessServiceDeskSchemaSmokeTest.java` — latest version assertion
- `src/test/java/com/ses/migration/FlywaySelfServiceSchemaSmokeTest.java` — fresh/legacy latest version assertions
- `src/test/java/com/ses/migration/FlywayV110AdminBoundaryUpgradeSmokeTest.java` — upgrade latest version assertion

`FlywayNf06V184MetadataMigrationTest`のようにV184適用・V182→V184 upgradeそのものを検証するtestのversion/history/schema assertionsは歴史対象を示すので184のまま残し、必要なら独立して最終head=185のlatest assertionを追加する。受入時に`rg`で全184/114 literalを走査し、意図的に固定したmigration targetとlatest期待を区別してレビューする。

## 6. 対象コード・test変更一覧

### production code（Plan受理後）

- `src/main/java/com/ses/mapper/ReportScheduleMapper.java`: due snapshot CAS、claim token付き取得、完了token/logicalRunAt CAS、0-row契約。
- `src/main/java/com/ses/service/scheduler/ManagementReportScheduler.java`: Clock、per-row now/stale cutoff/failureAt、claim token伝搬、completion更新件数と失権処理。
- `src/main/java/com/ses/entity/ReportSchedule.java`: internal tokenのDB mappingとJSON非公開。
- `src/main/java/com/ses/service/report/impl/ReportScheduleServiceImpl.java`: token入力不可、enable/disableの限定更新。
- `src/main/java/com/ses/controller/api/ManagementReportScheduleApiController.java` / schedule response DTO（必要時）: tokenを返さないresponse contract。
- migration候補および `src/test/resources/sql/schema-scheduled-management-reporting-h2.sql`。

### tests

- `src/test/java/com/ses/report/ManagementReportSchedulerTest.java`: per-row clock、秒精度、retry基準、token伝搬、success/failure更新0件後にfallback/updateを行わないこと、batch間の時刻前進。
- 新規 `src/test/java/com/ses/report/ReportScheduleClaimFenceMySqlTest.java`（`@Tag("mysql")`）または既存MySQLスケジューラtest: real MySQL CAS・lease takeover・tenant条件。
- `src/test/java/com/ses/report/ReportScheduleServiceImplTest.java` とschedule API test: clientがtokenを供給/変更できない、response JSONにtokenを露出しない、enable/disableがactive leaseを消さない。
- migration smoke: fresh installとV184相当schemaからのupgrade、NULL既存token行の保持、MySQL DATETIME精度と新index/column。
- 上記latest assertion 4 classを正確に更新し、歴史的V184 target assertionは維持。

## 7. 必須受入試験

すべてsynthetic schedule/tenant/report fixtureを使い、隔離MySQL Testcontainersで実施する。H2はロジックとschema接続確認に使うが、CAS/競合の合格根拠はMySQL結果とする。

1. **A stale takeover後の旧A success拒否**: Aがtoken Aでclaim、lease期限超過、Bがtoken Bで同じschedule/logicalRunAtをtakeover。Aの`markSuccess`は0行で、Bのtoken、claimedAt、logicalRunAt、nextRunAt、retry、failure/error列を一切変えない。Bはsuccessを完了できる。
2. **A stale takeover後の旧A failure拒否**: 同じ配置でAの`markFailure`は0行、Bの全行状態を不変にする。Bは自身のfailure/successを完了できる。
3. **claim同時競合**: 同じexpected stateから複数workerがclaimを試みても一つだけ1行を得る。違うtenantの同一id相当行はclaim/complete双方で変更不可。
4. **stale due snapshot**: due選択後にretry/next/lease/tokenを更新し古いsnapshotでclaimする操作が0行となる。NULL/非NULL遷移も明示確認する。
5. **正常・retry・idempotency**: 初回successはnext cronへ進みlease token/claimedAt/logical runをclearする。failureは失敗時の現在時刻を基準にretryを設定し、token/claimedAtのみclear、logicalRunAtを保持する。月をまたぐretryも同一logicalRunAtを使いつつ新tokenを採番し、旧tokenは完了できない。同じreport run/deliveryが既存idempotency境界に従い重複しない。
6. **長時間batch**: 50件の先行処理をclockで進め、その後のclaimが先行処理のpoll開始時刻ではなく各件の最新時刻・秒精度で保存されること。直後pollで新claimがstaleと見なされないこと。failure retryも実failure時刻基準であること。
7. **API/entity/log境界**: HTTP JSONからtokenを注入・変更できず、response/list、entity `toString()`、log/例外にもtokenが露出しない。enable/disableがclaim stateを保持する。
8. **deployment/migration**: V184から新schemaへのMySQL upgrade、fresh migration、legacy NULL token、drain-before-rollout手順の確認。旧node混在時に新claimを安全と見なさない境界をrunbook/release条件に記録する。

## 8. 実装開始・完了境界

このPlanは原因と対策、対象file、test契約を固定するためのものであり、実装やschema変更の許可そのものではない。Astraの固定Head Plan受理後、rootがmigration versionを正式割当し、実装を別commitで開始する。実装完了判定にはMySQLのA/B takeover各success/failure結果、fresh/upgrade migration、schema/H2同期、API非露出、時刻試験、およびroot指定gateが必要。production適用にはscheduler drain、旧artifact停止、backup/rollback、migration windowと運用責任者の別承認が残る。

## 9. 孤児token（`processing_claimed_at IS NULL AND processing_claim_token IS NOT NULL`）の扱い

2026-11-12追加（独立レビューP1-1）。

- **発生経路**: (a) 新旧scheduler混在稼働で、新nodeがtoken Xでclaimした行を、tokenを知らない旧nodeがstale takeover（`processing_claimed_at` だけ更新し、tokenはXのまま）した後、旧nodeのcompletionが `processing_claimed_at` だけをclearする。(b) V185 schemaを残したまま旧artifactへrollbackし、新版が残したleaseを旧版が引き継いで完了した後に再upgradeする。
- **意味付け**: completion（`markSuccess`/`markFailure`）は非NULLのclaimedAt完全一致を要求するため、`processing_claimed_at IS NULL` の行に残ったtokenで完了できるworkerは存在しない。したがってowner不在＝未占有として扱う。旧版completionが進めた `next_run_at` / `retry_scheduled_at` / `processing_logical_run_at` はそのまま次回due・retryとして尊重する。
- **処置**: `selectDue` / `claimDue` の未占有判定は `processing_claimed_at IS NULL` だけとし、due snapshotのtoken完全一致（NULL/非NULLを明示照合）を含む全列CASでclaim時に新tokenへ上書きする。同時に `selectDue` のretry分岐へ未占有条件を加え、実行中retry leaseがLIMIT 50枠を毎poll占有しないようにした（P2-5）。
- **検知**: 置換時にschedulerがWARN「旧版completionが残した孤児claim tokenを新tokenで置換しました」を出す（token値は出力しない）。§10.3のQ2でも検出できる。
- **限界**: 混在稼働中は旧nodeが新ownerのlease（`processing_claimed_at`）を書き換え得るため、新ownerのcompletionは0件となり、生成/配布の重複は防げない。fenceは状態の上書きを防ぐだけで旧版の挙動は変えられないため、§10のrolling禁止・drainが前提条件である。
- **検証（`93c11457` の対象限定fast/MySQLで通過、`821076c0` の対象限定fast/MySQLでも通過）**: `ReportScheduleClaimFenceMySqlTest` の「旧版success_completionが残した孤児tokenは通常claimで回収され旧ownerは完了できない」「旧版failure_completionが残した孤児token行もretry時刻にscheduler経由で回収される」「実行中のretry_leaseはLIMIT枠を占有せずstale後だけ再選択される」、`ReportScheduleMapperH2SqlTest`、`ManagementReportSchedulerTest` の孤児token用case。旧版SQLは73aa7000の `ReportScheduleMapper` をtest内に再現して用いる。

## 10. 本番適用の前提（草案・**SRE/DBA承認待ち**）

本節は草案であり、承認・実施されたrunbookではない。コードでは本節の条件を強制していない。

### 10.1 リリース前提

- **rolling deploymentは禁止**。V185適用の前後を問わず、新旧versionのschedulerを同時に稼働させない。
- ~~Flywayは新artifactの最初の起動時にV185を自動適用する（`spring.flyway.enabled` 既定true）。V185適用と新artifact起動は分離できない~~
  - **2026-11-13訂正（配備決定P-3・配備バッチD1）**: prodは `spring.flyway.enabled=false` で起動時にmigrateせず、V185を含むmigrationは**全停止中に専用Flyway job**（`mvn -Pprod-migrate`、設定は `ops/flyway/flyway-prod.conf`）だけが適用する。誤って有効化しても起動時migrateは拒否され、適用漏れ・失敗行があればアプリは起動時schema guardで起動を止める。手順の正本は `.kiro/specs/production-launch/deploy-runbook.md`。
- **旧nodeのschedulerをすべて停止し、in-flight処理のdrain完了を確認してから**jobでmigrationを適用し、その後に新artifactだけを起動する。
- 新artifactへの切替後に旧artifactへ戻す場合も同じ（新nodeを全停止・drainしてから旧artifactを起動する）。DBを戻さずに旧artifactだけを起動することはできない（起動時schema guardが「artifactに無いmigrationが適用済み」として止める）ため、戻す場合はbackupからDBも戻す。
- 起動guard（drain未確認やtoken無しの新しいleaseが残る状態でschedulerを起動しない等）は未実装。要否はSRE/Owner判断。
  - **2026-11-13改訂**: ユーザー決定（B-NF10-1）により初回本番は全停止＋専用Flyway job（rolling無し）とし、起動guardを誤操作防止として実装した（§14.1。testは `f0639edb` の対象限定実行と統合候補 `8000d94e` のCIで通過、§14冒頭）。token無しleaseが1件でもあれば全tenantのclaimを止める。本節の手順草案とSRE/DBA承認待ちの扱いは変えない。

### 10.2 手順草案

1. 全nodeでschedulerを停止する（`app.scheduling.enabled=false` で再起動、またはinstance停止）。
2. drainを確認する: §10.3のQ1で実行中lease（`processing_claimed_at` 非NULL）が0件、またはすべてがlease期限（30分）を超過していること。0件にならない場合は期限まで待ち、手動でclearしない。
3. backupを取得する（full dumpまたはPITR基準点）。
4. 全instanceの停止を確認してから専用Flyway jobでmigrationを適用し（2026-11-13訂正: アプリ起動時には適用しない）、`flyway_schema_history` でversion 185（本releaseの最新versionまで）の `success = 1` と失敗行0件を確認する。その後、新artifactだけを `app.scheduling.enabled=false` で起動する（起動時schema guardの成功logを確認する）。詳細は `deploy-runbook.md` §2〜§4。
5. 新artifactのschedulerを有効化する（`app.scheduling.enabled=true` で再起動）。
6. 事後確認としてQ2（孤児token）・Q3（token無しlease）を確認し、Q2は次回due/retryで自動回収されることを件数推移で確認する。

### 10.3 検出SQL（read-onlyのSELECTのみ）

DATETIME列はtenant timezoneのwall clock（既定Asia/Tokyo、DST無し）で保存される。以下はAsia/Tokyoのtenantを前提に `CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')` を現在時刻として使う。他のtimezoneのtenantはoffsetを読み替える。

```sql
-- Q1: 実行中lease（drain確認）。lease_age_minutesが30以上ならstale（新版が引き継ぐ対象）。
SELECT tenant_id, id, processing_logical_run_at, processing_claimed_at,
       processing_claim_token IS NOT NULL AS has_token,
       TIMESTAMPDIFF(MINUTE, processing_claimed_at, CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')) AS lease_age_minutes
FROM m_report_schedule
WHERE deleted_flag = 0 AND processing_claimed_at IS NOT NULL
ORDER BY tenant_id, id;

-- Q2: 孤児token（旧版completionの痕跡。新旧混在稼働の証拠）。
SELECT tenant_id, id, enabled, next_run_at, retry_scheduled_at, processing_logical_run_at, failure_count, updated_at
FROM m_report_schedule
WHERE deleted_flag = 0 AND processing_claimed_at IS NULL AND processing_claim_token IS NOT NULL
ORDER BY tenant_id, id;

-- Q3: token無しlease（V185以前のclaim、またはupgrade後に旧nodeがclaimした行）。
SELECT tenant_id, id, processing_logical_run_at, processing_claimed_at, updated_at
FROM m_report_schedule
WHERE deleted_flag = 0 AND processing_claimed_at IS NOT NULL AND processing_claim_token IS NULL
ORDER BY tenant_id, id;

-- Q4: 連続失敗。2026-11-13以降は12回目の失敗で自動停止する（enabled = 0、last_error_code = 'SCHEDULE_RETRY_EXHAUSTED'、§14.2）。
SELECT tenant_id, id, failure_count, retry_scheduled_at, last_error_code, updated_at
FROM m_report_schedule
WHERE deleted_flag = 0 AND failure_count >= 12
ORDER BY failure_count DESC, tenant_id, id;
```

### 10.4 復旧案草案（未承認・未実行）

- **孤児token（Q2）**: 新版が次回due/retry時刻にCASで自動回収するため、手動UPDATEは原則不要。件数を見かけ上ゼロにする目的のclearは行わない。やむを得ずclearする場合の草案は、scheduler停止中に限り期待tokenを一致条件にした1行CASとする（**DBA承認前に実行しない**）:
  `UPDATE m_report_schedule SET processing_claim_token = NULL WHERE tenant_id = ? AND id = ? AND processing_claimed_at IS NULL AND processing_claim_token = ?`
- **token無しlease（Q3）**: 旧node停止・drain確認後、lease期限の超過を待てば新版が `processing_claimed_at` / `processing_logical_run_at` / token=NULLの完全一致CASで引き継ぐ。手動でtokenを付与しない（ownerを捏造しない）。
  - 2026-11-13以降は起動guardがQ3を1件でも検出すると全claimを止めるため、引き継ぎには運用者がdrainを確認した時刻（`management-report.legacy-lease-drain-confirmed-before`）を設定して再起動する必要がある。手順は§14.1。
- **rollback**: Flywayはforward-onlyとし、V185を取り消すDDL（DROP COLUMN）は用意しない。旧artifactはtoken列を無視して動作し、再upgrade後に残った孤児tokenは自動回収される。切替方向にかかわらず§10.1の停止・drainを行う。
- **混在稼働が起きた場合**: 直ちに旧nodeのschedulerを停止し、Q1〜Q3を確認する。重複生成/配布の有無は `t_report_run.run_key`（一意）と `t_report_delivery` のdedupeで確認する。

### 10.5 DB前提

- 末尾へのADD COLUMNはMySQL 8.0.12以上でINSTANT対象（列位置指定を付けない理由）。本番MySQLの版とINSTANT可否はDBA確認待ち。対象表は小規模でINPLACEでも影響は限定的と想定するが、未計測。

### 10.6 監視とalert rule（草案。閾値・評価窓・重要度・通知先は**未決**。2026-11-17、RB-NF10）

本節は草案であり、承認・設定されたalert ruleではない。repoにalert配信基盤は無い。metricの定義は§14.5。

- **決める人**（いずれも未決・未取得。`deploy-runbook.md` §1.7 のNF10の行）:
  - SRE: 各ruleの閾値・評価窓・継続時間・重要度・通知経路。NTPの前提と許容する時刻差（A6）。
  - Report Owner: 自動停止（`RETRY_EXHAUSTED_DISABLED`）など業務側で受ける通知の宛先と、受けた後の対応手順（§14.2の復旧手順を使うか）。
- 下表の `<窓>`・`<閾値>`・`<継続>` は決める人が埋めるプレースホルダであり、本書の値ではない。「1件でも通知する」（`> 0`）にするかどうかも決める事項である。
- 2026-11-13版の§14.5にあった具体的な窓と閾値（`[5m]`・`[1h]`・`> 0`・10分継続等）は案として書いたもので、決定ではなかった。本節でプレースホルダに置き換えた。

| # | 検知したいこと | 条件式の骨格（PromQL） | 閾値・評価窓・継続 | 重要度 | 通知先 | 決める人 |
|---|---|---|---|---|---|---|
| A1 | 起動guardが全tenantのclaimを止めた／新旧version混在の痕跡（`LEGACY_LEASE_BLOCKED`・`LEGACY_LEASE_ACTIVE_BLOCKED`・`LEGACY_LEASE_GUARD_FAILED`・`ORPHAN_TOKEN_REPLACED`） | `sum by (event) (increase(ses_report_schedule_events_total{outcome="GUARD"}[<窓>])) > <閾値>` | 未決 | 未決（`ORPHAN_TOKEN_REPLACED` は混在の唯一の痕跡になり得る。§14.1） | 未決 | SRE |
| A2 | legacy leaseが残っている | `max(ses_report_schedule_legacy_leases) > <閾値>` が `<継続>` 続く | 未決 | 未決 | 未決 | SRE |
| A3 | 確定的な失敗（人の対処が要る）: `RETRY_EXHAUSTED_DISABLED`（自動停止。引き継ぎを数えた停止を含む）・`CLAIM_SQL_REJECTED`・`TENANT_DISPATCH_REJECTED` | `sum by (event) (increase(ses_report_schedule_events_total{outcome="DETERMINISTIC"}[<窓>])) > <閾値>` | 未決 | 未決 | `RETRY_EXHAUSTED_DISABLED` はReport Owner（宛先は未決）。他は未決 | SRE／Report Owner |
| A4 | 結果不明・重複実行の痕跡が続く（poison pill、completion結果不明、引き継ぎ、lease超過、所有権喪失）: `CLAIM_UNCERTAIN`・`COMPLETION_UNCERTAIN`・`STALE_LEASE_TAKEOVER`・`LEASE_OVERRUN`・`OWNERSHIP_LOST` | `sum by (event) (increase(ses_report_schedule_events_total{outcome="UNCERTAIN"}[<窓>])) > <閾値>` | 未決 | 未決 | 未決 | SRE |
| A5 | 自動で再試行される失敗が続く: `RUN_FAILED`・`TENANT_DISPATCH_FAILED` | `sum by (event) (increase(ses_report_schedule_events_total{outcome="RETRYABLE"}[<窓>])) > <閾値>` | 未決 | 未決 | 未決 | SRE／Report Owner |
| A6 | 本nodeの時計とDBサーバーの時計の差（§11 P2-2） | `max(abs(ses_report_schedule_clock_skew_seconds)) > <許容秒>` が `<継続>` 続く | 未決（許容値はNTPの運用前提と合わせて決める） | 未決 | 未決 | SRE |
| A7 | drain確認時刻の設定の外し忘れ | `increase(ses_report_schedule_events_total{event="LEGACY_LEASE_DRAIN_ACCEPTED"}[<窓>]) > <閾値>` | 未決 | 未決 | 未決 | SRE |

- 注意:
  - gauge（`ses_report_schedule_legacy_leases`・`ses_report_schedule_clock_skew_seconds`）は、ShedLockを取ってclaimを始めたnodeだけが更新し、他のnode・guardで止めたpoll・NF10 OFFでは前の値のまま残る。`max(...)` で評価し、event counterと併用する（§14.5）。
  - `outcome` はeventごとに固定である（`ReportScheduleMetrics.Event`）。eventを追加すると、そのeventのoutcomeの条件に自動で含まれる。
  - dueの無い期間はeventが出ないため、「schedulerが動いていない」ことはeventの不在だけでは判定できない。監視するかどうかと方法もSREが決める（未決）。

## 11. Owner判断待ち事項（挙動は変更しない）

> 2026-11-13改訂: P2-1・P2-4・P2-8・P3-3・起動guardはユーザー決定（B-NF10-1/B-NF10-2、`launch-decisions-20261113`）を受けて§14で実装した（testは `f0639edb` の対象限定実行と統合候補 `8000d94e` のCIで通過、§14冒頭）。下表の「現状の実装」列は2026-11-12時点の記述として残し、決定と現在の扱いを「2026-11-13」列に記す。

| ID | 論点 | 現状の実装 | 追加した防御（挙動不変） | 判断者 | 2026-11-13 |
|---|---|---|---|---|---|
| P2-1 | lease延長、副作用前fence | lease 30分固定・延長なし。30分超のrunは引き継がれ、生成/配布が重複し得る（run_key・delivery dedupeでの収束はMySQL並行で未検証）。disableは実行中runを止めない | WARN「processing lease期限を超えて実行しました」「期限切れprocessing leaseを引き継ぎました」 | Report Owner／技術責任者 | **決定済**: lease延長はしない。重複はrun_key・delivery dedupeで収束させ、実service・実MySQLのtestで証明する（§14.4。`ReportScheduleDispatchPolicyMySqlTest` が `f0639edb` の対象限定実行と統合候補 `8000d94e` のCIで通過） |
| P2-2 | lease時刻のDB時刻化 | 見送り（下記理由） | なし（NTP同期を運用前提とする） | SRE | 変更なし |
| P2-3 | tenant timezone変更時の扱い | schedule timezoneはAsia/Tokyo固定。tenant zoneと不一致なら `policyFixed` で失敗し、retryを繰り返す | WARN「tenant timezoneとschedule timezoneが一致しない…」 | Report Owner | 再計算方針は未決のまま。無限retryはretry上限（12回で自動停止）で有界になった |
| P2-4 | retry上限・DLQ | 上限なし（間隔は最大60分）。確定的失敗も無限retry | failure count 12以上でWARN、§10.3 Q4 | Report Owner | **実装**: 連続12回目の失敗で自動停止し停止理由を保存、ERROR＋metric、管理者の有効化で再開（§14.2） |
| P2-8 | claim失敗のmetric/alert | 確定的SQL失敗（文法・列不一致・制約・binding）は当tenantの今回pollを中断し、ERROR `errorCode=SCHEDULE_CLAIM_SQL_REJECTED` を出す。結果不明（commit失敗・接続障害・lock競合）は当該scheduleだけ見送る | ERRORログの分類のみ。metric/alert連携は未実装 | SRE | **実装**: Micrometer counter/gauge（§14.5）。alert ruleと配信先はSRE |
| P3-3 | 行単位の確定的失敗による同tenantの後続停止 | 当tenantの今回pollを中断（head-of-line blocking） | なし | Owner | **実装**: 当該行だけを見送り、残り候補は続行（§14.3） |
| — | 起動guard | 未実装 | なし | SRE／Owner | **実装**: token無しleaseで全claim停止（fail-closed）、drain確認設定（§14.1） |

P2-2を見送る理由: (1) lease列はnext/retryと同じtenant timezoneのLocalDateTimeで保存しており、DBの `CURRENT_TIMESTAMP` はsession `time_zone` に依存してtenant timezoneを知らない。混在させるとstale判定とretry判定の基準がずれる。(2) completion CASはclaimedAtの完全一致を条件にしているため、DB生成値を使うとclaim後に読み戻す2文構成が必要になる。(3) H2 testの `CURRENT_TIMESTAMP` はJVM timezoneとなり、テスト結果が環境に依存する。node間の時刻差sがある場合、実効leaseは30−s分になる。

**2026-11-17追記（RB-NF10）。下表の判断は変わらない（未決のまま）。**

| ID | 決まっていないこと | 決める人 | RB-NF10で追加したもの（挙動は変えない） |
|---|---|---|---|
| P2-2 | NTP同期の運用前提と、許容する本node・DBの時刻差。alertの閾値と通知先（§10.6 A6） | SRE（**未決・未取得**） | claimを始めるpollごとに `SELECT UNIX_TIMESTAMP()`（session time zoneに依存しないepoch秒）で本nodeとDBの時計の差を測り、gauge `ses_report_schedule_clock_skew_seconds`（node − DB、秒）へ出す。測定誤差（DB側の秒切捨て0.5秒と往復時間の半分）を差し引いた差が `management-report.clock-skew-warn-seconds`（コード既定5秒）を超えたら WARN `errorCode=SCHEDULE_CLOCK_SKEW_DETECTED`（差が続く間は60分に1回）。既定の5秒は測定誤差を超える最小限の実装上の値で、許容値の決定ではない。測定に失敗してもpollは止めない。lease・ShedLockの時刻源は変えない（上記の理由）。理由: 時刻差は実効leaseと起動guardの境界（§14.1）を直接ずらすが、従来は観測手段が無く、NTPの前提が崩れても気付けなかったため |
| P2-3 | tenant timezoneを変える時の方針（scheduleのtimezoneと `next_run_at` の再計算・移行手順を用意するか、変更を禁止するか） | Report Owner（運用手順はSREと合意。**未決・未取得**） | なし（文書のみ）。現状: schedule timezoneはAsia/Tokyo固定で、tenant zoneと違えば `policyFixed` で失敗し、連続12回（引き継ぎを含む、§14.2）で自動停止する。lease・retryの時刻はtenant zoneのwall clockで保存するため、変更の前後で両zoneの差だけstale判定とretry判定がずれる。zoneを先へ進める変更（例: UTC→Asia/Tokyo）では、実行中のleaseが直ちにstaleと判定されて他workerに引き継がれ、RB-NF10以降はその引き継ぎが連続失敗1回として数えられる（§14.2。RB-NF10 review P3-7）。方針が決まるまでの暫定の扱い（例: NF10をONにしたtenantのtimezone変更を止めるか）も同じく未決で、変えた場合の挙動は上記のとおりで保証しない |
| P2-8 | alert ruleの閾値・評価窓・重要度・通知先 | SRE／Report Owner（**未決・未取得**） | events counterに有限のtag `outcome`（確定的失敗 `DETERMINISTIC`・結果不明 `UNCERTAIN` 等、eventごとに固定）を追加し、alert ruleの草案を§10.6に置いた。tenant単位の中断を `TENANT_DISPATCH_REJECTED`（確定的）・`TENANT_DISPATCH_FAILED`（再試行される）で数える（§15） |

## 12. 独立レビュー指摘対応表（2026-11-12）

テスト名は追加・更新したtestである。`93c11457` で対象限定実行済（fast 288/0/0/0、MySQL 41件中1件FAIL。FAILは§13で修正）。修正後の `821076c0` では対象限定fast 292/0/0/0、MySQL 41/0/0/0、browser 1/0/0/0（PowerShell環境）。

| 指摘 | 対応 | 主なtest |
|---|---|---|
| P0-1 | `ManagementReportScheduler` に `SafeErrorPolicy` のimportを復元 | （コンパイル） |
| P0-2 | `ManagementReportSchedulerTest` を10引数constructorと新しいmapper署名へ更新 | `ManagementReportSchedulerTest` 全件 |
| P1-1 | §9。未占有判定を `processing_claimed_at IS NULL` に統一し、孤児tokenを期待token一致CASで置換 | `ReportScheduleClaimFenceMySqlTest`（孤児token 2件）、`ReportScheduleMapperH2SqlTest`、`ManagementReportSchedulerTest` |
| P1-2 | §7の8項目を `ReportScheduleClaimFenceMySqlTest`（`@Tag("mysql")`、`mysql-shard-1.txt` に登録）で実装 | 同class 17件 |
| P1-3 | fresh/legacyで列の存在と定義（`char(36)\|ascii\|ascii_bin\|YES`）を検証。V184→V185 upgradeはアプリ自身のFlywayでV184のlegacy lease行付きDBへ適用 | `FlywaySelfServiceSchemaSmokeTest`、`ReportScheduleClaimFenceMySqlTest` のV184 legacy lease case |
| P1-4 | API応答・create入力・entity JSON/toStringのtoken非露出、setEnabledのlease保持 | `ReportScheduleApiBoundaryTest`、`ReportScheduleServiceImplTest`、MySQLのenable/disable case |
| P1-5 | §10にリリース前提・検出SQL・復旧案草案（SRE/DBA承認待ち）。コードでの強制はしない | — |
| P2-1/2/3/4/8 | §11（Owner判断待ち。挙動不変の防御ログのみ追加） | 一部 `ManagementReportSchedulerTest` |
| P2-5 | `selectDue` retry分岐に未占有条件 | MySQL LIMIT枠case、H2 case |
| P2-6 | `updateEnabled` に `deleted_flag = 0`、version加算、updated_byのnot_null相当処理。応答は再読込 | `ReportScheduleServiceImplTest`、MySQL enable/disable case、H2 case |
| P2-7 | lease/retry/token/failure countを `FieldStrategy.NEVER`（INSERT/UPDATE）、next/last runをUPDATEのみNEVER | `ReportScheduleApiBoundaryTest`、MySQL entity insert/updateById case |
| P2-9 | `CHAR(36) ascii ascii_bin`、`AFTER` 削除、H2同期 | `FlywaySelfServiceSchemaSmokeTest`、MySQL V184 case・大文字token case |
| P2-10 | proxyを迂回する簡易constructorを削除。claim serviceは秒精度へ正規化 | MySQLのREQUIRES_NEW case、`ManagementReportSchedulerTest` のclaim service case |
| P2-11 | 本書の状態を「実装済・独立レビュー待ち・テスト未実行」へ更新、completion-matrixへ追記。公開 `runOne` 削除はcommit messageに明記 | — |
| P2-12 | latest期待値を `LatestMigrationVersion.LATEST` に集約（4 class・5箇所） | 4 migration test class |

### §7受入試験とtestの対応（`93c11457` の対象限定MySQLでは3.の行lock待ちcaseだけがFAIL、他は通過。修正後の `821076c0` では同classの17件が全件通過）

`ReportScheduleClaimFenceMySqlTest` のmethod名で示す。

1. 旧A success拒否: 「stale_takeover後の旧Aのsuccess_completionは0件でBの状態を一切変えない」「長時間実行中のAをBが引き継いでも旧Aのcompletionは0件でBの完了状態を上書きしない」（scheduler 2 instance）
2. 旧A failure拒否: 「stale_takeover後の旧Aのfailure_completionは0件でBの状態を一切変えない」
3. 同時競合・tenant: 「同一snapshotの同時claimはInnoDB行lock待ちの後で一件だけが勝つ」（2接続＋latch。holder接続ID・対象行のPRIMARY record lock・claim UPDATEを条件に `performance_schema.data_lock_waits` で待ち辺を観測。§13）、「同一snapshotから8並行でclaimしても一件だけがtokenを得る」（barrier）、「他tenantの行はtenant境界を越えてclaimもcompletionも更新もできない」
4. stale snapshot: 「due選択後に状態が変わった古いsnapshotではclaimできない」（NULL⇔非NULL遷移を含む13パターン）
5. 正常・retry: 「初回successは次回cronへ進みlease_token_logical_runをclearする」「失敗は失敗検知時刻基準でretryし月跨ぎretryは同一logicalRunAtと新tokenで完了する」。生成/配布の重複がrun_key/delivery dedupeで収束することは実serviceを使っておらず**未検証**（§11 P2-1）。ここではA/Bが同一の生成要求（template version・対象期間・schedule・cutoff）を出すことまでを確認する。
6. 長時間batch: 「長時間batchでもclaim時刻はscheduleごとの秒精度で保存され直後pollでstale扱いされない」（50件）
7. API/entity/log: `ReportScheduleApiBoundaryTest` 全件、「enable_disableは実行中leaseとtokenを保持しversionと更新者だけを進める」「entity経由のinsertとupdateByIdはlease_token_retry列を書き込まない」、scheduler log内にtokenが出ないこと（上記1と孤児token case）
8. deployment/migration: 「V184のlegacy_lease行はV185適用後もtoken無しで保持され期限切れ後だけ引き継げる」、`FlywaySelfServiceSchemaSmokeTest`（fresh/legacy列定義）、孤児token 2件（旧版SQLによる混在稼働の再現）。drain-before-rolloutは§10（草案、SRE/DBA承認待ち）。

## 13. 対象限定MySQL FAILの静的診断と修正（2026-11-12）

対象: `93c11457` の `ReportScheduleClaimFenceMySqlTest`「同一snapshotの同時claimはInnoDB行lock待ちの後で一件だけが勝つ」。症状は10.07秒で「contenderのInnoDB LOCK WAITを10秒以内に観測できませんでした」。contenderは10秒間完了せず（完了していれば別messageになる）、`information_schema.innodb_trx` には `LOCK WAIT` が一度も現れず、HikariPool timeout・Lock wait timeout・Deadlockのlogも無い。再実行はしていない。

**判断: testの観測方法の欠陥。claim fenceの並行語義（CAS・REQUIRES_NEW）の欠陥ではない。**

- 原因（推断・高確度）: `information_schema.INNODB_TRX` はInnoDBの `trx_i_s` cacheから返される。cacheは「最後の**読取**から0.1秒を超えて経過した」場合にだけ再取得され、読取のたびに最終読取時刻が更新される（MySQL 8.0 `trx0i_s.cc` の `can_cache_be_updated` / `trx_i_s_cache_end_read`、公式文書「Persistence and Consistency of InnoDB Transaction and Locking Information」）。旧helperは約20ms間隔で読み続けたため、contender投入直後の最初の読取（contenderのUPDATEがInnoDBへ届く前）のsnapshotが10秒間固定され、その後に発生したLOCK WAITを観測できなかった。contenderが10秒間完了しなかったこと、待ちが50秒のlock wait timeoutに達せずholder解放後に後続testが正常に進んだことと整合する。
- 同じrunで通過した `V182TenantScopeHardeningRehearsalMySqlTest` は `performance_schema.data_lock_waits` で行lock待ちを観測しており、同じ環境でこの観測方法は機能している（証拠: 同runの5/0/0/0）。
- 排除した仮説（静的確認）: (1) 接続取得待ち — Hikari上限20に対し同時使用は最大3（holder・contender・監視）で、監視側は毎回接続を取得できていた。返却時は待機中の要求へhandoffされる。(2) holderがlockを持たない — 唯一の `PlatformTransactionManager` はBoot既定のもので、MyBatis/JdbcTemplateと同じHikariDataSourceを使う。分離levelの設定は無く（application*.yml・Hikari・`@Transactional`）MySQL既定のREPEATABLE READ。PRIMARY const accessのUPDATEは一致の有無にかかわらず当該recordのX lockをcommitまで保持する。READ COMMITTEDのsemi-consistent readでも、最新committed版がsnapshotと一致するため待ちは発生する。(3) MDL — test中にDDLやLOCK TABLESは無く、holderとcontenderはいずれもSHARED_WRITEで両立する。(4) 監視接続の権限 — root（PROCESS権限あり）でinformation_schemaを参照しており、権限不足なら0件ではなく別の結果になる要素は無い。(5) Java側のlock — claim service・MyBatis・Spring transactionにcontenderを止める共有lockは無く、8並行testは同じclaim service経由で通過している。
- いずれも実行による確認はしていない（本診断はコードと仕様の静的読解）。

**修正（test観測のみ。製品コードは変更しない）**

- holderのtransaction内で `SELECT CONNECTION_ID()` を取得し、lock保持接続を特定する。
- `performance_schema.data_lock_waits` を `data_locks`（requester/blocker）と `threads` へjoinし、次をすべて満たす待ち辺が1件だけ現れるまで待つ: 対象DBの `m_report_schedule`、`PRIMARY`、`RECORD`、requesterは `WAITING` のX lock、blockerは `GRANTED` のX lock、`LOCK_DATA` が対象id、blockerの接続IDがholder、requesterはholderでも監視接続でもない、requesterの実行中SQLがclaim UPDATE（`UPDATE m_report_schedule SET processing_logical_run_at…`）。
- contender投入前に待ち辺が0件であること、観測時点でcontenderが未完了であること、holder commit後に待ち辺が残らないことも確認する。10秒の上限は変えない。観測できない場合はdata_lock_waits・data_locks・metadata_locks・実行中SQL先頭を診断として失敗messageへ出す。
- 強度: 旧helperは「どこかのtransactionがLOCK WAIT」という条件だったが、新helperは「holder接続が保持する対象行のrecord lockを、別接続のclaim UPDATEが待っている」ことを要求するため、同等以上に厳しい。holder=1件、contender=CAS 0件（null）、tokenはholderのもの、という結果assertionは変更していない。

**修正後の実行結果**

- `821076c0` の対象限定MySQL（7 class）は41/0/0/0で、本caseを含む `ReportScheduleClaimFenceMySqlTest` は17/0/0/0、失敗時の診断段の出力は無かった（統括run記録 `nf10-821076c0-mysql-targeted`）。
- 上の原因はなお推断である。「`93c11457` の失敗当時、contenderが実際に行lockを待っていたか」は実行で確認しておらず、UNKNOWNのまま（第3回独立レビューも同旨）。

## 14. 運用方針の実装（2026-11-13、W2-B8）

状態: **実装済・r2は対象限定テスト通過・統合候補 `8000d94e` のCI 7 job通過。** COMPLETE/PASSではない。production/運用gateの承認、SRE/DBA承認（§10）、Report Owner署名を意味しない。

- r1: `30e058ce1916af52193bdbf4f7fa430513d2a8ae`（基準HEAD `1836eb73…`）。独立レビュー（静的）PASS、Runner対象限定 fast 324/0/0/0・MySQL 23/0/0/0（DispatchPolicyは別seed 20261113でも4/0/0/0）。証拠は統括のrun記録 `nf10-30e058ce-*`。
- r2: 上記の上に1 commit（独立レビュー `w2-nf10-policy-30e058ce-review.md` のP2-1・P3-1・P3-3・P3-8対応と文書訂正）。`f0639edbca018b91a4cfda6df5fefc7652bd2665` で独立レビュー（静的）PASS、Runner対象限定 fast 344/0/0/0・MySQL 24/0/0/0（DispatchPolicyは別seed 424242でも5/0/0/0）。証拠は統括のrun記録 `nf10-f0639edb-*`。
- 統合: r2は `07988f40` で統合候補へ取り込まれ、第1・2波の全batchを含む `8000d94e` でCI 7 job（`ReportScheduleDispatchPolicyMySqlTest` が属するMySQL shard 2を含む）がすべて通過した（run 43387006667）。その後の統合候補 `430f0a2a`・`0fc507b8`・`00d1e539` でもCI 7 jobが通過している。`1be03bea` のMySQL shard 2失敗はNF10外のprivacy test（lock待ち観測の `LOCK_DATA` 依存）が原因で、HYG-0（`00d1e539`）で修正済み。
- branch `codex/snf-w2-nf10-scheduler-policy-20261113`。
- 入力となった決定（`launch-decisions-20261113`、ユーザー決定。各責任者の署名の代わりではない）:
  - B-NF10-1: 初回本番は全停止＋専用Flyway job（rolling無し）。起動guardは誤操作防止として実装する。
  - B-NF10-2: 重複配布はrun_key dedupeで受容する（testで証明する）。retryは12回で計画を停止しalertする。確定的SQL失敗は当該行だけを見送り、tenant全体を止めない。
  - 2026-11-13追加（r2）: 同一論理runのcutoff（速報/確定）は最初の判定で固定し、引き継ぎとretryでも再判定しない。月次締めを跨いでも速報と確定を1回ずつ配布しない（決定時は「確定版は次の周期で扱う」と記したが、実際の扱いは§14.4のとおり手動生成で行う）。「12回」は初回実行を含む連続失敗12回のまま。
  - NF10は機能スイッチ `app.features.nf10.enabled`（既定OFF）で守り、Report Owner署名後にONにする。
- DDL・messages・application*.yml・機能スイッチ実装は変更していない。

### 14.1 起動guard（legacy lease検出、A-NF10-3）

- **判定**: 毎poll、`dispatchDue` の入口（機能スイッチ確認の後、tenant処理の前）で、inventory内の全tenantについて `enabled = 1 AND deleted_flag = 0 AND processing_claimed_at IS NOT NULL AND processing_claim_token IS NULL` を数える（`ReportScheduleMapper.countLegacyLeases`。§10.3 Q3に `enabled = 1` を加えた条件）。新版のclaimは必ずtokenを保存するため、1件でもあれば旧版schedulerの痕跡である。旧版のcompletionはtoken条件を持たず新ownerの状態を上書きし得るため、tenant単位ではなく全tenantのclaimを止める。
- **drain未確認（既定）**: 1件以上なら全tenantのclaimを開始しない。ERROR `errorCode=SCHEDULE_LEGACY_LEASE_DETECTED`、metric `LEGACY_LEASE_BLOCKED`、gauge `ses_report_schedule_legacy_leases`。
- **drain確認時刻**（r2で真偽値から変更。`management-report.legacy-lease-drain-confirmed-before`、offset付きISO-8601、例 `2026-11-13T10:00:00+09:00`、コード既定は空＝未確認）:
  - 受け入れ境界は「確認時刻」と「本instance起動時刻（scheduler bean生成時刻）」の早い方。tenant timezone・秒精度へ変換し、境界以降に取得されたlegacy leaseが1件でもあれば止める。ERROR `errorCode=SCHEDULE_LEGACY_LEASE_ACTIVE`、metric `LEGACY_LEASE_ACTIVE_BLOCKED`。
  - 境界より前に取得されたleaseだけなら許可し、lease期限（30分）後に既存のtoken=NULL完全一致CASで引き継ぐ。WARN `errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_ACCEPTED`、metric `LEGACY_LEASE_DRAIN_ACCEPTED`。
  - 解釈できない値（offset無し等）は未確認として止める。ERROR `errorCode=SCHEDULE_LEGACY_LEASE_DRAIN_CONFIG_INVALID`、metric `LEGACY_LEASE_BLOCKED`。
- **検出の失敗**: 検出SQLの `DataAccessException`（V185未適用でtoken列が無い等）は全claimを止めてERROR `errorCode=SCHEDULE_LEGACY_LEASE_GUARD_FAILED`。それ以外の例外（tenant inventory未設定等）もmetric `LEGACY_LEASE_GUARD_FAILED` を記録したうえで呼出元へ伝播し、claimは開始しない（r2、review P3-3）。
- **drain確認が必要な理由**: legacy leaseは「旧版nodeが実行中」と「旧版nodeは停止済みでleaseだけが残った」をDBから区別できない。
  - 確認なしで止め続けると、crashした旧版nodeのleaseが永久にclaimを止める（guardが引き継ぎも止めるため自然には解消しない）。
  - 逆に期限後の自動引き継ぎを許すと、旧版nodeが稼働中のまま新版が引き継ぎ、旧版のtoken条件なしcompletionが新ownerの状態を上書きする（§9の限界）。
  - そこで「停止済み」の確認だけを人に委ねる。r1の真偽値は、戻し忘れたまま再起動するとその時点の全leaseを再び受け入れていた（review P3-1）。r2は運用者が書いた確認時刻を境界にするため、戻し忘れても確認後に旧版が取得したleaseは受け入れない。ただしnode間の時刻差sがあると、境界直後s秒間の旧版leaseを境界前とみなし得る（NTP同期を前提とする）。
- **限界**（review P3-2で補足）:
  - ShedLock名が新旧で共通のため `dispatchDue` は新旧nodeの間で直列になる。新版が旧版の実行中leaseを見るのは、旧版の実行が `lockAtMostFor`（10分）を超えた場合か旧版nodeがcrashした場合に限られ、実際に検出するのは主にcrashの残りである。旧版nodeがclaimしていない（idle）間は検出できない。
  - 旧版がtoken付きのstale leaseや孤児token行を引き継ぐと、tokenを更新しないためlegacy leaseとしては数えられない。事後に `ORPHAN_TOKEN_REPLACED` のWARNとして現れるだけなので、同eventは重要度の高いalertの候補とする（§10.6 A1。2026-11-17訂正: 重要度は案であり未決。決める人はSRE）。
  - inventory外tenantの行とenabled=0の行は数えない（schedulerがclaimしない行のため。再有効化すれば再び数える）。
  - 全停止デプロイ（B-NF10-1）が一次防御で、guardは誤操作の検出と被害拡大の防止である。
- **復旧手順（草案、SRE/DBA承認待ち）**:
  1. ERRORまたはalertを検知したら、旧版artifactが全nodeで停止していることを確認する。
  2. §10.3 Q1・Q3で残っているleaseを確認する。手動UPDATEでclearしない。
  3. 停止を確認した時刻を `MANAGEMENT_REPORT_LEGACY_LEASE_DRAIN_CONFIRMED_BEFORE`（例 `2026-11-13T10:00:00+09:00`）へ設定して新版を再起動する（設定は起動時に読む）。
  4. lease期限後の次pollで引き継がれ、gaugeが0になることを確認する。
  5. 設定を外して再起動する（外し忘れても、確認時刻以降の新しいlegacy leaseは止まる）。

### 14.2 retry上限（A-NF10-4 / P2-4）

- 連続失敗回数（`failure_count`、初回実行の失敗を含む）が12回に達したfailure completionで、所有権CASと同じUPDATEにより `enabled = 0`、`last_error_code = 'SCHEDULE_RETRY_EXHAUSTED'` を保存する。`retry_scheduled_at`・`processing_logical_run_at`・`failure_count`（12）・`last_error_message` は保持する。
- **DDL不要**: 既存列の組合せ（`enabled` / `last_error_code` / `failure_count` / `retry_scheduled_at`）で、停止・停止理由・再開位置を表す。新列は使わない。
- **SET句の順序に依存する**: MySQLの単一表UPDATEはSET句を左から評価し、後ろの式が更新後の値を参照する（H2は更新前の値）。`failure_count` を参照する式を `failure_count` の代入より前に置いている。r1のcommit messageにある「SET句左から評価に依存しない順序」は誤記で、正しくは「左から評価に依存し、その評価順で正しく動く順序」（review P3-4）。順序の誤りはMySQLのtest（11回目で停止しないこと）で検出できる。H2のtestでは原理上検出できない。
- **通知**: ERROR「連続失敗が上限（12回）に達したためscheduleを自動停止しました」、metric `RETRY_EXHAUSTED_DISABLED`。
- **可視化**: `GET /api/management-reports/schedules` の `enabled=0`、`lastErrorCode=SCHEDULE_RETRY_EXHAUSTED`、`failureCount=12`、`retryScheduledAt`。schedule一覧の画面は現状存在しないため、UI表示は後続。
- **手動再開**: 管理者の `POST /api/management-reports/schedules/{id}/enable`。`updateEnabled` は「`enabled = 0` かつ `last_error_code = 'SCHEDULE_RETRY_EXHAUSTED'`」の行を有効化する時だけ `failure_count = 0` に戻す。retry予定から同一論理runを再開する。停止理由のerror codeは次のcompletionまで保持し、successでclear、failureで上書きする。それ以外のenable/disableは回数を変えない。自動停止後の初回completion前は手動のdisable→enableでも停止理由codeが残るため、INFOログは「停止理由が残るscheduleを有効化」という文言にした（review P3-9）。
- **「12回」**: ユーザー決定により「初回実行を含む連続失敗12回」とする。変える場合は定数 `ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES` に加え、testの固定値（`ManagementReportSchedulerTest` の失敗回数 `{10, 11}`、`ReportScheduleDispatchPolicyMySqlTest` の失敗回数10・退避55/60分）も直す（review P3-5）。上限はSQLへ定数として埋め込み、設定値にはしない。
- 再開後、停止中に過ぎた `next_run_at` は既存の扱い（成功ごとにcronの次回へ1回分ずつ進む）に従う。
- **同一論理runで確定的に失敗する原因の復旧（review P3-7）**: 次の原因は、有効化しても同じ論理runで再び12回失敗して（約5.5時間後に）停止する。
  - 配布先の変化（`recipientPreviewStale`。§14.4の前提が崩れた場合）、scope変化（`scopeChanged` / `scopeSnapshotInvalid`）、template版の非公開化（`templateVersionNotPublished`）、tenant timezoneの不一致（`policyFixed`）、確定で固定済みの未完了runの締め解除（`closingRequired`）。
  - 手順:
    1. 原因を元に戻せる場合（権限・組織所属の復元、template版の再公開、締めの再確定、timezone設定の復元）は、戻してから有効化する。
    2. 戻せない場合は、停止したscheduleを無効のまま残し、同じtemplate版で新しいscheduleを作成する。作成時のscopeを現在のprincipalで保存し、`next_run_at` は次の周期になる。定期runのrun_keyはscheduleごとに分かれるため（§14.4）、新しいscheduleは旧scheduleのrunの影響を受けない。取り逃した月は、明示再生成（`POST /api/management-reports/runs/{runId}/regenerate`）または手動生成・配布で扱う。
    3. `next_run_at` をDBで直接進める操作は行わない（行う場合はDBA承認の手順として別途定める）。
  - コード方針（未実装）: 管理者が「当該論理runを見送り、cronの次回へ進める」操作（skip API。`processing_*` が空の行だけを対象に `next_run_at` を次回へ進め、`retry_scheduled_at` / `processing_logical_run_at` / `failure_count` / 停止理由をclearする専用UPDATE）を追加すれば、新schedule作成は不要になる。監査とUIを伴うため本批では実装しない。
- **限界（review P3-6）**: ~~retry上限が数えるのはfailure completionだけで、poison pillやcompletion結果不明では引き継ぎが無限に続く。引き継ぎ回数の上限にはDDLが要る~~
  - **2026-11-17改訂（RB-NF10、§15）**: 引き継ぎも連続失敗1回として数える。DDLは不要で、既存の `failure_count` を使う。
    - 期限切れleaseの引き継ぎclaimは、completionに至らなかった前の実行を失敗1回として、claimと同じUPDATEで `failure_count` を加算する（`ReportScheduleMapper.claimDue`。占有中の行だけに一致する条件で加算し、SET句の評価順に依存しない）。引き継いだ実行が成功すれば0へ戻り、失敗すればさらに1回加算される。
    - 引き継げば上限（12回）に達するleaseは引き継がず、同じsnapshot CASで停止する（`markTakeoverExhausted`）。停止の状態はfailure completionの上限到達と同じで（`enabled = 0`、`SCHEDULE_RETRY_EXHAUSTED`、retry予定は5分×12＝60分後、論理runを保持、lease/tokenをclear）、通知もERROR（`cause=STALE_LEASE`）＋ `RETRY_EXHAUSTED_DISABLED`。管理者の有効化で同一論理runから再開する。tokenをclearするため、期限後も動いていた旧workerのcompletionは0件になる。
    - **停止の原因の見分け方**（RB-NF10 review P3-5）: 停止理由code `SCHEDULE_RETRY_EXHAUSTED` は管理者の再開条件（`updateEnabled` の0戻し）のため両方の停止で共通にし、`last_error_message` で区別する。引き継ぎによる停止は `error.managementReport.scheduleStoppedByLeaseExpiry`（4言語のbundleに登録したkey）にする。生成失敗の completion は、加算後の回数が上限未満（再試行で続ける）なら `error.managementReport.scheduleGenerationFailed`、上限に達して同じ UPDATE で停止したなら `error.managementReport.scheduleStoppedByGenerationFailure`にする（2026-11-17、RB-HYG。停止したことを文言で明示するため2つに分けた（RB-HYG review P3-4。claim の CAS で加算後の回数は DB 値と一致する）。review RB-NF10 P3-D2 の前は `safeJobMessage` の既定分岐の日本語固定文言で、引き継ぎ停止の key と形式がそろわず意味も違っていた）。いずれも key を保存し、画面を作るときは `SES.i18n.t` で訳す。schedule一覧APIの `lastErrorMessage` とERRORログの `cause=STALE_LEASE` で区別できる。DDLは要らない。
      - 引き継ぎで回数だけが1増えた行（停止前）は、`last_error_code`・`last_error_message` が前回の値のまま残る（引き継ぎのclaimは回数以外の失敗の列を書かない）。一覧では「失敗回数は増えたが理由は前回のまま」に見える。引き継ぎの事実はWARN「期限切れprocessing leaseを引き継ぎました…連続失敗1回として数えました」と `STALE_LEASE_TAKEOVER` で確認する。
    - 結果不明の停止は、行を読み直して**この試行が作った停止状態**を確かめた時だけ通知する。判定は、無効・停止理由code・lease無し・同じ論理run・この試行のretry時刻（秒精度）・この試行の加算後の回数の全一致で、retry時刻は試行ごとの現在時刻から決まるため、pollがShedLockの `lockAtMostFor`（10分）以内でnode間に直列の間は他workerの停止と区別できる。pollが10分を超えて他nodeのpollと重なると、同じ秒に同じsnapshotで同じ行を扱い、自分の停止SQLがcommitされずに例外となり他nodeがcommitした場合だけ一致し、重複して通知する（停止状態は正しく、重複するのはERRORとmetricの1回。review RB-NF10 P3-D1）。読み直しの失敗は判定せず、`COMPLETION_UNCERTAIN`／`CLAIM_UNCERTAIN` の監視（§10.6 A4）と§10.3 Q4に委ねる。
      - `markFailure` が上限で結果不明: commitされていなければleaseが残り、期限後の引き継ぎが同じ失敗を数えて停止・通知する。lease期限は**読み直す直前の現在時刻**で判定し（RB-NF10 review P3-2。markFailureは接続・lock待ちの後で例外になり得るため、失敗検知時刻では期限切れ後の他workerの停止を見落とす）、期限を過ぎていれば判定しない。
      - 引き継ぎ停止（`markTakeoverExhausted`）が結果不明（RB-NF10 review P3-1）: 確定的なSQL失敗（commitされない）は読み直さない。それ以外は読み直し、commit済みなら通知する（行は無効になっているため、ここで通知しないと誰も通知しない）。commitされていなければ期限切れleaseのまま残り、次のpollの引き継ぎ停止が通知する。
  - **数え方の範囲**（RB-NF10 review P3-4）:
    - 回数は**同一論理runの中でだけ**累積する。success completionが `next_run_at` を進めて0へ戻し、失敗も引き継ぎも同じ論理runを続けるため、周期（月）を跨いで「遅い実行」が累積することはない。停止に至るのは、同じ論理runで成功できない試行が12回続いた場合だけである。
    - 単一instanceでは、実行中の処理を同じnodeが引き継ぐことはない（`@Scheduled` の同じtaskは重ならず、次のpollは今回の終了後になる）。引き継ぎが起きるのは、process停止・再起動の後と、多instanceで他nodeのpollがShedLockの `lockAtMostFor`（10分）後にstaleのleaseを見た場合である。
    - 変更前は、同じ状況（毎回completionに至らない試行）が無限の引き継ぎと重複実行になっていた。変更後は約6時間（lease 30分 × 残り回数）で停止し、`RETRY_EXHAUSTED_DISABLED` で通知される。
    - 典型の長い実行（A が30分を超え、B が引き継いで1回数える）は、B が A の作った成功runを再利用してすぐ成功するため（§14.4）、回数は0へ戻る。これは B の開始時に A が生成を終えている場合で、A の生成途中に B が始まると B は重複 insert で失敗して回数が2になり、retry の成功で0へ戻る（review RB-NF10 c837e5d0 §2.4）。
  - 残る限界:
    - poison pillで止まるまでの時間は約 lease 30分 × 残り回数（初回からなら約6時間）である。
    - 同じ論理runで成功できない試行のたびに30分を超える場合（再利用できない長い実行が続く場合）は、正常な実行でも12回で停止する。事前には `LEASE_OVERRUN` で見える。
    - 悪化する境界: 同じ論理runで既に11回の実失敗がある時、多instanceで30分を超える正常な実行（実際には配布済み）が他nodeに停止と判定され、元の実行のsuccess completionは `OWNERSHIP_LOST` になる。管理者の再有効化が要る（再実行はrun_key・delivery dedupeで収束する）。
    - 時刻・timezoneとの関係: tenant timezoneを先へ進める変更（例: UTC→Asia/Tokyo）をすると、実行中のleaseは直ちにstaleと判定されて引き継がれ、失敗1回として数えられる（§11 P2-3）。node間の時刻差sがあると、他nodeはs分だけ早くstaleと判定する（§11 P2-2）。
  - **Report Ownerの確認（未取得）**:
    - 「12回」の数え方が「同一論理runの中の、初回を含む連続失敗（completionに至らずlease期限を超えた試行を含む）12回」になった。P3-5の数え方の確認と合わせて確認する。
    - **正常な実行が30分を超え得るなら、数え方の定義ではなくB-NF10-2（lease延長はしない）を見直す**（lease更新〔heartbeat〕またはより長いlease）。統括の判断（2026-11-17）で数え方の定義は現状のまま（「同一runが引き継がれた時だけ数える」等には変えない）とした。生成・配布の所要時間の見込みはReport Ownerが示す（未取得）。

### 14.3 確定的claim SQL失敗（P3-3 / review P3-8）

- 当該行だけを見送り、同tenantの残り候補は続行する。ERROR `errorCode=SCHEDULE_CLAIM_SQL_REJECTED`、metric `CLAIM_SQL_REJECTED`。
- claimが成立していないため、行へ失敗を記録できない。r2では見送った行をinstance内メモリで待機させる（5分×連続回数、上限60分）。待機中の行はdue一覧から除外し、その件数だけ多く取得するため（LIMIT 50＋待機件数、待機件数は1 tenant 500件まで）、失敗行がLIMIT枠を占有し続けて健全な行が処理されない状況を防ぐ。待機中はERRORを繰り返さない。claim SQLが実行できた時点（成功・CAS 0件）で記録を消す。待機が明けてから24時間選ばれない記録は捨てる。
- 待機記録はinstanceごとで再起動で消える（ShedLockで交互にpollするnodeは別々に待機する）。
- `MyBatisSystemException`（`UncategorizedDataAccessException` の子）は確定的、`UncategorizedSQLException` は結果不明に分類され、扱いが揃っていない。どちらも当該行を見送るだけで制御の違いは待機の有無とlog/metricの分類だけである（review P3-8、未変更）。
- schema不整合のうち、起動guardの検出SQLが参照する列（`processing_claimed_at` / `processing_claim_token` など）の欠落だけは、guardが先に失敗して全claimを止める。それ以外の列の不整合は行ごとに見送られる。

### 14.4 重複配布の収束（A-NF10-5 / P2-1）

- lease延長は行わない（B-NF10-2）。stale takeoverやretryで同一論理runが重複実行されても、実serviceのrun_key（`uk_report_run_key`）とdelivery（`uk_report_delivery_recipient` / `uk_report_delivery_dedupe`）で配布を1回に収束させる。
- **cutoffの論理run固定（r2、ユーザー決定）**: 定期runのrun_keyは `schedule ID・template版・対象月・scope hash` から作り、cutoffを含めない（`ReportSnapshotServiceImpl.buildRunKey`。手動生成のキーは従来どおりcutoffを含む）。
  - schedulerは現在の締め状態で速報/確定を「要求」するが、生成serviceは同じrun_keyの既存runがあればそのcutoffで再利用・再生成し、再判定しない。
  - 速報を配布した後、completion前に月次締めが確定して引き継ぎ・retryが走っても、確定runは作らず速報runを再利用する。その対象月の確定版は自動では作られず、配布もされない。
  - **2026-11-14訂正（review P3-r2-1）**: 改訂前は「確定版は次の周期で扱う（必要なら明示再生成）」と記していたが、どちらも成り立たない。
    - 月次cronの次の周期は次の対象月（`ManagementReportScheduler` は実行月の前月を対象にする）を扱うため、当該対象月の確定版は作らない。
    - 明示再生成（`POST /api/management-reports/runs/{runId}/regenerate`）は元runのcutoffを引き継ぐため、速報runから再生成しても速報のままである。
    - 確定版が必要な場合は、月次締めの確定後に `POST /api/management-reports/runs` で `cutoffKind=確定` を指定して手動生成し、`POST /api/management-reports/runs/{runId}/deliver` で手動配布する（手動生成のrun_keyはcutoffを含むため、定期runの速報runとは別runになる）。
    - 既存挙動として、製品仕様の月次cron（毎月1日0時）は通常は前月の月次締めより前に実行されるため、定期配布は実質的にほぼ速報になる。確定版の配布を定期運用に含めるかはReport Ownerの判断事項である。
  - 最初のrunが未完了（PARTIAL等）でも、retryは固定したcutoffでsectionを作り直す。確定で固定済みの未完了runは、締め解除中は `closingRequired` で失敗する（速報へ切り替えない）。
  - 別cutoffでの並行作成も同じrun_keyの一意制約で1件に絞られる。
  - 副次効果: scheduleごとにキーが分かれるため、同じtemplate版・scopeの手動生成や別scheduleのrunを再利用して `deliverScheduled` のsystem context検証で拒否され、論理runが進まなくなる既存の問題も解消する。
  - cronを月次以外にした場合は、同じ対象月の後続の論理runも同じrunを再利用する（同じscheduleでは1対象月1配布）。製品仕様のcronは月次（`0 0 0 1 * *`）である。
  - DDLは不要。既存列（`t_report_run.schedule_id` / `cutoff_kind`）とrun_keyの構成だけで実現する。既存DBに旧形式キーの定期runがある場合、同じ対象月を再実行すると新形式キーのrunが別に作られ得る。初回本番は空DBのため該当しない（開発DBでは対象月の再実行に注意）。
- **収束の前提**（review P2-1）: 同一論理runの実行の間で、次が変わらないこと。
  - recipient preview（配布先と各配布先のscope）。変わると既存runの再利用が `recipientPreviewStale` で拒否される。二重配布にはならないが、その論理runは進まなくなる（復旧は§14.2）。
  - template版（scheduleは作成時の版を保持し、変更APIは無い）。
  - cutoffはr2で固定したため前提ではない。
- **検証**: `ReportScheduleDispatchPolicyMySqlTest`（`@Tag("mysql")`、`mysql-shard-2.txt`、5件）。
  - 重複実行: Aが実生成・実配布の後、completion前にlease期限を超えて停止する。Bが引き継ぎ、実生成はrunを再利用（`reused=true`）、実配布は既存deliveryを再利用する。delivery・アプリ内通知・outboxは各1件、文書登録は1回、BのdeliverScheduledは1回、Aのcompletionは0件。
  - 締めを跨ぐ重複実行（r2）: 上の配置で、Bの引き継ぎ前に月次締めを確定する。Bは「確定」を要求するが、速報runを再利用し、確定runは作られず、配布は1回。
  - 並行投入: 同一command（同一run_key）を8並行で生成・配布する。run・delivery・通知・outboxは各1件。失敗した投入は一意制約またはlock競合による拒否だけで、transactionごとrollbackされる。その後の再投入も同じrun・deliveryへ収束する。
- **mockの範囲**: recipient preview（配布先の評価。収束の前提として固定値）、AR aging正本値（section値）、PDF描画とstorage（`ReportDeliveryDocumentRegistrar`）、外部webhook。月次締めはr2から実 `MonthlyClosingService` が `t_monthly_closing` を読む。r1の「mockはdedupe境界の外側だけ」という記述は、締め状態とpreviewがrun_keyと再利用判定に関わるため不正確だった。
- 並行時の文書artifactの重複（document business key）は対象外で、UNKNOWN。

### 14.5 metric / alert（P2-8）

- 既存のMicrometer（`/actuator/prometheus`）へ接続する。tagは有限のevent種別と、eventごとに固定の結果区分だけで、tenant/schedule ID・token・PIIを載せない。
  - `ses_report_schedule_events_total{event=...,outcome=...}`（2026-11-17、RB-NF10で `outcome` tagと2 eventを追加し16種）。`outcome` はreview P2-8の「確定的な失敗と結果不明の区別」をlabelで表すもので、eventから一意に決まるため系列数はevent数を超えない。
    - `SUCCEEDED`: `RUN_SUCCEEDED`
    - `RETRYABLE`（失敗を記録し、自動で再試行される）: `RUN_FAILED` / `TENANT_DISPATCH_FAILED`
    - `DETERMINISTIC`（再試行しても変わらない失敗、または上限で停止した。人の対処が要る）: `RETRY_EXHAUSTED_DISABLED` / `CLAIM_SQL_REJECTED` / `TENANT_DISPATCH_REJECTED`
    - `UNCERTAIN`（DB上の結果を確認できない、結果不明のまま引き継いだ、重複実行の痕跡）: `CLAIM_UNCERTAIN` / `COMPLETION_UNCERTAIN` / `STALE_LEASE_TAKEOVER` / `LEASE_OVERRUN` / `OWNERSHIP_LOST`
    - `GUARD`（起動guardが全claimを止めた、新旧version混在の痕跡）: `LEGACY_LEASE_BLOCKED` / `LEGACY_LEASE_ACTIVE_BLOCKED` / `LEGACY_LEASE_GUARD_FAILED` / `ORPHAN_TOKEN_REPLACED`
    - `INFO`: `LEGACY_LEASE_DRAIN_ACCEPTED`
  - `ses_report_schedule_legacy_leases`（gauge）: 直近に起動guardの集計が成功したpollで検出したlegacy lease件数。集計が失敗したpoll、NF10 OFF、ShedLockを取得しなかったnodeでは前の値のまま残る（review P3-3）。node間で値が古いままになり得るため、alertは `max(...)` で評価し、event counterと併用する。
  - `ses_report_schedule_clock_skew_seconds`（gauge、2026-11-17）: claimを始めたpollで測った本nodeの時計 − DBサーバーの時計（秒、符号付き）。更新されるのはclaimを始めたpollだけで、他のnodeでは前の値のまま残る（§11 P2-2）。
- alert rule: ~~推奨alert rule（案）の表~~ 2026-11-17に§10.6へ移した。閾値・評価窓・重要度・通知先はプレースホルダとし、決める人（SRE／Report Owner）を明記した（いずれも未決）。旧表の窓と閾値は案であり決定ではなかった。

### 14.6 機能スイッチとdelivery（A-NF10-6）

- 新しいスイッチは追加しない。NF10がOFFなら `dispatchDue` は起動guardを含め何も実行せず、schedule表への問合せも無い（ShedLockのlock表は毎分更新される。review P3-10）。手動の生成・配布・retry・replay・downloadのAPI（`/api/management-reports/**`）は `FeatureSwitchFilter` が404にする。
- 残る隙間: OFFにする前に通知outboxへ投入済みの通知は、汎用の `NotificationOutboxScheduler` が送る（`.kiro/specs/production-launch/feature-switches.md` §4.5）。delivery専用スイッチは作らない決定のため、OFF前に該当の配布を取消す運用で扱う。

### 14.7 test対応（r2は `f0639edb` の対象限定実行と統合候補 `8000d94e` のCIで通過）

| 方針 | test |
|---|---|
| 起動guard | `ManagementReportSchedulerTest`（drain未確認で停止、起動後leaseは確認時刻があっても停止、確認時刻より後のleaseは起動前でも停止、確認時刻より前のleaseだけなら引き継ぎ、確認時刻を解釈できなければ停止、検出SQL失敗で停止、DB以外の例外はmetricを残して伝播）、`ReportScheduleMapperH2SqlTest`（`countLegacyLeases` の条件）、`ReportScheduleDispatchPolicyMySqlTest`（実MySQLで4段階） |
| retry上限 | `ManagementReportSchedulerTest`（11回目は通知せず12回目でERROR＋metric、tagはeventのみ）、`ReportScheduleMapperH2SqlTest`（停止と再開のSQL）、`ReportScheduleDispatchPolicyMySqlTest`（MySQLのSET句評価、一覧での停止理由、管理者の再開と同一論理runの再実行）、`ReportScheduleApiBoundaryTest`（一覧JSONの停止理由） |
| 確定的SQL失敗 | `ManagementReportSchedulerTest`（先頭行の失敗後も次の行を実行、失敗50行が待機中はLIMIT枠を占有せず健全な行を処理、待機が明けると再試行して待機を延ばす） |
| cutoffの論理run固定 | `ReportSnapshotServiceImplTest`（締め確定後の引き継ぎでも速報runを再利用し手動生成とキーを共有しない、未完了runのretryは固定cutoffでsectionを作る）、`ReportScheduleDispatchPolicyMySqlTest`（締めを跨ぐ重複実行） |
| 重複配布の収束 | `ReportScheduleDispatchPolicyMySqlTest`（重複実行・締めを跨ぐ重複実行・8並行） |
| 引き継ぎを含むretry上限（RB-NF10、§15） | `ManagementReportSchedulerTest`（引き継ぎを1回として数えretry待機と上限判定に含める、上限に達する引き継ぎは生成せずに停止・通知、停止CASが0件・確定的失敗なら通知も読み直しもしない、結果不明のcompletion・引き継ぎ停止は読み直してこの試行の停止を確かめた時だけ通知、読み直し時点でlease期限を過ぎていれば判定しない、claim serviceの数え方と停止の入力検証）、`ReportScheduleMapperH2SqlTest`（引き継ぎclaimの加算・上限での不一致・停止SQL・有効化での再開）、`ReportScheduleClaimFenceMySqlTest`（実MySQLでcompletion前に毎回停止する実行が引き継ぎで11・停止で12になり、有効化で同一論理runから再開。stale takeover後の失敗は2回） |
| entityの書き戻し（RB-NF10、review P3-9） | `ReportScheduleApiBoundaryTest`（`enabled`・`lastErrorCode`・`lastErrorMessage` の更新策略）、`ReportScheduleMapperH2SqlTest`（自動停止後に古いentityを `updateById` しても停止と停止理由が残る）、`ReportScheduleClaimFenceMySqlTest`（同、実MySQL。INSERTからも停止理由を設定できない） |
| tenant単位の隔離（RB-NF10、review P3-12） | `ManagementReportSchedulerTest`（実 `TenantAwareBatchRunner` で先のtenantのdue取得が確定的失敗・一時的失敗のどちらでも、後のtenantを処理し、ERRORとmetricを残す） |
| 時計の差・outcome tag（RB-NF10、review P2-2・P2-8） | `ManagementReportSchedulerTest`（gaugeの値、誤差を差し引いた閾値判定、60分のWARN間隔、測定失敗でもpollを続ける。outcomeはeventごとに固定で系列数はevent数）、`ReportScheduleMapperH2SqlTest`（`UNIX_TIMESTAMP()` がepoch秒を返す） |

## 15. ON前のP2/P3（2026-11-17、RB-NF10）

状態: **実装済・対象限定fastのみ手元で通過（worker環境）。MySQL testは未実行（統括のRunnerが実行する）。** COMPLETE/PASSではない。Report Owner署名、SRE/DBA承認、alertの設定を意味しない。DDL・`application*.yml`・機能スイッチ実装は変更していない（新設定 `management-report.clock-skew-warn-seconds` はコード既定値5秒で動き、`application.yml` への明示は提案のみ）。messagesは第2回（下記）で1 key（4言語）を追加した。

依拠: FINAL複審 §7 (b) b-19・§6.3 L-2、review `nf10-v185` P2-2・P2-3・P2-8、`w2-nf10-policy-30e058ce` P3-6・P3-9・P3-12。

| 指摘 | 処理 | 内容 |
|---|---|---|
| P3-6 poison pillの無限引き継ぎ | 修正 | 引き継ぎを連続失敗1回として数え、上限では引き継がずに停止・通知する。結果不明のfailure completionは読み直して停止を確かめる（§14.2） |
| P3-9 `enabled`・`lastErrorCode` の更新策略 | 修正（一部は不修正） | `enabled` は `updateStrategy = NEVER`（INSERTは許可。作成時に0を保存する）、`lastErrorCode`・`lastErrorMessage` はINSERT・UPDATEとも `NEVER`。管理者のenable/disableは従来どおり `updateEnabled` の指名列UPDATEだけ。**不修正**: 自動停止後の初回completion前に手動でdisableすると、一覧に `SCHEDULE_RETRY_EXHAUSTED` が残る点（review P3-9の2点目）。停止理由codeを次のcompletionまで保持するのは§14.2の設計で、変えると有効化の再開判定（`updateEnabled` の0戻し条件）と既存testの前提を変えるため、本批では変えない。区別が要るなら停止の種別を表す列（DDL）か、有効化時の停止理由のclearが要る |
| P3-12 tenant失敗で後続tenantが止まる | 修正 | NF10の `dispatchDue` だけを、tenantごとに例外を捕まえてERROR（`errorCode=SCHEDULE_TENANT_DISPATCH_REJECTED`／`_FAILED`、tenantId、例外型、diagnosticId）とmetricを残し、残りのtenantを続ける形にした。共有の `TenantAwareBatchRunner` は変えない（他のschedulerが例外の伝播を前提にしている）。起動guardは全tenant共通の判定のため、従来どおり1 tenantの検出失敗でも全tenantを止める（fail-closed）。**`Error` は隔離しない**（StackOverflowError等。process停止とみなし、そのpollの残りのtenantも中断する。claim済みの行は引き継ぎで失敗として数えられ、上限で停止する。RB-NF10 review P3-6）。ERRORには例外と原因の型の連鎖だけの安全な要約 `detail=`（`LogRedaction.safeThrowableSummary`。message・SQL・値を含まない）を付ける（同 P3-3） |
| P2-8 metric/alert（残り） | metricは修正、alertは草案 | §14.5（`outcome` tag）、§10.6（alert rule草案。閾値等は未決） |
| P2-2 時計 | 挙動不変・観測を追加 | §11追記（gauge・WARN。許容値はSREが決める、未決） |
| P2-3 timezone変更の方針 | 挙動不変・文書のみ | §11追記（Report Ownerが決める、未決） |
| Report Owner署名・確定版の配布方針 | 人が行う（未取得） | `deploy-runbook.md` §1.7 のNF10の行と `feature-switches.md` 4.5 に列挙した |

- 対象限定のtest（統括のRunnerへ渡す一覧）:
  - fast: `ManagementReportSchedulerTest`、`ReportScheduleMapperH2SqlTest`、`ReportScheduleApiBoundaryTest`、`ReportScheduleServiceImplTest`、`FeatureSwitchSchedulerGateTest`（NF10 OFFでmapperに触れないこと）、global guard `ScheduledMethodsHaveSchedulerLockTest`・`TestIsolationAuditTest`
  - MySQL: `ReportScheduleClaimFenceMySqlTest`（`scripts/test-suites/mysql-shard-1.txt`、既存classへのcase追加と期待値更新のためshard登録の変更なし）、`ReportScheduleDispatchPolicyMySqlTest`（`mysql-shard-2.txt`、変更なしだが引き継ぎの数え方の変更の影響を受ける）

### 15.1 独立レビュー（`4ffcfb72`、PASS 附条件）への対応（第2回、2026-11-17）

統括の判断: 引き継ぎの数え方は現状のまま（「同一runが引き継がれた時だけ数える」には変えない）。clock skewのWARNのコード既定5秒は受け入れる。

| 指摘 | 処理 | 内容 |
|---|---|---|
| P3-1 引き継ぎ停止のSQLが結果不明だと通知が出ない | 修正 | 確定的でない例外なら行を読み直し、この試行の停止（retry時刻と加算後の回数まで一致）なら通知する（§14.2） |
| P3-2 読み直しのlease期限判定が失敗検知時刻基準 | 修正 | 読み直す直前の現在時刻で判定し、停止状態の判定にこの試行のretry時刻と回数の一致を加えた（§14.2） |
| P3-3 tenant隔離のERRORの診断不足 | 修正 | `detail=`（`LogRedaction.safeThrowableSummary`。例外と原因の型の連鎖だけで、message・SQL・値を含まない）を加えた |
| P3-4 数え方の説明が誤読されやすい | 文書 | 同一論理runの中だけで累積すること、単一instanceでは実行中に引き継がれないこと、変更前は無限の引き継ぎだったこと、11回の実失敗の後の境界を§14.2に記した。正常な実行が30分を超え得るならB-NF10-2（lease延長しない）を見直すことを、Report Ownerの確認事項として§14.2と `deploy-runbook.md` §1.7 に加えた |
| P3-5 停止の原因を行で区別できない | 修正（既存列） | 引き継ぎによる停止だけ `last_error_message` を `error.managementReport.scheduleStoppedByLeaseExpiry`（4言語）にした。停止理由codeは再開条件のため変えない。引き継ぎで回数だけが増えた行の表示（理由は前回のまま）は文書で扱う（§14.2） |
| P3-6 `Error` は隔離しない | 文書 | 上の表のP3-12の行に記した |
| P3-7 timezone変更と数え方の関係 | 文書 | §11のP2-3の行と§14.2に記した |

- 追加・更新test: `ManagementReportSchedulerTest`（引き継ぎ停止の結果不明は読み直してこの試行の停止だけ通知、読み直し時点でlease期限を過ぎていれば判定しない、他workerの停止を自分の停止と数えない、停止のmessage、tenant隔離のERRORの `detail` とmessage非出力）、`ReportScheduleClaimFenceMySqlTest`（停止行の `last_error_message`）。fastには `MessageBundleConsistencyTest` を加える（bundleに1 keyを追加したため）。
