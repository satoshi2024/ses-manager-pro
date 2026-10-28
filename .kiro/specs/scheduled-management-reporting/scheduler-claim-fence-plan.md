# NF-10 scheduler claim fence 修正計画

状態: **技術修正Plan案 / 実装・migration未着手。** 対象は `m_report_schedule` のscheduler claim所有権競合とclaim時刻の正確性に限定する。NF-10全体の再承認、production/運用gateの承認、remote/release承認を意味しない。現在の基準Headは `9bac40c9624e7d964bcf6e18f480b3457662206e`。

## 1. 確認した欠陥と影響

`ReportScheduleMapper.claimDue` は `processing_claimed_at` をleaseとして設定するが、所有者を表す値を保存していない。`markSuccess`/`markFailure` は `processing_claimed_at IS NOT NULL` だけを条件に更新する。そのため、worker Aのleaseが30分を超えてworker Bが同じ論理実行を再claimした後、復帰したAがBのleaseを消し、`next_run_at`、retry、failure count、error情報を更新できる。B側の完了更新が0行になっても、schedulerは戻り値を確認しない。

`dispatchDueForTenant` はpoll開始時の `now` と `staleBefore` を最大50 scheduleへ使い回している。長い処理後に後続scheduleをclaimすると、DBに保存されるlease取得時刻が実時刻より古くなり、次pollで即stale扱いされうる。failureのretry基準も同じpoll開始時刻を用いる。

## 2. 不変条件

1. 各claimには、workerが新規生成する暗号学的に予測困難なclaim token（UUID等）を割り当て、DBの現在claimへ保存する。tokenはclaimごとに一意であり、再claimでは必ず新しい値を使う。単調generation方式を選ぶ場合も、同じrow上でCAS増加し、同等の予測不能性ではなく一意世代で旧ownerと区別する。本案の第一候補はランダムtoken。
2. 完了更新はtenant/idに加え、`processing_claim_token` の完全一致と `processing_logical_run_at` の完全一致を条件にする。logicalRunAt単独は同一論理実行を再claimすると同値のため所有者識別子にならない。可能なら `processing_claimed_at` もtokenと共に一致条件へ含める。
3. `markSuccess`/`markFailure` が0行を返したらclaim所有権喪失として扱う。古いworkerは別UPDATE、generic entity update、fallback処理でBの状態を上書きしてはならない。所有権喪失をgeneration failureとして再記録しない。
4. claimはdue行の期待状態に対するCASとする。selectDueで読んだtenant/idだけでclaimせず、expected next/retry/logical run/claimedAt/token等の必要な状態をUPDATE条件で再照合する。未claimの初回取得は `processing_claimed_at IS NULL AND processing_claim_token IS NULL` の状態だけを許可し、NULLを未占有として明示CASする。通常completionは非NULLのclaim tokenを必須とし、`processing_claim_token = :claimToken` の通常完全一致を使う。NULL-safe比較、NULL token completion、tokenを省略する旧overloadや後方互換completion経路は設けない。tokenなしの旧snapshot、別tenantの行、leaseを既に更新した競合workerはclaimできない。
5. DB日時列は秒精度に正規化し、Java時刻、token生成、SQL引数、テスト比較の精度を統一する。MySQL `DATETIME` の秒精度を前提にし、H2 timestampを秒精度で扱う。
6. claimの時刻、stale cutoff、failure時刻/retry計算はscheduleごとの現在時刻から求める。poll開始時のbatch timeを後続scheduleへ流用しない。

## 3. 実装設計

### 3.1 tokenとclaim CAS

新しいlease token列（候補名 `processing_claim_token`、nullable `VARCHAR(36)`）を追加する。`claimDue` は次を一回の条件付きUPDATEで行う。

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

## 5. Schema / test schema / migration境界

新列を追加する場合のmigration候補は `V185__report_schedule_claim_token.sql`。現時点の最大versionはV184だが、V185は未割当であり、本Planでは採番・DDL追加を行わない。正式受理時にrootが採番を確定する。V1/V112/V114は書き換えず、後続migrationでnullable token列と必要なindex検討だけを追加する。既存lease行はtoken NULLのままとし、migration時に実行ownerを捏造しない。

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
