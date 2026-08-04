# review-ledger — approval-workflow-internal-control (S07)

## Current authoritative REVIEW PACKET — Round 4 current Head / R4-P1-01 correction（2026-08-04）

**判定:** **NOT REVIEWABLE**。S07は`IN PROGRESS`、S09は`NOT READY — まだ開始不可`、Wave 2は解放不可。

- **current Git:** Review Base=`5d228d211d0d752833fe3424a3b8aa4b40096733`、current Head=`74329e9c982af24e10409b564c5d89a56ef4e2cd`。`HEAD = origin/main = origin/HEAD`、branch=`main`を確認した。
- **scope:** Base→Headは**18 commits / 211 files / +9684/-330**。旧205-path manifestへcurrent Head追加6 paths（responsibility DTO/entity/mapper、permission-group mapper、V79.1 patch migration）を追加した。current Head後のR1.3追加回帰test 3 filesは未commitの作業木にあり、211 committed pathsには含めない。
- **worktree:** `main...origin/main`、追加test 3 filesがdirty（M 2、?? 1）。`git diff --check`はexit 0。commit/pushは行っていない。
- **R4-P1-01:** V79.1、`applicant_role_condition`、`t_approval_responsibility`、PERMISSION_GROUP/ORGANIZATION_MANAGER/FINANCE_MANAGER、as-of/scope/fail-closedを実装済み。V75〜V79は変更していない。
- **R1.3 evidence:** `RouteResolverServiceTest` 19、`ApprovalAdministrationServiceTest` 13、`ApprovalAdministrationApiControllerTest` 5、計**37 / failures 0 / errors 0 / skipped 0**。responsibility期間/組織境界、group membership/user disabled/deleted、3 sourceの候補0/self-only、管理API不正type/逆期間/不存在組織/無効userを含む。
- **static/direct/full evidence:** migration/static/JSは**35 / 0 / 0 / 0 / BUILD SUCCESS**。20クラスdirect regressionは**153 / 0 / 0 / 0 / BUILD SUCCESS**。`verify-like-ci.ps1`の`mvn -B clean test`は**1454 / failures 0 / errors 0 / skipped 12 / Maven BUILD SUCCESS**、scriptはDocker依存skip検出でexit 1。
- **skip /未達:** Docker依存12 test cases / 10 report classes（Flyway系、並行性系）をskip。実MySQL fresh/legacy/partial/repair/rollback、`flyway_schema_history`、複数JVM ShedLock/claim、実Webhook、commit前例外時DB rollback、desktop/390px browser、zero-skippedは未確認。定向greenやMaven BUILD SUCCESSをrelease PASSへ拡張しない。

### Current Issue Register（review process blocker。P分類ではない）

| ID | 状態 | 根拠 | 次の必要対応 |
|---|---|---|---|
| `R4-REVIEW-01` | **OPEN** | current Head 211 pathsへmanifestを拡張したが、独立Reviewによる完全性確認前 | 211件のstatus/count/個別帰属、AC trace、範囲外帰属を独立再確認 |
| `R4-REVIEW-02` | **VERIFIED_CLOSED** | V75〜V79、V79.1 patch、V80〜V88予約、static 35/0/0/0の静的整合を確認 | クローズ維持。実MySQL/B1/Mの証拠へ拡張しない |
| `R4-REVIEW-03` | **OPEN** | B1/T046・M/T047 checkboxは`[ ]`。実環境release gate未達 | 実MySQL、rollback、複数JVM、Webhook、browser、zero-skippedを実証 |
| `R4-REVIEW-04` | **OPEN** | Packet/manifest/中央ledgerをHead `74329e9`、211 paths、18 commitsへ同期したが独立再Review前 | current Head整合と通常Review再開根拠を独立確認 |
| `R4-P1-01` | **OPEN / P1** | source別境界/異常系回帰はgreenだが、V79.1の実MySQL適用・履歴・repair/rollback未確認 | V79.1を実MySQLで適用し、route/source/履歴/repairを実測 |

**再確認しない判定:** B1/M、S07 PASS、S09開始、Wave 2解放は行わない。

## Historical Round 4 packet（current Head `0a724356`時点の履歴。現在値の根拠には使用しない）

### Archived packet snapshot（2026-08-04以前）

**requested verdict:** intermediate / re-baseline。通常Reviewの開始判定ではなく、現時点の判定は **NOT REVIEWABLE** とする。

- **handbook version:** `execution-review-handbook.md` v2.0。Round 4以降は§11により通常Reviewを停止し、spec・時間モデル・scope inventory・migration fixture・test matrixを先に改訂する。
- **spec/tasks:** `.kiro/specs/approval-workflow-internal-control/requirements.md`、`design.md`、`tasks.md`、T041〜T047。
- **base / head / merge status:** Review Base=`5d228d211d0d752833fe3424a3b8aa4b40096733`、original implementation Head=`a70cb51145a94ec3d70421bcc1de77a6b236b559`、Packet統合commit=`9215c5e797d063d13719b231175ab8741736a591`、current Head=`0a724356bfe8e1a05ef03d81ff0ca8c5b19d9e2e`。実Gitでは`HEAD = origin/main = origin/HEAD = 0a724356`、branchは`main`である。R4-P1-01の実装とPacket/台帳の修正は未commitの作業木にあり、今回もcommit/pushは行わない。
- **scope inventory:** Base→current Headは**17 commits / 205 files / +9089/-328**。partitionはS07 spec packet 5、roadmap dispatch docs 25、other spec docs 18、production Java 88、migration SQL 5、frontend/resources 30、test Java 29、test resources 5、未分類0。205 pathの個別task/commit帰属は[`review-manifest-10dc316d.md`](review-manifest-10dc316d.md) §2に固定した。未commitのR4-P1-01差分はこのBase→Head統計には含めない。
- **fix-delta scope:** 現作業木のR4-P1-01は、`m_approval_route.applicant_role_condition`と`t_approval_responsibility`を追加するV79.1、route entity/DTO/API/UI、`RouteResolverServiceImpl`のrole条件優先/fallback、PERMISSION_GROUP/ORGANIZATION_MANAGER/FINANCE_MANAGERの候補解決、H2 schema/fixtureと回帰testを含む。V75〜V79は変更していない。Packet側は`design.md`、`tasks.md`、`review-manifest-10dc316d.md`、本ledger、中央ledgerのHead/未達状態を同期する。
- **requirements/acceptance trace:** R1.2は申請者`SysUser.role`に一致するrouteを汎用routeより優先し、非該当時は汎用routeへfallbackする。R1.3は既存permission groupと期間付き責任者assignmentから候補を解決し、未対応・候補0件・自己承認をfail-closedにする。実装/H2/定向test/static testのtraceはmanifestと`tasks.md` A2記録へ反映済みだが、実MySQL適用証跡は未取得である。
- **migration latest/reserved/applied:** 作業木の実ファイルはV75/V76/V77/V78/V79と未commitのV79.1。S07の正式migration集合はV75〜V79、R1.2/R1.3のpatchはV79.1、S09〜S17はV80〜V88を予約する。適用済みDBの`flyway_schema_history`は未照会であり、V79.1のfresh/legacy/repair/rollback適用判定は未確認。
- **test / Demo evidence:** compileは`BUILD SUCCESS`。R4-P1-01定向testは**25 / failures 0 / errors 0 / skipped 2**（skip 2件はDocker unavailableによる`FlywayMigrationSmokeTest`）。migration/dispatch/JSのstatic testは**35 / 0 / 0 / 0**（`MigrationScriptIntegrityTest` 26、`SpecDispatchConsistencyTest` 8、`JsSyntaxCheckTest` 1）。全量`mvn -B test`は**1440 / failures 2 / errors 0 / skipped 12**で完走したが、failure 2件は通常`powershell.exe`のExecutionPolicyに起因する`VerifyLikeCiPowerShellCompatibilityTest`、skip 12件はDocker依存testである。これらは定向/H2/static evidenceであり、実MySQL fresh/legacy/repair/rollback、複数JVM、Webhook、browser desktop/390px、L4 zero-skippedの代替ではない。
- **known review blockers:** `R4-REVIEW-01`はcurrent Headの205 path個別manifestと20 AC traceの独立確認前でOPEN。`R4-REVIEW-02`はV75〜V79とV79.1 patchの静的整合を確認しVERIFIED_CLOSED。`R4-REVIEW-03`はB1/Mの実環境release gate未達でOPEN。`R4-REVIEW-04`はcurrent Head/Packet/中央ledgerの独立再確認前でOPEN。`R4-P1-01`はR1.2/R1.3のコード/H2/static実装を追加したが、実MySQL gate未達のためOPEN / P1を維持する。P0/P1/P2の通常ReviewはB1/M gate達成まで再開しない。
- **out-of-scope changes:** other-spec 18 files、roadmap dispatch 25 files、shared Java/resource/testはmanifest §2の個別帰属でS07 production実装から分離する。別specの問題をS07の受入PASSへ加算しない。
- **rollback:** commit/push前のため、R4-P1-01は作業木の対象file単位で元の`0a724356`へ戻せる。V75〜V79は編集・削除せず、V79.1を適用したDBを戻す場合はbackup復元または新しいforward migrationを使用する。

### Round 4 Issue Register（review process blocker。P分類ではない）

| ID | 状態 | 根拠 | 次の必要対応 |
|---|---|---|---|
| `R4-REVIEW-01` | **OPEN** | current Head `0a724356`のBase→Head 205 pathを個別task/commitへ帰属し、R1〜R5の20 AC traceをmanifestへ再提出した。独立Reviewによる完全性確認は未了 | current Headを対象にmanifestの205件、分類合計、AC trace、範囲外帰属を独立再確認 |
| `R4-REVIEW-02` | **VERIFIED_CLOSED** | V75〜V79実在集合、V79.1 patch、S09〜S17 V80〜V88単一予約、static regression 35/0/0/0を確認した。実MySQL gateとは分離して扱う | クローズ維持。B1/Mや通常Reviewの証拠へ拡張しない |
| `R4-REVIEW-03` | **OPEN** | B1/T046・M/T047のcheckboxは`[ ]`。実MySQL/rollback/複数JVM/実Webhook/browser/zero-skipped未達 | B1/MのDoD・Demo・release gateを同一Headで実証するまでPASS・次Wave解放を行わない |
| `R4-REVIEW-04` | **OPEN** | Packet・manifest・中央ledgerをcurrent Head `0a724356`、Base→Head=`17 commits/205 files/+9089/-328`、`origin/main`一致・dirty worktreeへ同期したが、独立再Reviewは未了 | current HeadのPacket/中央ledger一致と通常Review再開根拠を独立確認 |
| `R4-P1-01` | **OPEN / P1** | R1.2/R1.3のroute decision model・approver source不足に対するV79.1/entity/API/UI/resolver/H2実装と定向/static検証は完了した。一方、Docker unavailableのため実MySQL fresh/legacy/repair/rollback、`flyway_schema_history`、複数JVM・browser gateは未確認 | V79.1を実MySQLで適用し、route selection・approver source・rollback/repairを実測するまでP1を閉じない |

**現行判定:** **NOT REVIEWABLE**。R4-REVIEW-01/03/04と`R4-P1-01`はOPEN継続、R4-REVIEW-02のみVERIFIED_CLOSED。B1/M、実MySQL、browser、zero-skippedの未達を維持し、S07をPASSへ変更せず、S09/Wave 2を解放しない。

## Round 3追跡Review訂正（2026-08-03、Head `a33a6e9`）

以下を現Headで直接確認した。後続に残るRound 3/Mの旧記録は履歴として保持するが、Docker可用性・全量test・commit状態については本節を正とし、旧記録をrelease PASSの証拠として再利用しない。

### S07-R3-P2-17 — Base / Head / 作業木

- Base: `5110f1204a2270a3cff4195ae580ac7bf366031d`。
- Head: `a33a6e9b1e1f8a973ccb45c59e5a1a38805cda8d`（`main`、`origin/main`、`origin/HEAD`が一致）。
- Headは既にcommit済みであり、「commit/push未実施」「HeadはBaseと同じ」という旧記録は訂正する。今回の追跡作業ではcommit/pushを行わない。
- 作業木はclean、`git diff --check`はexit 0。HeadのBase差分は17ファイル、503 insertions / 127 deletions。
- V75/V76/V77は現Headの差分対象外であり、変更していない。

### S07-R3-P2-18 — Docker / MySQL / V78適用証跡

- Docker CLIは存在するが、現環境の`docker info`/`docker ps`はいずれもDocker daemonへ接続できずexit 1（`dockerDesktopLinuxEngine` named pipe不存在）。したがって、同日旧M記録の「Docker利用可能、8経路全件PASS」は現Headで再確認できず、証拠未提示のためrelease gateのPASS根拠として扱わない。
- `mysql` CLIは未導入、`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`は未設定。`localhost:3306`（IPv6 `::1`）へのTCP接続だけは成功したが、認証・DB名・`flyway_schema_history`の照会は未実施である。
- `application.yml`の既定接続先は`jdbc:mysql://localhost:3306/ses_manager_db`、ユーザー`root`、パスワード`123456`だが、これで接続・照会できることは確認していない。
- よって、V78を適用済みのDBが存在しないとは断定しない。現ローカルDB、dev/staging DBとも、V78の`flyway_schema_history`行をread-only照会できる接続情報・CLIがなく、**V78適用状況は未確認**と記録する。V78適用済みDBがある場合のchecksum/remediation判断は、接続後に別途行う。

### S07-R3-P2-19 — L4全量 / Mobile

- 旧台帳の`1420 / failures 1 / skipped 12`は、現Headのclean作業木を対象にした再測定結果として確定していないため、現Headの実測値として扱わない。
- `MobileResponsiveLayoutTest`を含むL4全量とCI相当skip数は、現Headでclean再測定してから更新する。再測定前の状態は**未確定**であり、旧failureを現Headのfailureとして継承しない。

### 訂正範囲と残作業

- Round 3のコード修正（P1-09/P2-16/P2-11）はcommit `a33a6e9`に含まれる。P2-17/18/19はコード欠陥ではなく、台帳と検証証拠の整合を訂正する追跡項目である。
- B1（通知/SLA/escalation）とM（対象画面統合/回帰）は`tasks.md`上で未完了のまま維持する。実MySQL、実ブラウザ、L4 zero skipped、outbox/commit後処理など、証拠のない項目はPASSにしない。

## Round 3追跡再測定（2026-08-03、clean worktree / Head `a33a6e9`）

`git worktree add .tmp-clean-head-r3 a33a6e9b1e1f8a973ccb45c59e5a1a38805cda8d`で作成したdetached clean worktreeを使用した。作成時の作業木はcleanで、テスト対象のproduction/test codeに変更はない。

- **L4 CI相当**: `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`を実行。Node `v24.18.0`は利用可能、Docker daemonは利用不可。`mvn -B clean test`は**1420 tests / failures 1 / errors 0 / skipped 12**、script exit 1。
- **L4 failure**: `MobileResponsiveLayoutTest.クイック作成ボタンのラベルは小画面で非表示にできるようspanで包まれている`。clean worktreeの全量実行で再現したため、旧台帳の「現Headでは再現しない」という記録は今回の全量測定結果で上書きする。ただし、S07のRound 3変更差分に含まれない既存UI契約のfailureとして扱う。
- **skipの内訳**: 12 test cases、10 XML report classes。`CustomerContactPrimaryConcurrencyTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`ConcurrentLoginSessionSmokeTest`。Docker未起動が原因で、CI契約のzero skippedは未達。
- **Mobile単独**: `mvn -B -Dtest=MobileResponsiveLayoutTest test`は**23 tests / failures 0 / errors 0 / skipped 0 PASS**。全量failureと単独PASSが異なるため、Mobileの単独結果をL4全量PASSへ読み替えない。
- **Round 3対象回帰**: `ActionPermissionMatrixTest` 15、`ApprovalViewServiceImplTest` 4、`ApprovalTargetAdapterTest` 7、`InvoiceServiceImplTest` 41の計67件はfailures 0 / errors 0 / skipped 0。併せて`FlywayMigrationSmokeTest` 2件はDocker不可でskip、コマンド全体は**69 / 0 / 0 / 2**。
- **M定向回帰**: `QuotationApiControllerTest` 4、`ContractApiControllerTest` 12、`ContractPaginationTest` 13、`InvoiceApiControllerTest` 10、`ApprovalTargetAdapterTest` 7の計**46 / 0 / 0 / 0 PASS**。
- **再測定後の結論**: L4全量はfailure 1・skip 12でrelease gate未達。単独Mobile、Round 3対象回帰、M定向回帰はPASSだが、実MySQL smokeと実ブラウザdesktop/390px通しは未検証のまま維持する。

## Round 3 実装・検証記録（2026-08-03）

### Base / Head / 作業木

- Base: `5110f1204a2270a3cff4195ae580ac7bf366031d`（親 `1e204df953ff09617b39bbb0da6289a1ade06033`、`指摘対応`）。
- Head: 同じ`5110f12`。commit/pushは未実施で、今回の変更はすべて未commitの作業木にある。
- 作業木: 15ファイル変更、`git diff --stat`は448 insertions / 21 deletions。
- `git diff --check`: exit 0。
- V75/V76/V77: `git diff --name-only --`の結果0件。これらのmigrationは変更していない。

### 実装内容とP1-09 A′ / P2-16 / P2-11の対応

- V78はDDLより先にstored procedure gateを実行する。`route_snapshot_json`のmalformed/NULL/空、`steps`欠落/空、現在step以降の残り全stepにおける複数approver slot欠落/空をfail-closedで停止する。
- terminal申請は既存履歴を削除せずparticipantを`INSERT IGNORE`でbackfillする。非終端で多名旧snapshotのslot境界を復元できない申請は停止し、停止メッセージに申請特定SQL、`flyway repair`後の取下げ/完了と再実行手順を含めた。
- 承認適用のロック順序は全対象で固定し、`request row → target row`とした。見積・契約・請求・BP支払は対象行を`FOR UPDATE`取得し、月次締めは`m_system_config`対象行を`FOR UPDATE`取得してJVM内cacheを最終version確認に使わない。請求の直接BP支払状態変更経路にも対象行ロックを追加した。
- P2-11はV78と`permission-group-seed-h2.sql`の双方へ`bp-company.bank-account.view`のdeny行を追加した。`ActionPermissionMatrixTest`はdeny総数だけでなくaction keyを名指しで確認し、`ApprovalViewServiceImplTest`は営業/マネージャーのマスクと管理者の表示を確認する。

### 自動検証の実測

- 対象回帰: `ActionPermissionMatrixTest` 15件、`ApprovalViewServiceImplTest` 4件、`ApprovalTargetAdapterTest` 7件、`InvoiceServiceImplTest` 41件。合計67件、failures 0 / errors 0 / skipped 0。
- コンパイル: 対象回帰時および`mvn -B clean test`のcompile/testCompileが成功。
- `FlywayMigrationSmokeTest`: 2件、failures 0 / errors 0 / skipped 2。Docker daemonが利用できず、fresh migrationとV77 legacyからの終端成功/backfill・非終端停止fixtureは実MySQL上では未実行。
- 直接実行した`mvn -B clean test`: 1420件、failures 3 / errors 0 / skipped 12。失敗3件の内訳は、既存の`MobileResponsiveLayoutTest` 1件と、Windows execution policyにより`VerifyLikeCiPowerShellCompatibilityTest` 2件。
- `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`: Node `v24.18.0`を検出し、`JsSyntaxCheckTest`は実行可能な状態。1420件、failures 1 / errors 0 / skipped 12、exit 1。残るfailureは`MobileResponsiveLayoutTest.クイック作成ボタンのラベルは小画面で非表示にできるようspanで包まれている`。skipは12件で、Docker依存の`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`CustomerContactPrimaryConcurrencyTest`、`ConcurrentLoginSessionSmokeTest`に対応する。
- `MobileResponsiveLayoutTest`の失敗はS07変更ファイル外の既存UI fixture/共通メニュー注入に関するもので、今回の承認実装の対象回帰は全件greenとして切り分けた。CI契約のzero skippedと全量failure 0は、この環境では満たしていない。

### 未検証事項 / release gate

1. Docker未利用のため、V78のMySQL dialect、DDL前gate、legacy終端backfill、非終端旧snapshot停止、`SIGNAL`例外連鎖を実DBで確認できていない。
2. request→targetの同一transactionロックは対象adapterテストで確認したが、実MySQLの並行承認・通常更新との競合および複数JVMの月次締め変更検知は未確認である。
3. 実ブラウザdesktop/390pxの5業務通しは未実施である。
4. 全量testは`MobileResponsiveLayoutTest` 1件が未解決で、CI相当のskip 12件も未解消である。CIでDockerを有効にして`verify-like-ci.ps1`を再実行し、zero skippedを確認する必要がある。
5. `VerifyLikeCiPowerShellCompatibilityTest`はExecutionPolicy Bypassでは通過したが、通常の`powershell.exe -File`は端末ポリシーにより拒否された。Windows開発環境の実行ポリシー設定差として記録し、CI相当実行時は明示的な実行ポリシーを使用する。

### Rollback

- commit/push前のため、コード・テスト・spec文書はこの作業木の15ファイル単位で元の`5110f12`へ戻せる。戻す場合も`V75/V76/V77`は変更しない。
- V78を本番DBへ適用した後は、適用済みmigrationを編集・削除せず、バックアップ復元または新しいforward migrationで業務影響を戻す。停止したlegacy申請は、メッセージに示したID特定SQLで確認し、運用判断により取下げまたは完了させ、`flyway repair`後に再実行する。
- 今回はcommit/pushを行っていないため、rollback用commitは作成していない。

## Round 3 semantic-review転記（自動検証の観測であり独立Review判定ではない）

対象成果物は削除前の`semantic-review/2026-08-03-140419-pr-0.md`である。この成果物は独立reviewerによる承認・PASS判定ではなく、変更差分に対する自動検証観測として扱う。内容の主要観測は次のとおり。

- **blocker / confirmed — 対象版確認と承認適用のTOCTOU**: 現在版のreadと`applyApproved`の間に対象更新が入ると古い申請を適用できる観測。今回のRound 3ではrequest rowからtarget rowを`FOR UPDATE`で固定する方針を実装したが、実MySQL並行競合は未検証。
- **high / confirmed — V78の既存申請participant backfill欠落**: 参加者テーブル作成だけではV78前の進行中申請が非管理者一覧から消える観測。P1-09 A′としてterminal backfillと危険な非終端旧snapshotのfail-closed gateを実装したが、legacy MySQL fixtureはDocker未利用で未実行。
- **medium / confirmed — view/statusの交差条件**: inbox/completedのview境界と明示statusの積がSQLで保証されない組合せがあるという観測。今回の変更で新たな修正を加えたものではなく、独立Review判定ではない未再検証事項として保持する。
- **medium / confirmed — 口座情報の権限キー不一致**: `bp-company.bank-account.view`とseed/設計上の判定キーの不一致という観測。Round 3ではV78/H2 seed、action key名指し回帰、営業/マネージャーmask・管理者表示を追加して整合を取った。
- **high / likely — 月次締めfingerprintのJVM cache依存**: 複数インスタンスでcacheが古くなる可能性という観測。Round 3では最終確認をDB対象行`FOR UPDATE`へ寄せたが、複数JVM実測は未実施。
- **medium / confirmed — reject/return通知dedupeのround非依存**: round 2の通知がround 1のdedupe keyに抑止される可能性という観測。今回の変更で通知機構自体は変更しておらず、B1/別作業の未解決観測として保持する。
- **medium / confirmed — MySQL実行と重要経路の検証gap**: Docker不在でfresh/legacy migration、競合、backfill、権限の実DB検証が未実施という観測。Round 3でもDocker未利用のため解消していない。

**独立Review結果**: semantic-review成果物は独立Review判定ではない。設計承認は済みだが、Docker未検証、全量failure、skip残存、上記未検証事項があるため、release PASSとは判定しない。

## Issue Register（Round 2 — P1/P2 全件）

| ID | 分類 | 概要 | 状態 |
|---|---|---|---|
| S07-R2-P1-01 | P1 | 差戻し→再申請→承認が silent failure（round_no 未追加により UNIQUE 衝突） | 対応中 |
| S07-R2-P1-03 | P1 | ROLE step quorum が全員要求になっており any-of ではない | 対応中 |
| S07-R2-P1-04 | P1 | 申請者 role 条件が design.md に記載なし（逸脱明記が必要） | design.md 更新済み |
| S07-R2-P1-05 | P1 | V76/V77 が S09/S10 の予約番号を占有して `SpecDispatchConsistencyTest` FAIL | **対応済み（S07=V78、S09=V80、S10=V81へ文書統一。定向8件PASS）** |
| S07-R2-P1-06 | P1 | 却下/取下げ後の再申請で UNIQUE(idempotency_key) 違反が発生 | 対応中 |
| S07-R2-P1-07 | P1 | target_version 再検証未実装（秒精度問題 + conflict 遷移なし） | 対応中 |
| S07-R2-P1-08 | P1 | ApprovalViewServiceImpl が全件 load 後 Java 側 filter（SQL 境界違反） | 対応中 |
| S07-R2-P2-06 | P2 | 締め済み月の一律適用方針が decision table に未記載（F1 着手前から申し送り） | design.md 更新済み |
| S07-R2-P2-09 | P2 | tasks.md の `- [~]` / `- [x]` が不正な記法 | 修正済み |
| S07-R2-P2-11 | P2 | 口座 field の mask が `bp-company.view` 判定で実質無効 | 対応中 |
| S07-R2-P2-12 | P2 | Issue Register が review-ledger.md に未記載 | 本行で対応 |

### 各 Issue の再現条件（実測）

- **P1-01**: `returned` 状態の申請を `resubmit()` → `approve()` すると `t_approval_action` の UNIQUE `(request_id, step_no, approver_slot_user_id)` が前 round の action と衝突する。`round_no` を UNIQUE キーに含めれば解消。
- **P1-03**: `ApprovalEngineServiceImpl.approve()` L167 が `approvedCount < currentStep.approverUserIds().size()` で判定。ROLE 解決で管理者が3名の場合 `approvedCount < 3` となり1名承認では step が進まない。`requiredCount=1` の any-of quorum に変更が必要。
- **P1-05**: 旧版の`SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと`で、実在V76(`approval_menu`)・V77(`approval_sla_step_start`)とS09(V76予約)/S10(V77予約)が衝突した。Round 2でdesign/tasks/派工資料を正としてS07=V78、S09=V80、S10=V81以降へ統一し、定向8件PASSで解消。
- **P1-06**: reject/withdraw 後の `idempotency_key` が UNIQUE 制約のままのため、同一 key での再 insert が `DuplicateKeyException` になる。終端到達時に `SET idempotency_key = NULL` でクリアすることで解消。
- **P1-07**: `ApprovalTargetAdapter` に `currentVersion()` がなく `target_version` が常に申請時値のまま変わらない。対象4テーブルへ `version INT`（`@Version`）追加と `currentVersion()` 実装が必要。
- **P1-08**: `ApprovalViewServiceImpl.listPendingForUser()` が `selectList()` 全件取得。`t_approval_participant` 追加と JOIN クエリへの変更が必要。

## M. 対象画面統合/回帰 実行記録（2026-08-03）

Mの対象5業務（見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopen）を、
対象APIから`ApprovalTargetAdapterRegistry`経由の申請へ統合した状態で回帰した。申請者が直接確定する経路には戻していない。

- **変更・実装**: `ApprovalPayloads`、5種類のtarget adapter、決定的SHA-256 idempotency key、月次締めの最終承認者/roleを既存締めサービスへ渡す監査主体、対象APIの申請レスポンス、UI文言、4言語bundleを確認した。既存DI不足（`QuotationPdfService`、`InvoiceApiController`、`MonthlyClosingService`）も修正済み。
- **定向回帰**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、`ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件、計46件を failures 0 / errors 0 / skipped 0で実行した。39 errorsだった初回全量の原因（対象WebMvcTest fixtureに`ApprovalTargetAdapterRegistry` mockが無い）は4 fixtureへmockを追加して解消した。
- **L4全量**: `mvn test`は`1410 tests / failures 2 / errors 0 / skipped 0`。残る失敗はM変更に起因しない次の2件のみである。
  1. `SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと`: 実在最新V77に対しS07がV75、S09がV76、S10がV77を予約している。仕様/作業木の採番不整合であり、M実装の失敗ではない。
  2. `MobileResponsiveLayoutTest.クイック作成ボタンのラベルは小画面で非表示にできるようspanで包まれている`: `.tmp-ui-scale-r3`等の既存dirty worktree変更に関連する`quick-add-label` markup不足。M対象APIの失敗ではない。
- **Docker/Testcontainers**: Dockerは利用可能で、fresh/legacy/upgrade/partial-repair/repair/concurrentの8経路を実行した。`FlywayMigrationSmokeTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`FlywayRepairRunbookTest`、`ConcurrentUpdateTest`は全件PASS（各1件、skipped 0）。
- **Node/差分検証**: Node `--check`はPASS（`JsSyntaxCheckTest` 1件）、対象回帰時点の`git diff --check`もexit 0。今回の文書追記後に再実行する。
- **未実施**: 実ブラウザdesktop/390pxの5業務通しは未実施。理由は本実行環境でブラウザ通しを自動起動できる受入経路が未構成で、代替としてMockMvc/Thymeleaf UI契約テストを実施したため。影響は実表示・実操作・viewport依存の回帰を保証できないこと。
- **release gate（未解決、PASS扱い禁止）**: `targetVersion`正式定義とCAS/current_step再検証、`ApprovalEngineServiceImpl.approve()`の現行対象version比較、対象テーブル側`UNIQUE(approval_request_id)`、同時二重申請のDB UNIQUE競合処理、outbox/通知失敗時のrollback・再送、ROLE quorumと申請者role条件、差戻し再申請時のUNIQUE衝突、締め済み月のconfirm/reopen判定、diff maskingの正式確認、見積受注時の`changeStatus`と`createDraftFromQuotation`のmethod境界。
- **判定**: Mの対象統合・定向回帰・Docker smokeは実測済みだが、上記2件の全量失敗、実ブラウザ未実施、release gate未解決事項のためMは完了にしない。コミット・pushは行っていない。

## T042(F1) route/request/action/delegation DDL 完了（2026-08-02）

TASK CONTRACTに基づき実装した。詳細は`tasks.md`のF1エントリと`design.md` §8「F1実装注記」を正とする。

- **requirements ID**: R1.1〜R1.4、R2.1〜R2.2、R2.4、R3.4、R4.1
- **変更file**:
  - migration: `V75__approval_workflow.sql`（新規5テーブル + `ActionPermissionResolver`用`approval.*`権限seed）
  - H2: `sql/schema-approval-h2.sql`（新規）、`application-test.yml`（schema-locations追加）、
    `sql/permission-group-seed-h2.sql`（`approval.*`のgroup権限seed追加）
  - entity: `ApprovalRoute`/`ApprovalRouteStep`/`ApprovalRequest`/`ApprovalAction`/`ApprovalDelegation`
  - mapper: `ApprovalRouteMapper`/`ApprovalRouteStepMapper`/`ApprovalRequestMapper`/`ApprovalActionMapper`/`ApprovalDelegationMapper`
  - service: `com.ses.service.approval`パッケージ（`ApprovalEngineService`/`ApprovalTargetAdapter`/
    `RouteResolverService`/DTO群）+ `RouteResolverServiceImpl`/`ApprovalEngineServiceImpl`
  - controller: `ApprovalApiController`（`/api/approval/requests`配下の汎用engine API）
  - DTO: `com.ses.dto.approval`パッケージ
  - `ActionPermissionResolver.java`（`approval`をRESOURCE_NAMESへ登録。CRM-R2-P1-01の再発防止）
  - `messages{,_en,_ko,_zh_CN}.properties`（`error.approval.*` 5key×4言語）
  - `FlywayMigrationSmokeTest.java`（V75のtable/column/index/action権限assert追加）
  - test: `RouteResolverServiceTest`（新規, 8件）、`ApprovalEngineServiceTest`（新規, 12件）
- **DDL/H2/MySQL同期**: 新規テーブルのみのためV1は無変更（CRM V73と同方針、design冒頭に明記）。
  H2は`schema-approval-h2.sql`（FK無し、CLOB化、CRM/BPと同じ方針）。MySQL smoke assertは
  `FlywayMigrationSmokeTest`へ追加したがDocker未導入のため本環境では未実行（release gate継続）。
- **実行testと件数/結果**:
  - `RouteResolverServiceTest` 8/8 PASS（金額帯inclusive境界、該当routeなし拒否、負数絶対値、
    金額なし申請の専用route、自己承認候補ゼロでの拒否、組織具体性/金額帯狭さ/version_no新しさの決定順）
  - `ApprovalEngineServiceTest` 12/12 PASS（申請直後in_review到達、単一承認者の終端、
    並列group全員承認での進行、並列group1人却下での即終端、自己承認のみのroute拒否、
    非承認者からのapprove拒否(403)、代理期間内/期間外、本人と代理の同時解決での先着1件のみ有効、
    同一slotへの二重clickの冪等性、終端到達後retryの状態不正エラー、versionのCAS(0件更新)確認）
  - 共有基盤への直接影響範囲の回帰: `MigrationScriptIntegrityTest`・`ActionPermissionMatrixTest`・
    `ActionPermissionResolverTest`・`MessageBundleConsistencyTest`（4クラス計51件）全PASS
  - 上記6クラス合計 71件 / failures 0 / errors 0 / skipped 0。`mvn -o compile`・`mvn -o test-compile` BUILD SUCCESS。
  - `git diff --check` exit 0
- **Demo**: `RouteResolverServiceTest`の境界fixture群で金額帯境界のroute解決を自動確認。
  route未設定時の`notifyAdminsOfConfigGap`呼び出しはコードレビューで確認（実通知送信の目視Demoは
  B1のSLA/通知実装と合わせて実施）。実ブラウザ/curl Demoは対象画面が無いF1段階では実施せず、
  A1（inbox UI）着手後またはM taskで行う。
- **未検証事項**:
  1. MySQL fresh/legacy smokeの実機実行（Docker未導入環境のため）。
  2. 実通知送信（`NotificationService.publishToUser`呼び出し先の実際の到達）はB1と合わせて確認。
  3. 本番相当のbrowser Demo（対象画面が無いため、A1/Mへ持ち越し）。
  4. G7の正式decision-log記録は未実施（推奨既定採用として`operation-inventory.md`へ記録済みだが、
     `decision-log.md`自体の更新は発注者/統合担当の所掌）。
- **既知のトレードオフとロールバック**: `design.md` §8に記載の5件の実装注記（二重action防止キーの変更、
  draft/requested collapse、approver_type範囲限定、resolveApprovers実現方法、target_version/`@Version`の
  F2持ち越し、escalateのB1持ち越し）はいずれも既存資産の再利用または後続task境界の明確化であり、
  要件変更ではない。ロールバックは本task分の新規file削除 + `V75__approval_workflow.sql`の取り下げ
  （適用済みでなければ）で完結する。適用済みの場合は新migrationでDROPする（V75自体は編集しない）。
- **base/head commit**: 未commit（working tree、ユーザーからのcommit指示があれば別途実施）。
- **Review開始条件**: 未成就。F2（5 target adapters）着手前、またはA1/A2/B1と合流するタイミングで
  主担当が独立Reviewへ提出する。現時点でF2以降を自動開始しない。

## Readiness再確認とT041完了（2026-08-02）

前回STOP後、`main`側でCRM(S08)がRound 8独立再ReviewでPASS確定し（`spec-execution-ledger.md` row8、
Base `94f95083f178b812caa43782a5e00d09a8d6f324` → Head `042bd0cfb8139466eb7199a7d625adfb181c8563`、
L4全量1,280/0/0/0、MySQL fresh/legacy/partial/repair全4経路成功、desktop/390px全role Demo確認済み）、
central ledger row7（approval-workflow-internal-control）が`NOT READY`→`READY`へ更新された。
working treeもclean化された（HEAD `6645644`）。これによりT041(0)の開始条件が成就したため着手した。

```text
READINESS（再確認）
- spec/task: approval-workflow-internal-control T041(0)
- handbook version: v2.0
- base commit / working tree: main 6645644、clean
- dependency merge/review evidence: BP(S06) PASS、CRM(S08) PASS（Round 8、central ledger row8）確認
- migration latest/reserved: 実在latestはV74系列。本specの予約V75は依然空き番号（F1着手時に再確認する）
- G7: blocking=no、decision-log推奨既定（組織上長→財務/管理者。閾値は設定画面で管理）を採用し、
  operation-inventory.md §1へ明記。decision-log.md自体の正式decision記録は発注者/統合担当の所掌として
  別途依頼する（本task 0はinventory担当であり、decision-log更新権限を僭称しない）
- decision: GO（T041のみ。F1はTASK CONTRACTを別途提示してから着手する）
```

### T041(0) 成果物・実測

- 成果物: [`operation-inventory.md`](operation-inventory.md)（対象5業務・9操作の現endpoint/service/申請field/route/SLA/職務分離表）。
- 変更file: `operation-inventory.md`（新規）、`tasks.md`（task 0を`[x]`化）、本ファイル。production code(Java/SQL/JS/HTML)は無変更。
- 対応requirements ID: R1.1, R1.2, R1.3, R2.1, R2.2, R2.4, R4.1（各行に付与、詳細はoperation-inventory.md §2）。
- test: L0。`git diff --check` exit 0。表の全9操作にendpoint/service/requirements IDが存在することを目視確認。
- Demo: 財務/管理者向け提示内容としてoperation-inventory.md §4に記録（実ブラウザ/実会議での提示はF1〜Mのrelease gateへ継続）。
- 未検証事項: G7の正式decision-log記録（発注者/統合担当待ち）。operation-inventory.md §3の3件の非対称性（月次締めロック未呼び出し、単価改定の状態非依存、BP支払確定のlock方式）はF1のdesign.md決定表反映が必要な申し送りであり、F1着手前に解消する。
- rollback: 本task分のドキュメント3ファイルをrevertするのみ（production変更なし）。
- Base/Head: Base `6645644`（変更前）→ 本task分のドキュメント変更のみ、コミットは未実施（ユーザー指示によるcommit要求があれば別途実施）。

## Readiness Gate 判定（2026-08-02、旧・STOP時点の記録として保持）

`execution-review-handbook.md` v2.0 §4 Readiness Gateに従い、T041着手前の確認を実施した結果、
開始条件が未成就のため production file・SQL・`tasks.md`のcheckboxを一切変更せず停止する。

```text
READINESS
- spec/task: approval-workflow-internal-control T041(0)〜T047(M)
- handbook version: v2.0
- requirements/acceptance: requirements.md R1〜R6（未着手）
- base commit / working tree: main 182dce7（作業木はdirty。ui-scale-regression-hardening-200系の
  未commit変更（ProposalApiController/WorkRecordApiController/DashboardSummaryDto等）と、
  CRM T049関連とみられる未commit変更（LeadServiceImpl.java、CrmLeadPaginationTest.java）が
  本specと無関係に存在する。本spec用のbranch/worktreeは未作成）
- dependency merge/review evidence: BP master(S06)はPASS（Head 4d34212）。
  CRM(S08)は central ledger row8で状態`IN PROGRESS`。CRM tasks.mdのtask M（回帰）が
  `- [ ]`未完了（「L4全量とdesktop/390px全role browser Demoは最終gateとして残る」）。
  Round 7時点でP0=0/P1=0だがM未完了のためS08はPASSに至っていない
- migration latest/reserved/gaps: 実在latestはV74系列（V74, V74_1, V74_2）。
  本specの予約はV75（tasks.md冒頭で確定済み）。V72/V59は永久欠番。今回のmerge済み最新確認では
  ユーザー指示にあった「V71」は既にBP procurement fix（V71__bp_company_fix_and_procurement.sql）に
  使用済みで、本spec着手時の空き番号ではない
- mandatory environments: 未確認（STOPのため未着手）
- file ownership: 未宣言（着手条件未成就のため子Agentへの割当も未実施）
- assumptions: G7はdecision-log.mdでblocking=no。推奨既定「組織上長→財務/管理者。閾値は設定画面で管理」を
  採用する前提を置くことは可能だが、spec-execution-ledger.mdの開始条件（row7）は
  「CRM(S08)のT049〜T053完了とG7方針記録後にS07」を明示しており、G7の推奨既定採用を記録するだけでは
  開始条件の後半しか満たさない
- blockers:
  1. CRM(S08) T049〜T053が未完了（central ledger row8 = IN PROGRESS、CRM tasks.md task M `- [ ]`）
  2. G7の方針記録（推奨既定採用の明記、またはG7決定）が本specのdecision-log/review-ledgerへ未記録
  3. ユーザー指示の着手条件記載「Migration: V71」がmerge済み最新（V74系列）と不整合。
     正しい予約はV75（tasks.md冒頭、central ledger row7、dependency-matrix該当節と一致）
- decision: STOP
```

## Blocker詳細

| # | Blocker | 根拠 | 影響task |
|---|---|---|---|
| 1 | CRM(S08)未完了 | `spec-execution-ledger.md` row8 = `IN PROGRESS`。`crm-contact-opportunity/tasks.md`のtask M が `- [ ]`、備考「L4全量とdesktop/390px全role browser Demoは最終gateとして残る」。`crm-contact-opportunity/review-ledger.md` Round 7時点でP0=0/P1=0だがL4全量・browser Demo未実施 | T041〜T047全件（0→F1→F2→(A1\|\|A2\|\|B1)→M）。dependency-matrix「approval」行、parallel-execution-plan「Wave 1-B」、central ledger row7がいずれも「CRM完了後にS07」と明記 |
| 2 | G7方針記録が未記録 | `decision-log.md` G7: blocking=no、推奨既定「組織上長→財務/管理者。閾値は設定画面で管理」、状態=未決。central ledger row7の開始条件は「G7方針記録後にS07」であり、推奨既定を採る場合もその旨を明記した記録が要求されている | T041（Objective自体が「G7と対象操作inventory」）、およびF1のroute金額帯設計の前提 |
| 3 | 着手条件のMigration番号不整合 | ユーザー指示「Migration: V71」に対し、`db/migration`実在最新はV74系列。`tasks.md`冒頭・`spec-execution-ledger.md` row7・`dependency-matrix.md`はいずれも本specの予約をV75と確定済み | F1（DDL）着手時の採番。今回はSTOPのため実際の採番作業は行っていない |

## 必要な発注者回答

1. CRM(S08)を「完了・merge済み」とみなしてS07着手を許可するか、CRM task M（`mvn test`全量、fresh/legacy MySQL smoke、desktop/390px browser Demo）とCRMの独立Review PASS確定を待つか。
2. G7について、decision-log推奨既定（組織上長→財務/管理者。閾値は設定画面で管理）をそのまま採用してよいか、または別の決定値を出すか。採用する場合、`decision-log.md`のG7行へ「決定」「決定日」「決定者」を記録してよいか（本spec側のT041ではなく、decision-log自体の更新は発注者/統合担当の所掌と理解している）。
3. 着手条件に記載された「Migration: V71」は本spec着手時点のmerge済み最新（V74系列）と不整合であり、正しい予約はV75である旨の認識合わせ。

## 再開条件

- `spec-execution-ledger.md` row8（CRM）が `PASS` に更新され、Base/Head commitが記録され、`main`へmerge済みであることを確認する。
- G7について、decision-log推奨既定の採用または発注者決定が`decision-log.md`へ記録されていることを確認する。
- 再開時に`db/migration`のmerge済み最新を再確認し、V75が依然空き番号であることを確認してからT041に着手する（衝突していれば後発である本specを繰り上げ、前の欠番は埋めない）。
- 再開後は本ファイル冒頭の「現行判定」を更新し、T041のTASK CONTRACTから実装を開始する。

## 変更ファイル

- 本ファイル（新規作成）。production code / SQL / 他specファイル / `tasks.md`のcheckboxは変更していない。

## T043(F2) 着手判定（2026-08-02、STOP: engine/対象version/outbox契約未確定）

- **task / requirements / 変更file**: F2（カタログT043、R1.1〜R1.5、R2.1〜R2.4、R5）を着手確認した。production変更なし。許可範囲内の変更は本記録のみ。`tasks.md`・migration・V1・H2共通schema・共通entity/service・message bundleは変更していない。
- **必須事前確認**: `AGENTS.md`、spec requirements/design/tasks、`customer-product-expansion-2026` README/decision-log/gate-decisions-g1-g6/execution-review-handbook/shared-standards/platform-invariants/dependency-matrix/parallel-execution-plan/subagent-delegation-summary/spec-execution-ledger、独立Review用R07 conversation、V75 migrationを確認。`.config`はspec directoryに実在せず、bugfix specである証拠はない。
- **migration / base-head**: 実在latestは`V75__approval_workflow.sql`（V74_2後、V75重複なし）。V59/V72は永久欠番。`git rev-parse HEAD`=`9a57eebf78aff7d4566f614cb33d37845f4685c5`、working treeは既存の`tasks.md`変更のみ（本taskは保持）、`git diff --check` exit 0。
- **OPEN P1確認**: R07独立Review資料は対象確認用promptであり、approval固有のIssue Register/OPEN P1はrepository内で確認できなかった。指定されたreturned→resubmit UNIQUE衝突、route解決不能通知rollback消失、ROLE quorum不一致、申請者role条件欠落、P2-06締め済み月決定表未反映の解消状態を示す独立Review証跡も見つからないため、独立Review結果は未検証として扱う。
- **blocker 1（R2.1/R5）**: `t_quotation`/`t_invoice`/`t_bp_payment`に実version列がなく、月次締めは`m_system_config`のJSON記録でversionがない。`Contract`のみ`BaseEntity.updatedAt`（@Versionなし）である。F1の`ApprovalSnapshot.targetVersion`は任意Longを保存するだけで、対象versionの取得・再検証契約/mapperもない。古いsnapshotを適用しないことを保証するには、対象ごとのversion定義または対象更新CASを上位契約で確定する必要がある。adapter側だけでupdated_at等を推測してLong化する実装は決定表にないため実施しない。
- **blocker 2（R2.3）**: F1の`ApprovalEngineServiceImpl.approve()`はrequest lock→adapter.applyApproved→request approvedまでで、outbox entity/mapper/table/jobまたはcommit-after hookの契約が存在しない。外部API/メールを呼ばないことは確認できるが、commit後のみ実行されるoutbox insertをF2専用ファイルだけで実装できない。migration・共通engine契約を変更する必要があり、TASK CONTRACTの禁止共有ファイル/停止条件に該当する。
- **blocker 3（R1.1/R2.2/R2.4）**: inventoryは9操作（quotation submit/accept、contract activate/revisePrice、invoice send/void、BP confirm、closing confirm/reopen）を示す一方、F1 registry契約は`requestType()`一キーの5 adapterを想定している。quotation acceptは既存serviceの`changeStatus`と`createDraftFromQuotation`の2単件methodを必要とし、単一委譲「1回だけ」と同時に満たす操作境界が未確定。月次締めreopen/confirmも同様に別methodである。9操作を5 adapterへ写像するrequestType/operation契約がdesign/tasksにないため推測実装しない。
- **test / Demo**: F2 production/test実装は未実施。したがってF2対象testの件数は0、failures/errors/skippedは未実行、exit codeは未実行。5対象curl申請→承認、同一request 10回承認、rollback/outbox commit後実測Demoも未実施。
- **risk / rollback / 未検証事項**: 推測したversion・operation・outboxを実装すると、古いsnapshot適用、二重業務操作、rollback時の外部副作用またはcommit前送信を隠れたまま通す高riskがある。今回production変更はないためrollbackは本追記行のrevertのみ（`tasks.md`の既存変更は主担当管理）。未検証は上記独立Review issue状態、対象version定義、outbox schema/job、direct endpoint申請化、MySQL/H2同期、対象adapter integration test、curl Demo。
- **依頼**: 主担当で①5 adapter対9 operationの明示mapping（特にquotation.acceptの一操作境界）、②各対象のversion/CAS契約（migration/entity変更可否を含む）、③outbox persistence/job契約とtransaction順序、④指定OPEN P1およびP2-06の独立Review判定表をrequirements/designへ反映・確定してから、T043を再派工すること。

## A2 route/代理管理 完了（2026-08-03）

- route version登録・適用期間・approver preview、期間/対象付き代理登録・論理削除、代理監査表示を実装した。既存routeは変更せず新行へversionを採番し、申請時route snapshotを維持する。
- 代理は承認操作時点の有効期間とrequest typeで評価し、本人/代理の同一slotは一意制約で先着1件に抑制する。固定USERと申請者上長は有効ユーザーのみを候補にし、解決不能なら受付を拒否する。
- 管理API/ページは`hasRole('管理者')`で保護し、CSRFは既存`SES.api`方式、i18nは4言語bundleを維持した。
- 検証: `ApprovalAdministrationServiceTest` 8件、`RouteResolverServiceTest` 9件、`ApprovalEngineServiceTest` 12件、`ApprovalPageRenderTest` 1件、`ApprovalUiContractTest` 2件、`MessageBundleConsistencyTest`を変更後に実行し、合計35件・failures 0・errors 0・skipped 0でPASS。Node `--check`と`git diff --check`もPASS。実ブラウザdesktop/390px、MySQL/Docker fresh smoke、mvn全量は未実施。

## B1通知/SLA/escalation 実装追跡（2026-08-03）

- `ApprovalNotificationKeys`を追加し、承認申請・承認・差戻し・却下・conflict・SLA超過のdedupe keyを`requestId + round + step (+ slot)`へ統一した。`ApprovalSlaService`のSLA keyもround対応へ変更したため、round 1の通知がround 2の再申請通知を抑止しない。
- V78は変更していない。B1の外部配信を`V79__notification_webhook_outbox.sql`へ追加し、通知本体とoutboxを同一transactionで保存する。commit前のWebhook呼出しを避け、commit後callbackまたはschedulerから、別worker beanの`REQUIRES_NEW` transactionでclaim→送信→SENT/RETRY/FAILED更新を行う。claim競合、30分超PROCESSING回復、指数backoff、最大5回、dedupe UNIQUEを実装した。
- H2 `schema-approval-h2.sql`へoutbox表・due indexを反映した。`NotificationServiceImpl`の通常publish経路もtransaction化し、outboxが利用可能な構成では直接Webhook非同期経路を使わない。
- 定向検証: `ApprovalNotificationSlaTest` 6件、`NotificationServiceImplTest` 9件、`NotificationOutboxDispatcherTest` 5件、`NotificationOutboxServiceTest` 5件、計**25 / failures 0 / errors 0 / skipped 0**。round 1→2のREQUESTED/RETURNED/SLA key分離、SLA境界/重複/NULL、宛先限定、outbox成功/RETRY/FAILED/claim競合/重複登録を確認した。
- 残るrelease gate: Docker daemon未起動のためV79を含む実MySQL fresh/legacy/upgrade smoke、複数JVMのclaim/ShedLock競合、実Webhook endpoint、commit前例外の実DBrollbackは未検証。V78適用状況も引き続き`flyway_schema_history`未照会であり、適用済み/未適用を断定しない。

## B1/M追跡再検証（2026-08-03、current B1作業木）

前項のclean Head基準測定後に、V79とB1の実装・テストを含むcurrent作業木で追加回帰を実行した。V75/V76/V77/V78は変更していない。V78の`flyway_schema_history`適用状況は引き続き未照会であり、適用済み/未適用を断定しない。

- **広いB1回帰**: `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`WebhookNotifierTest`、`ApprovalNotificationSlaTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`を実行し、**46 tests / failures 0 / errors 0 / skipped 0 PASS**。
- **M定向回帰**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、`ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件を再実行し、計**46 tests / failures 0 / errors 0 / skipped 0 PASS**。
- **migration/static回帰**: `MigrationScriptIntegrityTest` 26件、`SpecDispatchConsistencyTest` 8件、`FlywayMigrationSmokeTest` 2件を実行し、計**36 tests / failures 0 / errors 0 / skipped 2**。skip 2件はDocker daemon unavailableによる`FlywayMigrationSmokeTest`である。
- **current Head相当のCI相当全量**: `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`をcurrent B1作業木で実行。Node `v24.18.0`は利用可能、Docker daemonは利用不可。内部の`mvn -B clean test`は**1432 tests / failures 1 / errors 0 / skipped 12**、script exit 1。
- **全量failure**: `MobileResponsiveLayoutTest.クイック作成ボタンのラベルは小画面で非表示にできるようspanで包まれている`の`quick-add-label`契約。clean Head基準の1420件実測と同じ既知failureであり、B1/M追加テストを含むcurrent作業木では総数が1432件となった。`MobileResponsiveLayoutTest`単独は23 / 0 / 0 / 0 PASSだが、全量failureをPASSへ読み替えない。
- **skip内訳**: 12 test cases / 10 report classes。`CustomerContactPrimaryConcurrencyTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`ConcurrentLoginSessionSmokeTest`。Docker未起動が原因で、CI契約のzero skippedは未達。
- **未検証のrelease gate**: 実MySQLでのV79 fresh/legacy/upgrade/partial-repair/repair/concurrent smoke、commit前例外時の実DB rollback、複数JVMのShedLock/claim競合、実Webhook endpoint、実ブラウザdesktop/390pxの5業務通しは未実施。したがってB1/Mのcheckboxは未完了のまま維持し、今回の定向PASSだけでrelease PASSとは判定しない。

## Round 3追跡続行（2026-08-03、RG-3/B1）

### RG-3 Mobile全量failureの原因特定とfixture修正

- Surefireの`alphabetical`順を維持した固定prefix（`SesManagerApplicationTests`、common/config/controller/crm/dto/mapper/migration/scripts/service各prefix、web前半）へ`EngineerFollowupServiceTest`だけを追加し、`MobileResponsiveLayoutTest`と同時実行した。結果は**1392 tests / failures 3 / errors 0 / skipped 12**。内訳はMobileの`quick-add-label` failure 1件と、通常`powershell.exe`のExecutionPolicyに起因する`VerifyLikeCiPowerShellCompatibilityTest` 2件である。
- 汚染元の`EngineerFollowupServiceTest`は`@Sql("/sql/engineer-schema-h2.sql")`で`m_menu`/`t_role_menu`をDROP/CREATEしていたが、メニュー行を投入していなかった。`GlobalControllerAdvice`の管理者向け`allowedMenus`は`MenuCacheService`の`m_menu`を読むため、後続Mobileのquick-add markupが消える共有H2状態汚染を特定した。
- `src/test/resources/sql/engineer-schema-h2.sql`へV2相当の9メニューと管理者・営業・HR・マネージャーのrole mappingを追加した。`MobileResponsiveLayoutTest`とUI assertは変更していない。
- 修正後、`EngineerFollowupServiceTest`→`MobileResponsiveLayoutTest`を直列実行し、**26 tests / failures 0 / errors 0 / skipped 0 PASS**。これはfixture修正の定向回帰であり、L4全量のzero failure/zero skippedを意味しない。

### B1残要件とscheduler Demo相当

- `ApprovalNotificationSlaTest` 6件で、期限直前/ちょうど/直後、同一超過の重複抑止、対象本人以外への宛先限定、`sla_hours IS NULL`の対象外、round 1→2のREQUESTED/RETURNED/SLA dedupe分離を確認した。
- `NotificationOutboxSchedulerIntegrationTest` 1件を追加し、Webhook未設定のSYSTEM通知をoutboxへ1件投入して、実Spring beanの`NotificationOutboxScheduler.dispatchPending()`を2回起動した。実測は1回目のみdue行1件を処理し、2回目はdue対象なし。同一dedupe keyはDB上**1行のみ、`SENT`、`attempt_count=1`**となった。
- `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`WebhookNotifierTest`、`ApprovalNotificationSlaTest`、`NotificationOutboxSchedulerIntegrationTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`の8クラスを再実行し、**47 tests / failures 0 / errors 0 / skipped 0 PASS**。
- 残るB1 release gateは、実MySQLでのV79 fresh/legacy/rollback/lock、複数JVMのShedLock/claim競合、実Webhook endpoint、commit前例外時の実DB rollbackである。Docker daemon・DB接続情報がないため未検証とし、B1 checkboxは`[ ]`のまま維持する。

### 現時点の判定

- RG-3の既知Mobile failureは、汚染元fixtureを修正し、定向prefixでは解消した。L4全量の再測定、Docker依存skip解消、実MySQL/実ブラウザは別gateとして残る。
- B1の定向要件とscheduler二重起動Demo相当はPASS。ただし実環境gate未達のため、B1/Mを完了扱い・release PASS扱いにはしない。V75〜V78は変更していない。

## CI run証跡（2026-08-03、run `30790999682`）

- Workflow `CI` のpush run。Headは`a33a6e9b1e1f8a973ccb45c59e5a1a38805cda8d`、URLは<https://github.com/satoshi2024/ses-manager-pro/actions/runs/30790999682>、結論は`failure`。
- Docker検証stepは成功した。ログは`Docker Engine 28.0.4 / API 1.48`で、TestcontainersもDocker server `28.0.4`へ接続している。したがってこのCI runではDocker依存testはskipされず、実MySQL経路まで到達した。
- `FlywayMigrationSmokeTest`は2件実行・failures 1・errors 0・skipped 0。ログ上、fresh側は77 migrationを適用してschema version `v78`へ到達した。一方、`FlywayMigrationSmokeTest.java:564`の`V78legacy申請は終端済みならparticipantをbackfillし多名旧snapshotの進行中申請は停止する()`で、終端fixtureの`participant_role='applicant'`件数が`expected: <1> but was: <3>`となった。これはV78 legacy participant backfill検証の実MySQL failureであり、RG-1のPASS証拠ではない。V75〜V78は変更しない。
- `MobileResponsiveLayoutTest`は23件実行・failures 1・errors 0・skipped 0。`MobileResponsiveLayoutTest.java:148`の`quick-add-label` assertionで失敗し、同runのログでは`m_menu`取得件数が0だった。これはfixture修正前のHead（`a33a6e9`）に対するCI結果であり、current作業木の`EngineerFollowupServiceTest` fixture修正およびV79未commit変更は含まれない。したがってRG-3定向回帰の26件PASSを無効化する証拠ではないが、CI全量PASSの証拠にもならない。
- 最終集計は`Tests run: 1420, Failures: 2, Errors: 0, Skipped: 0`、`BUILD FAILURE`。`Ensure no tests were skipped` stepは前段のtest failureにより実行されていない。
- 判定: CIはDocker/Testcontainers到達性の証拠を提供したが、V78 legacy smoke failureとMobile全量failureがあるためRG-1/RG-3のrelease PASSには使えない。current作業木のL4再測定、実ブラウザ、ローカルDB接続（RG-4）は別途未達のまま確認する。B1/M checkboxは未完了を維持する。

## 最終L4再測定とrelease gate確認（2026-08-03、fixture修正後のcurrent作業木）

- `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`を実行した。Nodeは`v24.18.0`でJS構文チェックを実行可能、Docker daemonはローカルで利用不可と再確認した。
- 内部の`mvn -B clean test`は**1433 tests / failures 0 / errors 0 / skipped 12**、Maven本体は`BUILD SUCCESS`。script全体は、CI契約と同じskip検出でDocker依存の12 test cases（10 report classes）を検出したためexit 1となった。
- skipは`CustomerContactPrimaryConcurrencyTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`ConcurrentLoginSessionSmokeTest`。したがってL4はfailure 0まで到達したが、CI契約のzero skippedは未達である。
- RG-3: `EngineerFollowupServiceTest`のH2 fixtureへV2相当メニュー9行とrole mappingを復元した後の全量でfailure 0となり、Mobileの`quick-add-label` failureはcurrent作業木では解消した。fixture修正後の`EngineerFollowupServiceTest`→`MobileResponsiveLayoutTest`直列回帰も26 / 0 / 0 / 0 PASS。Mobileのassert/UIは変更していない。
- RG-1: current環境のDocker daemonが利用不可でV79を含む実MySQL smokeは未実施。CI run `30790999682`はDocker/Testcontainers到達後にV78 legacy participant検証（expected 1 / actual 3）でfailureとなったため、実MySQL gateのPASS証拠ではない。
- RG-2: Playwright/Selenium/WebDriver等のbrowser通し経路とChrome/Edge/Firefox実行ファイルが環境にないため、desktop/390pxの5業務browser Demoは未検証。
- RG-4: `mysql` CLIなし、`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`未設定。`localhost:3306`へのTCP接続のみ確認済みで、認証・DB名・`flyway_schema_history`照会は未実施。V78適用状況は未確認のまま。
- B1/M: 定向・scheduler二重起動Demo相当とL4 failure 0は確認できたが、実MySQL、複数JVM競合、実Webhook、commit前例外時の実DB rollback、browser Demo、zero skippedが未達のため、`tasks.md`のB1/M checkboxは`[ ]`を維持し、release PASSとは判定しない。
- 最終差分ゲート: `git status --short --untracked-files=all`は台帳・tasks・H2 fixtureの変更とschedulerテスト新規ファイルの4件のみ。`git diff --check`はexit 0。V75/V76/V77/V78の`git diff --name-only`は空。commit/pushは実施していない。

- **HEAD/commit補足**: 最終ゲート時の実HEADは`b380a5a1bbf13e5cf0f61168e429bfafe467cc58`（親`a33a6e9`）で、commit日時は2026-08-03 17:24:50 JST、`origin/main`/`origin/HEAD`も同じcommitを指していた。これは本継続のテスト・台帳追記開始時点ですでに存在した履歴であり、本継続中にcommit/pushコマンドは実行していない。既存commitの巻き戻しや追加commitは行わない。従来の`Head a33a6e9`記録はその時点の履歴として保持し、本節のHEADを最終ゲート時点の正とする。

## S07-R4-P1-10追跡結果（2026-08-03、現行HEAD `df674db`）

- CI run `30790999682`の`expected: <1> but was: <3>`を、V78 migration defectと断定せずソースとfixtureの対応で再切り分けした。`V78__approval_workflow_round_participant_version.sql`のparticipant backfillは、申請者を`participant_role='applicant'`で1件、旧snapshotの`approverUserIds` 2名を`participant_role='approver'`で2件投入する2文だけである。
- `FlywayMigrationSmokeTest`の最初のassertはapplicant件数1、次のassertはapprover件数2であり、いずれもこの形状と一致する。失敗していた第三assertは`countParticipantsForRound()`（`request_id`と`round_no`だけで絞り、roleを絞らないround総数helper）へ期待値1を渡していた。round総数の正しい値は**1 + 2 = 3**であり、CI failureはtest oracleの誤りだった。`participant_role`の誤格納、Flyway `DELIMITER`分割副作用、fixture累積は確認されなかった。
- 修正は`src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java`の第三assertを`1`から`3`へ変更し、申請者1名＋承認者2名の内訳を日本語コメントで明記した。`V78__approval_workflow_round_participant_version.sql`、V75〜V78のmigration SQL、Flyway parser設定は変更していない。
- Docker Desktopが起動したため、修正後に実MySQL Testcontainersを実行した。`FlywayMigrationSmokeTest`は**2 / failures 0 / errors 0 / skipped 0 PASS**、`MigrationScriptIntegrityTest`は**26 / 0 / 0 / 0 PASS**。fresh migrationはV79まで適用され、legacy V77→V78 success/backfillと非終端停止経路を含む対象smokeが通過した。
- CI相当全量`verify-like-ci.ps1`もDocker有効・Node `v24.18.0`で開始したが、1800秒上限で`FlywayRepairRunbookTest`付近の途中終了となった。Maven全量の最終集計は取得できず、全量PASS/zero-skippedとは判定しない。残留していたMaven/Surefireプロセスはこの途中実行のため停止した。
- `gh run list --commit df674db0effd9fa35eaedd1d4474adcfb40e9125`には修正後のremote CI runは表示されなかった。今回の修正は未commit作業木にあり、commit/pushは実施していないため、GitHub CIでの修正後runと`Ensure no tests were skipped`到達は未確認である。
- 判定: S07-R4-P1-10の「V78がparticipant_roleを誤生成する」主張は、実MySQL再実行で否定された。修正後の対象smokeはPASSであり、migration側P1 blockerは解消（test oracle修正）と判定する。ただしRG-1のremote CI修正後証跡、CI相当全量の完走、browser/RG-4等の既存release gateは別途未達のまま。B1/M checkboxは変更しない。