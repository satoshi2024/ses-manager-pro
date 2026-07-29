# Review Ledger — 企業認証・セキュリティ（S03）

## 現行判定（T018/T019完了、T020外部gate待ち）

- spec状態: `IN PROGRESS`。T014〜T019の実装とL0〜L3、T020のL4全量・Node/JS・Docker migration回帰まで完了した。
- T018状態: **実装・L3検証完了、独立Review待ち**。legacy role→既定group seed、group優先認可、action enforcement、自己権限変更拒否、同一transaction監査、role変更時group/session更新、契約原価field maskingを実装した。
- T019状態: **実装・L3検証完了、独立Review待ち**。magic byte/MIME検証、quarantine→scan→published、ClamAV INSTREAM prod adapter、非prod EICAR fake、scanner unavailable、再scan、未知file/download拒否、file拒否監査を実装した。
- T020 L4結果: Maven全量 **971 tests / 0 failures / 0 errors / 1 skipped**（skipは既存の`QuotationPdfServiceImplTest` CJK font環境依存）、Node/JS syntax **42 files / 0 failures**、`git diff --check` exit 0。Docker上でfresh V1〜V63、legacy V60、repair runbook、V62 closed-historyを全て実行し、skipなしで成功した。
- T020未完了gate: OWASP依存スキャン相当の仕組みはrepository未設定のため未実施。実Entra tenantでのOIDC login/logout・MFA assurance、実browser、login→権限変更→session失効、2名break-glass復旧訓練も外部環境未提供のため未実施。`M. セキュリティ回帰`は完了扱いにしない。
- release判定: B1/B2は完了、S03全体は`FIX/REVIEW`相当。外部gateと独立ReviewのP0=0/P1=0/PASSまで`PASS`へ進めない。

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

## T020 TEST SCOPE DECISION（外部gate待ち）

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
