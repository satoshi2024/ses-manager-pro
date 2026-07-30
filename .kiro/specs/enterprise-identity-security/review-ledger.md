# Review Ledger — 企業認証・セキュリティ（S03）

## 2026-07-30 fixed Head `f06b01d` 再独立Review FAIL対応

- Reviewの正式base: `b36922ae6287a4bc68681e577d9be1ba3180ed72`。
- Reviewの正式Head: `f06b01d426176d295a46fa3bfad0e2a1769161f1`。Review開始時点で
  `main = origin/main`、working tree cleanであることが確認された。
- 今回の修正scope: 同Head上の未commit working-tree patch。次のfixed Headはまだ存在しないため、
  判定は`FIX/REVIEW`を維持し、S04 `legal-document-ledger-archive`へ進めない。
- 前回R03の6件は正式Headの再Reviewで閉鎖が確認された。本節では、その後に検出された
  export/download suffixの未知API迂回、break-glassのMFA管理API迂回、台帳Head不整合を別Issueとして追跡する。
- T020 `M. セキュリティ回帰`は未checkのまま維持する。本修正patchをfixed Headへcommitした後も、
  独立Review P0=0/P1=0/PASSと外部gate完了まではS03を完了扱いにしない。

### Issue Register

| Issue | 深刻度 | 状態 | 修正・再現境界 |
|---|---|---|---|
| S03-R04-P1-01 | P1 | FIXED_PENDING_REVIEW | resource inventory判定をexport/download suffix判定より先に実施。未知の通常API、`/export`、`/download`、拡張子download、未知security APIをactionへ昇格させず403 |
| S03-R04-P1-02 | P1 | FIXED_PENDING_REVIEW | 認証基盤の除外をmethod+exact URI allowlistへ限定。管理者向けMFA resetを独立`mfa.reset` actionとして解決し、break-glass事件のexact scope外では即時失効 |
| S03-R04-P2-01 | P2 | FIXED_PENDING_REVIEW | 正式Review Headを`f06b01d`として記録し、本修正は未commit patchで次のfixed Headではないことを中央台帳と同期 |

### TEST SCOPE DECISION（T014〜T019）

| Task | selected level / test対象 | 直接consumer | 除外suite | 昇格条件 / 次L4 checkpoint |
|---|---|---|---|---|
| T014 | L0。Review本文、正式Base/Head、新Issue ID、台帳scope | requirements/design/tasks/review ledger | Maven/Node/Docker/browser。production差分なし | なし。次はT020 |
| T015 | L0。schema/migration変更なしを差分確認 | Flyway history、H2 schema inventory | Flyway/Docker/MySQL。DDL・Entity差分なし | schema昇格条件なし。次はT020 |
| T016 | L0。OIDC/provisioning変更なしを差分確認 | OIDC config、provision service、login/logout | 実Entra/Conditional Access。対象差分なし | identity provider昇格条件なし。次はT020 |
| T017 | L3。break-glass exact scope、`mfa.reset`、認証基盤exact allowlist | resolver、BreakGlassService、MenuPermissionFilter、MFA/session API・service・filter | 実2名訓練、multi-instance、実IdP。外部環境なし | shared security境界に該当するが既存中間L4を重複せず、次はT020 |
| T018 | L3。inventory-before-suffix、未知通常/security API、action matrix | resolver、AuthorizationService、MenuPermissionFilter | 実browser role matrix。UI差分なし | shared security境界に該当するが既存中間L4を重複せず、次はT020 |
| T019 | L0。file upload/download/scanner変更なしを差分確認 | FileStorage、metadata、download/rescan | file/ClamAV回帰。対象差分なし | file/schema昇格条件なし。次はT020 |

### 実行結果

- L1 direct: `ActionPermissionResolverTest`、`BreakGlassServiceImplTest`、`MenuPermissionFilterTest`、
  `SecuritySessionApiControllerTest`を実行し、**26 tests / 0 failures / 0 errors / 0 skipped**。
- L3 direct consumer: resolver/action matrix/authorization/menu/audit/break-glass/login/session/MFAの12 suiteを実行し、
  **60 tests / 0 failures / 0 errors / 0 skipped**。
- exact allowlist確定後の最終直接回帰: resolver/menu/break-glassの3 suiteを実行し、
  **28 tests / 0 failures / 0 errors / 0 skipped**。
- 本ReviewではL4を再実行していない。`f06b01d`には前回の条件式中間L4
  **1036 tests / 0 failures / 0 errors / 7 skipped**の証拠があるが、これは本未commit patchのL4証拠ではない。
  test policyに従い独立Reviewのみを理由とする重複全量は行わず、次のL4 checkpointをT020とする。
- JS/UI/schema/file差分なしのため、Node/browser/Docker/MySQL/ClamAVは本checkpointから除外した。

## 2026-07-30 merge Head `b36922a` 再Review FAIL対応

- Reviewの正式base: `b36922ae6287a4bc68681e577d9be1ba3180ed72`。
- 修正scope: 同Head上の未commit working-tree patch。固定fix Headではないため、独立再Reviewが完了するまで判定は`FIX/REVIEW`とする。
- migration: 公開済みV66を変更せず、後続予約V67を維持できるよう **V66.1**
  (`V66_1__close_security_review_boundaries.sql`) を追加した。
- T020 `M. セキュリティ回帰`は未checkのまま維持する。実Entra、実browser、2名break-glass訓練、依存scan、実MySQLは未完了である。

### Issue Register

| Issue | 深刻度 | 状態 | 修正・再現境界 |
|---|---|---|---|
| S03-R03-P1-01 | P1 | VERIFIED_CLOSED | 非管理者の全局`*`をV66.1で削除し、既知resourceへ展開。未知APIと未実装portalをfilterで403、legacy fallbackもinventory外actionを拒否 |
| S03-R03-P1-02 | P1 | VERIFIED_CLOSED | break-glass事件へexact `allowed_actions`を追加。sessionを事件へ固定し、各requestで状態・期限・scopeを再検証してpersistent sessionを即時失効。参照/exportを含む全操作を事前必須監査し、ACTIVE時に申請者・承認者へ即時通知 |
| S03-R03-P1-03 | P1 | VERIFIED_CLOSED | legacy移行とrescanでmax size・magic/構造検証をscanner前に実施。偽装PDF・上限超過はclean scannerでもquarantine |
| S03-R03-P1-04 | P1 | VERIFIED_CLOSED | page URIをmatched menuのAPI prefixから同じview actionへ解決し、restrictive groupのpage直達を403 |
| S03-R03-P2-01 | P2 | VERIFIED_CLOSED | prod validatorでissuer/authorization/token/JWK/user-infoをuserinfoなしの有効なHTTPS URLに限定 |
| S03-R03-P2-02 | P2 | VERIFIED_CLOSED | 台帳baseを`b36922a`へ更新し本Issue Registerを追加。修正は`f06b01d`としてcommit・push済み |

### TEST SCOPE DECISION（T014〜T019）

| Task | selected level / test対象 | 直接consumer | 除外suite | 昇格条件 / 次L4 checkpoint |
|---|---|---|---|---|
| T014 | L0。Review本文、issue ID、Head、migration採番、台帳整合 | requirements/design/tasks/review ledger | Maven/Node/Docker/browser。文書inventoryのみ | なし。次はT020 |
| T015 | L3。V66.1 integrity、H2 schema/seed、fresh/V63 upgrade smoke定義 | Flyway history、permission seed、break-glass entity/mapper | 実MySQLはDocker daemon不在で2件skip | schema変更のため条件式中間L4を1回実施。次はT020 |
| T016 | L3。prod HTTPS metadata、OIDC registration/login/security integration | prod validator、client registration、login/logout、identity API | 実Entra/Conditional Accessはcredentialなし | security変更。次はT020 |
| T017 | L3。事件scope/session期限/監査/通知/ログイン、persistent session consumer | LoginSuccessHandler、ApiAuditFilter、PersistentSessionFilter、session API | 実2名訓練・multi-instanceは環境なし | security/session変更。次はT020 |
| T018 | L3。未知API/portal、role×action、page/API parity、permission/user consumer | resolver、AuthorizationService、MenuPermissionFilter、sidebar、permission/user API | 実browser role matrixはUI変更なし・外部gate | security/cache境界。次はT020 |
| T019 | L3。legacy max/magic、clean/infected/unavailable、rescan/load | LegacyUploadMigration、FileStorage、metadata、download | 実ClamAVはsandboxなし | shared FileStorage変更。次はT020 |

### 実行結果

- L1/L2定向: 65 tests / 0 failures / 0 errors / 0 skipped。
- L3 subsystem: 124 tests / 0 failures / 0 errors / 2 skipped。skipはDocker必須の
  `FlywayMigrationSmokeTest`と`FlywayV63UpgradeMigrationSmokeTest`。
- 最終差分の直接再確認: 39 tests / 0 failures / 0 errors / 0 skipped。
- 条件式中間L4: 共有security/schema/session/FileStorage変更のため1回だけ実行し、
  **1036 tests / 0 failures / 0 errors / 7 skipped**。skipはDocker必須6件とCJK font必須1件。
- `git diff --check`: exit 0。JS/UI変更なしのためNode/browserは本checkpointから除外。
- 未成立証拠: Docker daemon不在のためV66.1のfresh/V63-upgrade実MySQL証拠なし。固定fix commitと独立再Reviewも未完了。

## 追加修正（2026-07-30 S02/S03実装差分の独立バグ検査対応）

merge前のbranch実装（S02 organization-management-accounting、S03 F1〜B2）に対する追加検査で、
Reviewでも回帰testでも検出されていなかった欠陥を修正した。V63〜V65は変更せず、**V66を新規追加**した。

| # | 深刻度 | 欠陥 | 原因 | 修正 |
|---|---|---|---|---|
| B-01 | P0 | 営業/HR/要員がdashboard・analytics・quotation・work-record・sales-performance・monthly-closing等の`/api/**`で403 | `ActionPermissionResolver`はURI rootからaction keyを機械生成するのに対し、V64 seedと`legacyRoleAllows`が許可listの列挙だった。未列挙keyが全て拒否され、R3.1の後方互換に反した | V66でbaseline `*`＋拒否指定へ変更。`AuthorizationServiceImpl`は拒否をbaselineより優先評価。`legacyRoleAllows`も同じ規則へ書き換え |
| B-02 | P1 | マネージャーがaction層で`user.*`/`permission.manage`/`payroll.view`/`audit.security.view`/`file.scan.retry`を素通し | V64が`role-manager`へ全権限wildcard `'*'` をseedしたうえ、拒否の仕組みが無かった（`role-admin`はaction 0件）。SecurityConfigのhasRole('管理者')とmenu権限だけが実害を止めていた | V66でrole-adminへbaselineを付与し、role-managerには機密actionの拒否指定を追加。baseline自体は後方互換のため維持する |
| B-03 | P1 | 営業/HRが`engineer.delete`/`customer.delete`/`contract.delete`を失う | V64 seedに`.delete`が無く、wildcardも`engineer.*`ではなく単一keyだった | V66のbaselineで回復 |
| B-04 | P1 | 既存アップロード済みファイル（スキルシート・要員写真・取込原本）が升级後に全て403 | `FileStorageServiceImpl#load`が`t_file_security_metadata`の`PUBLISHED`+`CLEAN`と`uploads/published/`配下を要求するが、V63はbackfillせず実体も移動しない | `LegacyUploadMigrationService`＋`LegacyUploadMigrationRunner`を追加。起動時にscanし、CLEANのみpublishedへ移す。INFECTED/UNAVAILABLEはquarantine保持で再scan可能 |
| B-05 | P1 | V64適用後に作成したユーザーはgroup未割当のままでlegacy fallback判定になる | `UserApiController#save`がdefault group割当を行っていなかった（role変更経路だけ実装済み） | 作成時も`replaceAssignments(id, Set.of(), auth)`で割当。V66でbackfillも再実行 |
| B-06 | P2 | 同時loginで遷移先が混ざる | `LoginSuccessHandler`（singleton）が`setDefaultTargetUrl`/`setAlwaysUseDefaultTargetUrl`でフィールドを書き換えていた | requestごとのlocal変数＋`getRedirectStrategy().sendRedirect`へ変更 |
| B-07 | P2 | `MfaEnforcementFilter`/`PersistentSessionFilter`がServletコンテナへ二重登録される | `MenuPermissionFilter`/`ApiAuditFilter`にはある`FilterRegistrationBean(enabled=false)`が未作成だった。既定の順序ではchain内が先に走るため実害は出ないが、順序仮定に依存していた | 両filterへ同じ無効化Beanを追加 |
| B-08 | P2 | 予算CSV取込が負数・欠損を受け付ける | `@Valid`は`@RequestBody`にしか効かず、CSV経路は手組みでrecordを生成していた（コメントは「負数を受け付けない」と記載） | `jakarta.validation.Validator`で1行ごとに同じ制約を評価 |
| B-09 | P3 | `defaultGroupId`でroleがnullだとNPE | switch式にnullガードが無かった | 400 `error.permission.invalidGroup`へ |

### 追加修正 TEST SCOPE DECISION

- selected level: **L4**（全量）。共有security境界・schema・file storageに跨るため。
- exact result: 全量Maven **1027 tests / 0 failures / 0 errors / 6 skipped**。skip 6件は全てDocker必須のTestcontainers
  （`FlywayMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayLegacyV60MigrationSmokeTest`、
  `FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`ConcurrentUpdateTest`）。
- 追加したtest: `ActionPermissionMatrixTest`（role×action matrixをH2上のseed状態で検証。8件）、
  `LegacyUploadMigrationServiceTest`（6件）、`AuthorizationServiceImplTest`の拒否優先1件、
  `UserApiControllerTest`のdefault group割当1件、`ManagementAccountingApiControllerTest`のCSV負数拒否1件、
  `FlywayMigrationSmokeTest`へV66 seed assertion（deny_flag列、baseline 4件、manager拒否5件、要員my.*）。
- **未実施の重要gate**: 本環境ではDocker daemonは起動できたが、proxyがDocker Hubのblob CDNを403で遮断するため
  `mysql:8.0` imageを取得できず、**V66のMySQL方言・seedは実MySQLで一度も実行されていない**。
  V66が使う構文は`ALTER TABLE ... ADD COLUMN ... COMMENT`と`INSERT IGNORE ... SELECT ... UNION ALL`の2種だけで、
  後者はV64と同一形であり実MySQLで実証済みである（当初入れていたmulti-table `DELETE a FROM ... JOIN`は、
  直後のbaseline INSERTで同じ行を戻すだけの無意味な操作だったため削除した）。それでも
  **merge前にDockerのあるCIで上記5 smokeを実行すること**をrelease gateとして残す。
- 未実施（既存の外部gateを継続）: OWASP依存スキャン、実Entra login/logout・MFA assurance、
  desktop/390px実ブラウザDemo、2名break-glass復旧訓練。
- migration採番: V66を消費したため、後続spec（#4〜#17）の予約をV67〜V80へ繰り上げ、
  `README.md`予約表・各spec design/tasks・中央conversation資料を同一差分で更新した。


## 現行判定（2026-07-30 再独立Review FAIL対応）

- 証拠scope: repository Headは`5cd1dd3e1dcbe894f967b87737fa237f27771d3e`、修正は未commit working-tree patchである。正式Review Headまたは固定fix Headの証拠ではなく、最終PASSには使用しない。
- P0: 公開済みV63を`710eecc`時点の内容へ復元し、seed/backfillをV64、break-glass制御DDLをV65へ追加した。`FlywayV63UpgradeMigrationSmokeTest`は旧V63適用後にrepairなしでV64/V65へupgradeし、`FlywayMigrationSmokeTest`は空MySQLからV65まで成功した（各1/0/0/0）。
- P1: API/action inventoryとgroup優先認可/sidebar、OOXML構造・画像decode・polyglot拒否、quarantine metadata保護、固定OIDC metadataとtimeout、incident/IdP障害確認/申請者と異なる2名承認、account/session/source別MFA rate limit、critical audit fail-closed、prod MFA enrollment検証を実装した。
- P2: logout時のpersistent session失効、外部identity duplicate-keyのidempotent/409化、台帳の証拠scope修正を実装した。`M. セキュリティ回帰`は外部gate未完了のため未checkのまま保持する。
- L1〜L3: 統合定向回帰111 tests / 0 failures / 0 errors / 0 skipped。追加のbreak-glass/MFA回帰11/0/0/0、MFA scope回帰3/0/0/0、4言語message key 36件、Node/JS 42 files / 0 failures、`git diff --check` exit 0。
- T020 L4: 1回だけ実行し、外側30分timeoutまでに168 reports / 1008 tests / 6 failures / 0 errors / 1 skippedを出力した。6 failuresはanonymous filter、candidate/profile/autocomplete legacy整合、break-glass entity列mappingの4 consumerに限定され、修正後の該当6 suitesは101 tests / 0 failures / 0 errors / 0 skipped。方針に従い全量L4は無条件再実行せず、したがって最終treeのL4全緑証拠は未成立と記録する。
- 未完了gate: repositoryにOWASP dependency-check相当の設定がない。実Entra login/logout・MFA assurance、desktop/390px browser、login→権限変更→session失効、2名break-glass復旧訓練は環境/credential未提供で未実施。release判定は`FIX/REVIEW`、独立Review P0=0/P1=0/PASSと外部gate完了まで`PASS`へ進めない。

### 再Review対応 TEST SCOPE DECISION（T014〜T019）

| Task | level / 対象 | 直接consumer | 除外suite / 理由 | 昇格条件 / 次L4 checkpoint |
|---|---|---|---|---|
| T014 | L0。Review、inventory、policy、台帳scope | requirements/design/tasks/identity inventory | Maven、Docker、browserはproduction差分なし | なし。次はT020 |
| T015 | L3。V63 checksum静的検査、旧V63→V65、空DB→V65 | Flyway history、V64 seed/backfill、V65 DDL、H2 schema | legacy V60/repair/V62 fixtureは今回P0の直接consumer外 | schema変更あり。T020でL4を1回実行 |
| T016 | L3。固定OIDC metadata、unreachable provider起動、prod context、identity競合 | ClientRegistration、token client、SecurityConfig、provision service | 実Entra/CA/logoutは外部tenantなし | 共有security変更あり。T020でL4を1回実行 |
| T017 | L3。incident二者承認、IdP障害限定、MFA rate limit/critical audit、session logout、prod enrollment | login success、MFA API、audit、persistent session、prod validator | 実2名訓練/多instanceは外部環境なし | security/session/transaction/schema変更あり。T020でL4を1回実行 |
| T018 | L3。全business API resolver、restrictive group、sidebar、legacy seed | MenuPermissionFilter、AuthorizationService、GlobalControllerAdvice、candidate/profile/autocomplete | 実browserの権限matrixは外部gate | cache/security/schema変更あり。T020でL4を1回実行 |
| T019 | L3。ZIP/OOXML/MIME/画像/PDF、polyglot、quarantine cleanup | FileStorage、FileKind、cleanup/reference provider、download/rescan | 実ClamAV長時間timeoutはsandboxなし | shared file/security変更あり。T020でL4を1回実行 |

## 旧判定（T014完了、T015開始前）

- spec状態: `IN PROGRESS`。S02 R02はHead `f6f002706dd201ed40e1d2ba808c30d6bb96eea6`でP0=0/P1=0/PASS、中央台帳と同期済み。
- implementation base: `f6f0027`、working treeはT014変更前にclean。
- migration: V63。V61/V62はorganization-management-accountingの既存履歴として不変・再利用禁止。
- T014状態: 開発gate完了、独立Review待ち。実Entra test tenant/app登録、実OIDC login/logout、実break-glass訓練は外部gateとして未実施であり、実施済み・PASSとは記録しない。
- T015状態: **実装・L3検証完了、独立Review待ち**。V63のidentity provider、external identity、MFA、recovery hash、persistent session、permission group/action、file quarantine/scan metadataを追加。既存V1公開履歴は変更せず、V63をbaseline後の増分DDLとした。
- T015 TEST SCOPE DECISION: task / `f6f0027` base + T015 working tree、changed contracts / V63 schema・Entity/Mapper・H2 replay・MySQL smoke、selected level / **L3**、selected tests / `EnterpriseIdentitySchemaTest` 3件 + `MigrationScriptIntegrityTest` 11件 + `FlywayMigrationSmokeTest`・`FlywayLegacyV60MigrationSmokeTest`・`FlywayV62ClosedHistoryMigrationSmokeTest`各1件、直接consumer / V63 Flyway適用、H2 schema、identity/file metadata unique、recovery hash、tenant-scoped key。
- T015 exact result: 定向H2/静的 **14 tests / 0 failures / 0 errors / 0 skipped**、fresh/legacy/V62 closed MySQL **3 tests / 0 failures / 0 errors / 0 skipped**、合計**17 tests / 0 failures / 0 errors / 0 skipped**。Entity/Mapper diagnosticsも問題なし。
- T015 excluded suites: 全量Maven、Node/JS、browser、OIDC provider、MFA runtime、action enforcement、scanner runtimeはT015のDDL直接consumer外のため除外。T020 L4で統合する。
- T015 escalation: schema/entity/H2/FlywayはL4昇格条件に該当するが、L3が説明可能な全緑で未知回帰なしのため中間L4は実施しない。次のL4 checkpointはT020 M。
- T015 rollback: V63適用前は機能公開を止める。適用後は過去migrationを編集せずバックアップ復元。identity secretは平文保存せず、V61/V62は変更しない。
- T015開始条件: T014のidentity/subject/claim/action/PII/file/scope契約を`identity-security-inventory.md`へ固定済み。V63 DDL実装を開始する。

### T014 TASK CONTRACT

- requirements/acceptance: R1.1〜R1.5、R2.1〜R2.4、R3.1〜R3.4、R4.1〜R4.4、R5。
- 顧客効果: Entra OIDC、限定break-glass、MFA/session失効、action権限、file scan拒否の境界を後続実装で検証可能にする。
- 変更file: `identity-security-inventory.md`、本台帳、S03 `tasks.md`、中央実行台帳。
- 変更禁止file: production Java/SQL、`SecurityConfig.java`、共通認証ファイル。
- 検証契約: issuer+subject unique、email単独link禁止、招待/承認必須、group未設定時のみlegacy fallback、未知file/scan障害fail-closed、秘密非出力。
- rollback: inventoryは文書revert。後続V63/feature flagは適用前に公開せず、適用後はバックアップ復元。

### T014 TEST SCOPE DECISION

- task / commit: T014 / `f6f0027`をbaseとするworking-tree diff。
- changed contracts: G1 decision trace、OIDC claim/provision/logout境界、MFA/session/action/file/PII inventory、V63予約。
- selected level: **L0**。
- selected tests and consumers: inventory必須marker（R1/R2/R3/R4/R5、V63、BG-01、auth.session.revoke、file.download）、requirements/design/tasks/policyの文書セット、V63予約整合、`git diff --check`。
- excluded suites and reason: Maven、Spring context、OIDC/MFA実provider、Docker/MySQL、Node、browser、security runtimeはproduction code未変更のため除外。実Entra/break-glass Demoは外部gateとしてT016/T017/T020へ繰越。
- escalation trigger present: **no**（T014自体は共有runtime/schema変更なし）。T015でV63/H2/schemaを変更した時点でL3、SecurityConfig/session/CSRF変更時はT016/T017のL3、全成果はT020でL4。
- exact result: inventory markers PASS、document set PASS、`git diff --check` exit 0。
- next L4 checkpoint: T020 M。T015はV63 DDLのL3（unique/hash/tenant分離、H2、fresh/legacy smoke）から開始。

## 前提修正ゲート（履歴）

S03の開始前レビューで検出された前提実装のP1を修正中。V61/V62はorganization-management-accountingで既に使用済みのため、S03の予約migrationはV63とする。S03自身のOIDC/MFA/permission/file-scan実装は未着手であり、この台帳は開始ゲートと先行差分の検証を追跡する。

| task | requirements | 変更file | test | Demo | 状態 | risk / rollback |
|---|---|---|---|---|---|---|
| pre-S03 P1-1 | R3.1〜R3.3 | `SystemConfigApiController`、`ScopeChangeInvalidator`、scope回帰test | commit/rollback generation、false→true→false、並行commit/rollback | H2で世代・cache遷移を確認 | FIX・定向test済み | rollback callbackはcacheを書き戻さず、後続commitを保持 |
| pre-S03 P1-2 | R2.2、R4 | V62、`EngineerAccountingHistory`、`EngineerMapper`、H2 schema | closed history MySQL fixture、direct-org優先、UNKNOWN未配賦、集計SQL | Docker smokeでV61→V62を再現 | 継続済み、既存回帰維持 | V62適用前バックアップへ戻す。UNKNOWN行は推測補完しない |
| pre-S03 P1-3 | R1.2〜R1.4 | `OrganizationServiceImpl`、組織サービス回帰test | 主所属順序、部分/前段重複、未来開始、有限validTo、同日開始 | H2で統合前後の期間を確認 | FIX・定向test済み | sourceを先に閉鎖/削除し、targetへ未被覆区間だけを移す |
| pre-S03 P1-4 | R1.4、R2.2、R4 | `OrganizationServiceImpl`、`EngineerAccountingHistoryMapper`、回帰test | 同日開始履歴、valid_from <= valid_to | H2で履歴整合性を確認 | FIX・定向test済み | 同日/未来開始は原地更新、過去開始だけ分割する |
| pre-S03 migration ledger | migration ledger | `enterprise-identity-security/tasks.md`、中央conversation/ledger | V63 grep整合 | 台帳の予約番号照合 | 修正済み | V62以降の予約番号を再利用しない |

## 最新判定（2026-07-29 第十八次Review対応）

最新独立Reviewの対象は`origin/main=fb91943`（PR #42 merge済み）で、P0=0/P1=4/P2=3だった。P1-1（cache失効原子性）、P1-2（manager全経路scope）、P1-3（混在組織請求書）、P1-4（NULL業務通知）を`90f50c0`で修正した。定向・全量は883/0/0/1、Node/JS syntax実行済み、Flyway fresh・legacy V60・repair・V62 closed fixture・migration integrity・ConcurrentUpdateは0 skippedで実行済み。混在請求書SQLとNULL通知のlist/count/visible/read-allに実行級回帰を追加した。

`90f50c0`は修正ブランチ上で未mergeであり、merge後独立Reviewは未実施のため、S02は`FIX/REVIEW`、S03は`NOT READY`。次の解放条件は、fix commitのPR merge、merge済みHeadでの独立Review P0=0/P1=0/PASS、中央台帳の再同期である。desktop/390px実ブラウザDemoはS03 blockerではないが、本番前硬门禁として未実施のまま保持する。


## T016 TASK CONTRACT

- task / base: T016 A1 / `f6f0027`をbaseとするworking-tree diff。
- requirements/acceptance: R1.1〜R1.5、R4.4、R5のOIDC login/provision/logout、subject紐付け、email自動link禁止、local fallback、監査/CSRF後方互換。
- changed contracts: Spring OAuth2 client依存、disabled-by-default OIDC設定、DB登録済みtenant+issuer+provider+subject解決、内部`LoginUser`互換OIDC principal、管理者承認link API、POST-only logout、login画面feature flag、4言語メッセージ。
- changed files: `pom.xml`、`SecurityConfig`/共通認証ファイル、OIDC config/principal/service、`SysUserMapper`/identity mappers、承認API/service/DTO、application/test config、login template、4言語messages、T016 direct tests。
- security invariants: emailだけでは自動linkしない。未知subject、無効provider、issuer不一致、無効user、IdP接続障害はfail closed。OIDC principalの`getName()`と`SecurityUtils`は外部subjectを内部username/user IDとして解釈せず、解決済み`SysUser`だけを使用する。承認APIは管理者限定かつCSRF/ApiAuditFilter対象。logoutはPOST+CSRFを維持し、provider logout redirectは固定login URLのみ。

## T016 TEST SCOPE DECISION

- task / commit: T016 / `f6f0027` base + T016 working-tree diff（未commit）。
- changed contracts: OAuth2 client wiring、SecurityConfig、認証principal、CSRF/logout、管理者承認API、tenant/provider/subject lookup、email collision、local-login feature flag。
- selected level: **L3**。
- selected tests and consumers: `OidcLoginUserServiceTest` 4件（既知subject成功、未知subject拒否、issuer/provider不一致、IdP timeout/error mapping）、`ExternalIdentityProvisioningServiceTest` 4件（明示承認、email衝突、email曖昧、local login無効時のbreak-glass限定）、`OidcSecurityIntegrationTest` 4件（admin boundary、CSRF、ApiAuditFilter経路、POST-only logout）、既存H2 test context、Maven compile、Java diagnostics、`git diff --check`。
- exact result: compile **SUCCESS**。T016 unit/service **8 tests / 0 failures / 0 errors / 0 skipped**。`OidcSecurityIntegrationTest` **4 tests / 0 failures / 0 errors / 0 skipped**。`git diff --check` exit 0。Diagnostics対象は問題なし。
- direct consumers: `SecurityConfig` OAuth2 login/logout chain、`CustomUserDetailsService` local policy、`LoginSuccessHandler`/`ApiAuditFilter`内部username契約、`SecurityUtils` current user ID/role/name、`ExternalIdentityApiController`のadmin+CSRF boundary。
- excluded suites and reason: 全量Maven、Node/JS全量、Docker/MySQL migration smoke、実Entra tenant/provider、実break-glass訓練、MFA/session runtime、action permission、file scannerはT016の直接consumer外でT020または後続Taskへ除外。実Entra login/logoutは認証情報のない外部gateとして未実施・PASS扱いしない。
- escalation trigger present: **yes**（`pom.xml`、`SecurityConfig`、認証principal/CSRFの共有security境界）。ただしL3 direct consumerが全緑で、既定の次L4 checkpointがT020に固定されているため、中間L4は追加しない。T017のsession/MFA wiringまたは未説明context failureが出た場合はT020前の安定checkpoint昇格を再判定する。
- Demo: 実Entra tenant/appでのlogin/logoutは未実施。fixture/MockMvcでは既知subject解決、未知subject拒否、管理者承認、email衝突拒否、IdP障害統一、CSRF、POST-only logoutを再現済み。
- rollback: `OIDC_ENABLED=false`（既定）でOIDC経路を無効化し既存local loginへ戻せる。`LOCAL_LOGIN_ENABLED=true`でlocal loginを復旧できる。V63以降のDB履歴やV61/V62、既存migrationは変更しない。依存追加を戻す場合はOIDC設定を無効化したうえでpomとOIDC実装を同一差分としてrevertする。
- next L4 checkpoint: **T020 M**。T017〜T019のL3完了後、OIDC/MFA/session/action/file成果を統合して一度だけL4全量・Node/JS・必要なDocker/MySQL・security/browser/provider gateを実施する。

## T017 TASK CONTRACT

- task / base: T017 A2 / `f6f0027`をbaseとするworking-tree diff。
- requirements/acceptance: R2.1〜R2.4。Entra userはIdP MFAを前提とし、限定break-glass管理者はTOTP MFAを必須化する。recovery codeはhash保存・一回限り・再発行時旧code無効化、persistent sessionは一覧・current/other/all失効・idle/max lifetime・同時session上限を提供し、role変更・無効化・削除・MFA reset時に対象sessionを失効させる。
- changed contracts: V63 MFA/session entity・mapper利用、AES-GCM暗号化TOTP secret、RFC 6238 TOTPとatomic replay防止、recovery hash、persistent session metadata、MFA pending/challenge、session管理API、LoginSuccessHandler登録、UserApiController lifecycle失効、MFA/session設定・messages・UI。
- security invariants: TOTP secretは平文保存しない。recovery codeは平文保存せずconditional updateで一回限りにする。last_used_stepはatomicに進めてreplayを拒否する。session DBにはraw JSESSIONID/IPを保存せずsalted hashとmetadataのみ保持する。OIDC principalはlocal TOTP対象外とし、break-glass local usernameだけを対象にする。role/status/delete/MFA resetの対象sessionは全失効する。

## T017 TEST SCOPE DECISION

- task / commit: T017 / `f6f0027` base + T017 working-tree diff（未commit）。
- changed contracts: MFA setup/enable/verify/reset、recovery code one-time、TOTP replay防止、persistent session登録・一覧・current/other/all失効、idle/max lifetime、同時session上限、role/status/delete lifecycle失効、break-glass MFA pending redirect、WebMvcTest security filter依存。
- selected level: **L3**。
- selected tests and direct consumers: `TotpUtilTest` 2件、`MfaServiceImplTest` 4件（暗号化secret、recovery hash/count、replay/atomic更新、verify）、`PersistentSessionServiceImplTest` 3件（hash保存、期限・上限・失効）、`UserApiControllerTest` 12件（role変更時の全session失効を含む）、`OidcSecurityIntegrationTest` 5件（MFA pending redirectを含む）、T016直接回帰の`OidcLoginUserServiceTest` 4件と`ExternalIdentityProvisioningServiceTest` 4件。直接consumerは`MfaServiceImpl`、`PersistentSessionServiceImpl`、`MfaEnforcementFilter`、`PersistentSessionFilter`、`LoginSuccessHandler`、`UserApiController`、`SecurityConfig`、V63 mapper/entityである。
- excluded suites and reason: 全量Maven、Node/JS全量、Docker/MySQL migration smoke、browser、実Entra/OIDC provider、Conditional Access、実break-glass訓練、B1 action permission、B2 file scannerはT017の直接consumer外であり、T020 L4へ除外。外部tenant/provider gateは認証情報・実環境がないため実施済み・PASSとは扱わない。
- escalation trigger present: **yes**（`SecurityConfig`、session/MFA filter、LoginSuccessHandler、共通認証経路、V63 runtime consumerを変更）。ただしWebMvcTestの不足mockを修正後、L3 direct consumerは全緑で未説明context failureは残らないため、中間L4は追加しない。共有security/schemaの統合確認は次の固定checkpoint T020で実施する。
- exact result: T017/T016 direct regression **34 tests / 0 failures / 0 errors / 0 skipped**。内訳は`TotpUtilTest` 2、`MfaServiceImplTest` 4、`PersistentSessionServiceImplTest` 3、`UserApiControllerTest` 12、`OidcSecurityIntegrationTest` 5、`OidcLoginUserServiceTest` 4、`ExternalIdentityProvisioningServiceTest` 4。`UserApiControllerTest`の初回12件401は、WebMvcTestでmockされた`PersistentSessionService.validateAndTouch`の未スタブが原因であり、`@BeforeEach`でtrueを返す修正後に再実行して解消した。compileは対象実行時に成功した。
- Demo: MockMvc/unit fixtureでbreak-glass MFA pending redirect、TOTP/recovery検証、replay拒否、secret/recovery/session保存境界、role変更時session全失効、session管理経路を再現済み。実Entra login、管理者MFA登録、実端末失効、break-glass復旧訓練は外部gateとして未実施。
- rollback: `MFA_ENABLED=false`またはbreak-glass username設定を空にしてlocal MFA enforcementを無効化し、session管理設定を既存互換値へ戻す。V63以降の履歴、V61/V62、既存migrationは変更しない。実装差分をrevertする場合もV63の既存データを平文化・削除する変換は行わず、バックアップ復元と機能停止を先に行う。
- next L4 checkpoint: **T020 M**。T018/T019のL3完了後、OIDC/MFA/session/action/fileを統合した全量Maven、Node/JS、必要なDocker/MySQL、security/browser/provider回帰を一度実施する。

## T018 TASK CONTRACT / TEST SCOPE DECISION

- task / base: T018 B1 / `f6f0027` base + current working tree。
- requirements/acceptance: R3.1〜R3.4。group割当がある場合はlegacy roleとunionせずgroup actionだけを正とし、未割当時だけlegacy fallbackを使う。自己権限変更を拒否し、他ユーザーの変更・監査・session失効を同一transaction境界へ置く。画面/API/exportの原価fieldを同じactionでmaskする。
- changed contracts: `AuthorizationService`、`ActionPermissionResolver`、`PermissionGroupManagementService`/API、`MenuPermissionFilter`、`UserApiController` role lifecycle、V63 default group/action/user seed、contract/export cost masking、権限拒否監査。
- selected level: **L3**。直接回帰は`AuthorizationServiceImplTest` 4、`ActionPermissionResolverTest` 3、`PermissionGroupManagementServiceImplTest` 2、`PermissionGroupApiControllerTest` 3、`ContractApiControllerTest` 12、`ExportApiControllerTest` 11、`UserApiControllerTest` 12の計**47 tests / 0 failures / 0 errors / 0 skipped**。DB判定例外、prefix近似、自己変更、CSRF、field masking、role変更を含む。
- selected tests and consumers: permission service/API、menu/action filter、user role lifecycle、contract detail/list、contract exportを直接consumerとして選定した。
- excluded suites and reason: Maven全量、Node/JS全量、browser、provider sandbox、全migration smokeはT018の直接consumer外であり、固定checkpoint T020へ集約した。
- escalation trigger present: **yes**（共有security filter、認可service、V63 seed、transaction/session境界）。L3直接回帰がgreenで未知失敗が残らず、固定checkpointが直後のT020だったため中間L4は追加しなかった。
- exact result: **47 tests / 0 failures / 0 errors / 0 skipped**。compile成功。
- next L4 checkpoint: **T020 M**。
- Demo: fixtureでgroup未割当fallback、group割当時の非union、原価mask、自己昇格403、他者変更時session失効・監査を再現済み。実browserの財務担当シナリオはT020外部gateへ繰越。
- rollback: action enforcement公開を止め、V63以前のrole/menu経路へ戻す。適用済みV63は編集せず、permission assignmentはバックアップ復元とする。

## T019 TASK CONTRACT / TEST SCOPE DECISION

- task / base: T019 B2 / `f6f0027` base + current working tree。
- requirements/acceptance: R4.1〜R4.4。extension/MIME/magic byte、quarantine、scanner CLEAN後だけpublished、infected/unavailable保持、再scan、metadata未登録・未知参照download拒否、監査を共通経路にする。
- changed contracts: `FileScanner`/`FileScanResult`、prod `ClamAvFileScanner` INSTREAM adapter、非prod `LocalSignatureFileScanner` fake、`FileStorageServiceImpl`、`FileScopeValidationService`、`FileApiController`再scan、`FileSecurityMetadataMapper`、file download/rejection監査、scanner接続設定。
- selected level: **L3**。直接回帰は`ClamAvFileScannerTest` 3、`FileStorageServiceImplTest` 10、`FileScopeValidationServiceTest` 5、`ActionPermissionResolverTest` 3、`ApiAuditFilterTest` 1の計**22 tests / 0 failures / 0 errors / 0 skipped**。CLEAN/FOUND/接続不能、EICAR、quarantine保持、再scan、未知file、監査を含む。
- selected tests and consumers: scanner adapter、FileStorage、file scope、再scan action、download auditを直接consumerとして選定した。
- excluded suites and reason: Maven全量、Node/JS全量、実ClamAV sandbox、browser、全migration smokeはT019の直接consumer外または外部環境gateであり、固定checkpoint T020またはrelease gateへ集約した。
- escalation trigger present: **yes**（共有FileStorage/file scope、prod profile、V63 metadata consumer）。L3とprod context回帰がgreenで、T020直前のため中間L4は追加しなかった。
- exact result: **22 tests / 0 failures / 0 errors / 0 skipped**。prod/non-prod scanner profileとcompile成功。
- next L4 checkpoint: **T020 M**。
- Demo: EICAR fixtureはINFECTEDとしてquarantineへ残りdownload不可、scanner接続不能はUNAVAILABLEとして公開不可、CLEAN再scanだけpublishedへ移ることを再現済み。
- rollback: upload受付を停止するか`FILE_SCANNER_ENABLED=false`で全uploadをfail-closed拒否する。published/quarantine metadataとV63履歴は削除・改変せず、バックアップ復元を使用する。

## 旧T020 TEST SCOPE DECISION（2026-07-30のproduction差分により失効）

> 以下は修正前treeの履歴証拠であり、現行working treeのPASS根拠には使用しない。現行結果は冒頭の判定を正とする。

- task / commit: T020 M / `f6f0027` base + T019完了時working tree。L4後の差分はcomment・台帳文書のみで、production contract変更なし。
- changed contracts: T014〜T019のOIDC、MFA、persistent session、action permission、file quarantine/ClamAV、監査、V63 schemaを統合したspec全体。
- selected level: **L4**。最終ソースでMaven全量 **161 suites / 971 tests / 0 failures / 0 errors / 1 skipped**。唯一のskipは`QuotationPdfServiceImplTest`のCJK font環境依存。
- selected tests and consumers: Maven全量、全security/schema/service/MVC consumer、Node/JS全静的asset、fresh/legacy/repair/closed-history MySQL migrationを選定した。
- Docker/MySQL: `FlywayMigrationSmokeTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`を実コンテナで実行し全成功。V63の既存user→default group seedとaction seed assertionを含む。
- frontend/static: `src/main/resources/static/js`の**42 files**へ`node --check`を実行し0 failures。`git diff --check` exit 0。
- 未実施: repositoryにOWASP dependency scan相当の設定が無く未実施。実Entra/OIDC/MFA assurance、実browser、2名break-glass復旧訓練は外部環境・認証情報がないため未実施でありPASS扱いしない。
- excluded suites and reason: OWASP依存スキャン、実provider sandbox、desktop/390px browser、break-glass訓練はtool/tenant/認証情報が未提供の外部gateとして除外し、未実施を明記した。
- escalation trigger present: **yes**（M task固定checkpoint）。L4後はcomment・台帳文書だけを変更しており、§7/§8により同一証拠を再実行しない。
- exact result: Maven **971/0/0/1**、Node/JS **42/0**、Docker/MySQL 4 suites成功、`git diff --check` exit 0。
- next L4 checkpoint: 同一treeでは追加なし。production/schema/security差分、merge競合手編集、またはrelease候補化時だけ§5/§8に従いL4/L5を再判定する。
- 判定: L4コード回帰はPASS、T020 MとS03全体は外部gate・独立Review待ち。次の解放条件は依存性スキャン、実環境Demo、独立Review P0=0/P1=0/PASS、中央台帳再同期。
