# Review Ledger — 組織・管理会計

## 運用

各TaskはObjective・自動テスト・Demoを確認してから完了にする。未検証の外部環境はPASSと記録しない。
V59は作成せず、既存DB更新用MigrationはV60を使用する。

## R02 FAIL後の修正記録（2026-07-27）

- 判定: 指摘対応の実装と自動検証は完了。独立Reviewの再判定待ち。PASSへは未更新。
- P1-1/P1-2: snapshot生成を月次締め確定hookへ限定し、確定work record・snapshot dimensionを一括取得。実績はsnapshotの組織/cost center/sales userを優先し、現行所属の後追い参照で過去実績を変えない。
- P1-3/P1-7: 組織更新を異動履歴の再編処理へ変更し、version必須CAS、merge/transfer API、所属行・主所属競合のロックを追加。
- P1-4: contract/engineer/invoice/BP paymentへcost center既定値を追加し、snapshot配賦を契約→要員→請求明細→BP支払の順で解決。組織・法人整合性を検証。
- P1-5: 管理会計にcost center/customer/project/sales userのSQL境界フィルター、待機費集計、drilldown、UTF-8 CSV出力を追加。
- P1-6: manager_user_idを含む組織scopeとDataScopeの積集合をquery boundaryへ適用し、通知の組織scope queryを追加。
- 自動検証: `mvn test -Dcheckstyle.skip=true` が761件成功、Failures 0、Errors 0、Skipped 5。MigrationScriptIntegrityTest 2件、対象再現テスト49件も成功。`git diff --check` 成功。
- 未検証: Docker MySQL smoke、実ブラウザDemo、Node.js syntax smoke（環境skip）。したがって外部Review PASSはまだ記録しない。

## R02再Review 指摘対応1（T008/F1、2026-07-27）

- 対応: 公開済みのV4/V5を変更前へ戻し、organization/cost centerの追加列・FKはV60の条件付きDDLだけで既存DBへ適用する形へ修正。
- H2: 通常テストのEntity列は`schema-organization-accounting-h2.sql`へ分離し、公開MySQL migrationの履歴不変性を壊さない構成に変更。
- 自動テスト: `MigrationScriptIntegrityTest` 2件、`OrganizationApiControllerTest` 5件成功。`FlywayLegacyV60MigrationSmokeTest` 1件はDocker daemon未起動のためskip（CI/Docker環境で旧V58形状→V60適用を実行する）。
- T008状態: この指摘について修正済み・自動検証済み。T009〜T013のP1/P2は未対応のため、Spec全体はFIX・再Review待ちを維持。

## R02第三次Review（2026-07-27）

- 判定: FAIL（P0=0 / P1=7 / P2=4）。`enterprise-identity-security`はNOT READYを維持。
- 有効修正: V4/V5不変化、H2補助Schema、legacy V60 smoke追加、全量762件成功（Failures 0 / Errors 0 / Skipped 6）。
- T008ブロッカーの確認: 発注者より「V60はこれまでどの環境にも未適用」と確認済み。したがって、V4/V5を不変のまま維持し、未適用のV60へ追加DDLを集約する方針を採用する。V59は作成しない。

## T008 F1 — 組織/所属/cost center/予算DDL

- 状態: 修正実装済み（自動検証済み、独立Review再判定待ち）
- 対応requirements: R1.1〜R1.4、R2.1、R4の組織階層・異動履歴・参照中削除禁止に必要な基盤
- 変更範囲: V1統合baseline、V60、H2 replay schema、`engineer-schema-h2.sql`、Entity/Mapper/Service、楽観ロック設定、4言語メッセージ、MySQL smoke assert
- 自動テスト: `OrganizationServiceImplTest` 4件成功（親子/子孫、循環、期間重複、主所属重複、参照中削除、無効化、予算version競合）
- Migration整合性: `MigrationScriptIntegrityTest` 2件成功。V59なし、V60重複なし、空Migrationなし
- MySQL smoke: `FlywayMigrationSmokeTest` はDocker daemon未起動のため1件skip。実MySQL適用は未検証
- Demo: H2上で `legal_entity_id` を将来法人境界として保持し、事業部→課の階層を登録。管理者ユーザーを上長として所属履歴を登録できるサービス/API基盤を確認。参照中組織は削除拒否、無効化は成功
- 未検証: Docker上のMySQL 8空DBからV1→V60の実適用、ブラウザ操作、実法人マスタとのFK接続
- ロールバック: V60未適用環境はMigration適用を止める。適用後は過去Migrationを編集せず、組織レコードを削除せず無効化し、必要ならV60適用DBをバックアップから復元する

## T009 F2 — OrganizationScopeService

- 状態: Review指摘の修正実装済み（自動検証済み、独立Review再判定待ち）
- 対応requirements: R3.1〜R3.3、R4の上長配下閲覧とscope漏洩防止
- 変更範囲: `OrganizationScopeService`、request-scoped実装、組織Mapper query boundary、4言語scopeエラー
- 規則: 管理者は無条件、マネージャーはprimary所属の自組織+子孫に加えてmanager_user_idで直接担当するユーザーだけを個別許可する。直接担当者の所属組織全体へscopeを拡張しない。営業/HR/一般ユーザーは有効所属組織のみ。menu roleは独立認可、既存DataScopeとは積集合で結合し、scopeを拡張しない
- SQL境界: list/count/exportが共通query builderを使い、`IN`条件をDBへ追加。空集合は`id = -1`で0件。画面後フィルターなし
- cache key: tenant（現行独立DBではnull）/user/role/as-of/versionをrequest内cache keyに含めた
- 自動テスト: `OrganizationScopeServiceImplTest` 5件、`WorkRecordServiceImplTest` 29件、`WorkRecordApiControllerTest` 2件、`OrganizationApiControllerTest` 5件、`WorkRecordMapperTest` 2件が成功（Failures 0 / Errors 0）。
- Demo: H2で部門長の配下組織一覧を確認し、manager_user_idで直属管理する外部組織ユーザーは本人だけ許可、同じ外部組織の同僚は拒否となることを確認。勤怠の月次一覧はscope付きMapperのWHERE/EXISTSで取得し、許可集合が空の場合はDB側で0件となることを確認。ID詳細、日次、提出、承認、却下、PDF経路はサービスのquery-boundary scope検査を通過した対象だけを処理する。
- 未検証: 実ブラウザのログイン画面、既存業務テーブルへ組織IDを結合する後続Task、Docker MySQL smoke
- ロールバック: `OrganizationScopeService` を呼び出す後続経路を無効化し、組織レコードは無効化運用。V60以前のDBへ戻す場合はバックアップ復元のみ

## 第三次Review後 T008/T009追補（2026-07-27）

- T008: V60未適用の発注者確認を反映し、V4/V5復元・V60集約・V59不作成を確定。legacy V60 smoke はDocker未起動のためskipのまま。
- T009: 記録レベルの勤怠scopeをSQL境界へ追加し、直接管理ユーザーによる外部組織全体のscope拡張を防止。組織scopeとDataScopeは積集合で適用。
- 未検証: Docker MySQL smoke、実ブラウザDemo、Node.js syntax smoke。Spec全体およびenterprise-identity-securityの状態はPASSへ変更しない。

## 第四次Review指摘対応 — T009/F2 月次一括確定（2026-07-27）

- 指摘: `/api/work-records/confirm` の `confirmMonth` が月内の全組織の勤怠を取得・ロック・更新していた。
- 対応: 月次一括確定は組織横断の不可逆操作のため管理者専用とした。`SecurityConfig` の静的matcherとControllerの`@PreAuthorize`で二重防御し、非管理者画面では確定ボタンも表示しない。CSRF・監査フィルターは既存経路を維持する。
- 回帰テスト: `WorkRecordReopenSecurityTest` 5件（営業/マネージャー拒否、管理者実行）、`WorkRecordServiceImplTest` 29件、`WorkRecordApiControllerTest` 2件、`MobileResponsiveLayoutTest` 20件が成功。Failures 0 / Errors 0。
- Demo: 営業・マネージャーが`POST /api/work-records/confirm?month=2026-07`を実行すると403、管理者は200となることをMockMvcで確認。したがって非管理者が二組織を跨いで月次確定する経路はAPI認可境界で遮断される。
- 判定: このP1（全月一括確定越権）は対応済み。ただし組織改組履歴、通知organization_id、待機費、管理会計多次元/履歴/drilldown、T010 UI、Docker/Browser/JS検証が残るため、R02およびSpec全体はFAILのまま。

## 第四次Review指摘対応 — T008/F1 legacy V60 smoke（2026-07-27）

- 指摘: legacy fixtureが`uk_management_budget`を削除した後、V60が同Indexを固定`DROP`し、Docker環境で確実に失敗する。
- 対応: V60のIndex再構成を、旧Indexが存在しない場合は直接追加、旧Indexが別キーの場合は削除して再作成、正しいIndexが存在する場合はno-opとなる三分岐へ修正。V59は作成しない。
- 自動検証: `MigrationScriptIntegrityTest` 2件成功（Failures 0 / Errors 0）。`FlywayLegacyV60MigrationSmokeTest` 1件はDocker engine未起動のためskip。実MySQL適用結果は未検証であり、T008はCONDITIONAL PASSのまま。
- Demo: SQL静的分岐とlegacy fixtureの削除条件を照合し、索引有無の両形状でV60が同名Indexの無条件DROPを行わないことを確認。Docker起動後に旧V58形状→V60の実適用を再実行する。

## 第五次Review対応進捗 — T009/F2 核心業務scope第一段階（2026-07-27）

- 状態: 修正中。T009は未完了のため`tasks.md`のF2チェックを外した。
- 対応: OrganizationScopeServiceが有効所属・直属ユーザーを基準に、要員/契約/請求書IDをMapperのSQLで導出。Engineer/Contract/Invoiceのlist/options/detail/PDFとExport、月次売上Exportへ組織scopeを追加し、DataScope有効時は同一母集団で交差する。
- 自動検証: `OrganizationScopeServiceImplTest` 6件、`ContractApiControllerTest` 11件、`InvoiceApiControllerTest` 7件、`EngineerApiControllerValidationTest` 4件、`ExportApiControllerTest` 9件が成功。compileと`git diff --check`も成功。
- Demo: H2でマネージャーが自組織に所属する要員だけを取得し、他組織要員は除外されることを確認。契約Mapperのページング引数、請求書ID条件、契約Exportのcount条件、組織外の契約detail/PDFを確認。
- 未対応: Customer/Project等の関連options、invoice aging/aging-export、renewal-calendar、全てのPDF/download経路、主要業務の完全なDataScope交差テスト。したがってP1-1とT009はFAIL継続。

## 第五次Review対応進捗 — T009/F2 aging・renewal scope追補（2026-07-27）

- 対応: renewal-calendar候補取得へ契約の組織scope∩DataScopeをSQL条件として渡した。aging/aging-export/aging-detailは、残高取得SQLへ請求書ID・顧客ID条件を渡し、取得後のscope filterを廃止した。空集合はSQLを実行せず0件とする。
- 自動検証: `RenewalCalendarServiceImplTest` 10件、`InvoiceServiceImplTest` 38件、`InvoiceApiControllerTest` 7件が成功（Failures 0 / Errors 0）。
- 未対応: Customer/Project等の関連options、Engineer/Contract/Invoiceの全PDF・download・count経路、通知publish時のorganization_id設定、管理会計の多次元/履歴/drilldown、実ブラウザ、Docker MySQL smoke、Node.js JS syntax smoke。T009はFAIL継続。

## 第五次Review対応進捗 — T009/F2 顧客・案件・帳票scope追補（2026-07-27）

- 対応: Customer/Projectの一覧・options・detail・更新・削除へ組織scope∩DataScopeを追加。顧客/案件の組織IDは、契約→要員アカウント→有効所属をSQLで辿った契約IDから導出し、画面後フィルターには依存しない。Engineerのskill-sheet PDF/Excel、Proposalのskill-sheet export、ContractDocument PDF downloadにも同じ交差境界を追加。
- 自動検証: `CustomerApiControllerTest` 3件、`ProjectApiControllerTest` 4件、`SkillSheetApiControllerTest` 2件、`ProposalApiControllerTest` 4件、`OrganizationScopeServiceImplTest` 6件が成功（Failures 0 / Errors 0）。
- 未対応: Engineer/Contract/Invoiceの全関連sub-resourceとcount/download経路、通知publish時のorganization_id設定、BP支払・待機費のscope、管理会計の多次元/履歴/drilldown、組織改組/異動境界、実ブラウザ、Docker MySQL smoke、Node.js JS syntax smoke。T009はFAIL継続。

## 現行判定訂正（2026-07-27）

- 旧記録にあるT009完了、T010/T011/T012/T013修正実装済み、Spec全体の`mvn test`成功という記載は、独立Reviewで未対応P1が再確認される前の中間記録であり、現行判定として使用しない。
- 現行は`tasks.md`のF1/F2/A1/B1/B2/Mをすべて未チェックとし、中央台帳は`FIX`、T009修正中、`enterprise-identity-security`は`NOT READY`を維持する。
- V60は未適用であり、V59は作成しない。Docker/Browser/Node.js未検証をPASS根拠にしない。

## T009〜T013

- T010 A1: 修正実装済み（自動検証済み、独立Review再判定待ち）
  - 対応requirements: R1.1〜R1.4、R2.1、R3.1〜R3.3の管理画面/API境界
  - 変更範囲: `OrganizationApiController`、page controller、DTO、Thymeleaf画面、module JS、V60 menu seed、sidebar、4言語i18n
  - API: 組織一覧/詳細/CRUD、状態切替、所属一覧/登録、cost center一覧/CRUD。全一覧・所属・cost centerはOrganizationScopeServiceのSQL wrapper適用後に取得し、画面後フィルターを行わない
  - 横断規約: Spring Security CSRFを更新系APIへ適用。`ApiAuditFilter`が更新APIを監査。versionをDTOから受けてMyBatis-Plus楽観ロックを利用。menuはV60の`m_menu`/`t_role_menu`で管理
  - 自動テスト: `OrganizationApiControllerTest` 5件成功（validation、CSRF有/無、scope境界、一覧query boundary）
  - Demo: MockMvcでCSRF付き登録がcode=200、CSRFなし更新系が403、マネージャーの一覧がscope serviceへ基準日付きで委譲、scope外詳細が拒否されることを確認
  - 未検証: 実ブラウザの異動前後フォーム操作、実MySQLのV60 menu seed（Docker daemon未起動）、後続Taskの契約/勤怠画面との組織表示
  - ロールバック: V60適用前はAPI/画面をリリース対象から外す。適用後は組織を削除せず無効化し、必要ならバックアップ復元。V59は作成しない
- T009 F2: 完了（前項記録済み）
- T011 B1: 修正実装済み（自動検証済み、独立Review再判定待ち）
  - 対応requirements: R2.2、R2.4、R4の異動後過去実績不変
  - 変更範囲: `MonthlyAccountingSnapshotService`、月次締めhook、予算API/CSV、scope付きsnapshot/予算一覧、V60管理会計menu、4言語i18n
  - snapshot: 締め対象月の確定work recordを締め確定時点の基準日で主所属へ帰属し、cost center/sales user/売上/原価を保存。`work_month/source_type/source_id`一意キーと既存行skipで再締め・異動後の上書きを禁止。並行実行は一意制約で一度だけ確定。公開手動snapshot APIは設けない
  - 予算: 組織scopeをSQL query boundaryへ適用し、単件upsertとUTF-8 CSV取込を提供。既存versionの不一致は409相当の業務例外、CSVの数式/負数など不正値は拒否し、CSV取込はトランザクションで全体ロールバック
  - 月次締め: `MonthlyClosingServiceImpl.confirmClosing`の締め確定前にsnapshot hookを実行。既存のreopen規約・CSRF・監査フィルタを維持
  - 自動テスト: `MonthlyAccountingSnapshotServiceImplTest` 2件成功、`MonthlyClosingServiceImplTest` 12件成功（snapshot hook、reopen含む）。F1の予算version競合テストも成功
  - Demo: 2026-06の確定work recordを1月初の主所属へsnapshotし、同じ月を再実行して既存snapshotが0件追加・所属再参照なしになることをMockで確認。締め確認時にhookが呼ばれること、再開（reopen）が既存規約どおり動くことを確認
  - 未検証: 実DBでのCSVファイル取込とロールバック、Docker MySQL V60適用、実際のwork_record/engineer_account_linkデータでのブラウザ操作、cost center既定配賦の業務データ網羅
  - ロールバック: V60未適用環境では管理会計API/メニューを公開しない。適用後はsnapshotを削除・更新せず、誤帰属は明示訂正Taskと監査理由で扱い、必要ならバックアップ復元
- T012 B2: 修正実装済み（自動検証済み、独立Review再判定待ち）
  - 対応requirements: R2.3、R3.1〜R3.3、R4の全社合計/組織別合計一致
  - 変更範囲: `ManagementAccountingService`/summary DTO、契約の組織scope SQL query、管理会計API/export、管理会計画面/JS、V60 menu、4言語i18n
  - 金額口径: 既存`MonthlyRevenueCalcService`へ契約単位の金額解決を委譲。確定work recordはsnapshotの組織を優先し、未snapshotの見込みはwork month時点の有効primary所属でSQL取得した契約組織を使用。予算は同じ組織scope wrapperで取得し、売上/粗利の予算差を算出
  - scope: 非管理者は契約の所属組織をJOIN条件で`IN`制限し、snapshot/予算もSQL `IN`。管理者のみ全件。export endpointも同じsummary serviceを再利用し、画面後filterなし
  - 自動テスト: `ManagementAccountingServiceImplTest` 1件成功（売上/原価/粗利、予算差、SQL scope引数、組織名）。F2 scope testとA1 API CSRF testも継続成功
  - Demo: 2026-06の組織100で売上120・原価70・粗利50、売上予算110・粗利予算40を入力したsummaryが、売上差10・粗利差10を返し、同じ行の組織名とscopeを保持することをMockで確認
  - 未検証: 実ブラウザのChart/CSV download、実MySQL JOIN/インデックス計画、Docker smoke
  - ロールバック: `/management-accounting` menu/APIを無効化し、予算/snapshotは削除せず読み取り停止。V60以前へ戻す場合はバックアップ復元
- T013 M: 修正実装済み（自動検証済み、独立Review再判定待ち）
  - 対象: T008〜T012の全自動回帰、Migration整合性、i18n、既存mobile layout
  - 自動テスト: `mvn test` 成功。761 tests、Failures 0、Errors 0、Skipped 5。`MessageBundleConsistencyTest`、`MigrationScriptIntegrityTest`、`MobileResponsiveLayoutTest`を含む
  - JS: `JsSyntaxCheckTest`はNode.js未導入のため1件skip
  - MySQL: `FlywayMigrationSmokeTest`はDocker daemon未起動（Docker clientは存在するがLinux engine pipeへ接続不可）のため1件skip。V1→V60実MySQL適用は未検証
  - Demo: サービス/APIのMock Demo（組織scope、snapshot不変、予実差、CSRF）と、H2統合回帰（mobile layoutを含む）を確認
  - 未検証: 実ブラウザでのログインから組織作成→所属異動→契約→締め→部門損益の一気通貫、Docker MySQL smoke、Node JS syntax smoke
  - ロールバック: 本Specで追加したAPI/menu/pageをリリース単位で無効化し、V60適用済みDBの組織/予算/snapshotを削除せずバックアップ復元。V59は作成しない。既存機能の変更は本Spec差分だけを選択的にrevertする

## 第六次Review対応 — T009/F2 回帰・principal・月次境界（2026-07-27）

- 最新ReviewのP1-10回帰を追補。標準の`UserDetails` principalでもロールを解決し、数値usernameだけをlocal user idとして扱うよう`SecurityUtils`を共通化した。外部subjectは未解決のままfail-closedとし、組織所属を推測しない。
- `ExportApiControllerTest`のscope交差stubを実装し、`SalesActivityApiControllerTest`の管理者経路を現行認可境界に合わせた。`ApiCoverageIntegrationTest`の月次売上exportで空の`IN`条件を生成しない経路、`InvoiceMapper`の組織導出をinvoice.customer_id全体結合からinvoice item→work record→contractの実参照へ修正した。年次exportは各work monthのas-of scopeをquery boundaryへ渡す。
- Autocompleteのengineer/customer/project検索もOrganizationScope∩DataScopeをSQL条件へ渡した。画面後フィルターには依存しない。
- 既存DBでのV60適用直後のデータ消失を避けるため、V60に`LEGACY`移行組織と既存非管理者の初期所属backfillを追加し、`organization.scope.enabled`を本番既定OFF、test既定ONの制御ゲートとして追加した。標準/OIDC principalは`username`から`sys_user`をSQL解決する経路まで追加したが、OIDC subjectの正式な外部IDマッピングと実DB backfill確認は未完了のため、P1-2、T009、R02はFAIL継続とする。
- 自動検証: `SecurityUtilsTest` 5件、`ExportApiControllerTest` 10件、`SalesActivityApiControllerTest` 7件、`OrganizationScopeServiceImplTest` 7件、`ApiCoverageIntegrationTest` 8件がFailures 0 / Errors 0。compileと`git diff --check`も成功。全量`mvn test`、Docker MySQL、Browser、Node.js JS smokeはこの追補では未完了。
- 未対応P1: 全業務出口の完全なscope交差（通知publish、BP支払、待機費、AI/skill/career/followup/sales/project-skill等）、改組/異動/退職の履歴境界、cost center UI/権限、管理会計の多次元・履歴・drilldown、組織mutationのロール境界。これらを理由にT010〜T013を開始・完了扱いにしない。

## 第六次Review対応 — 通知宛先の組織固定（2026-07-27）

- `publishToUser`で宛先ユーザーの当日有効な主所属をSQLから解決し、`t_notification.organization_id`へ保存するよう修正した。通知一覧・未読件数・既読処理が既存のOrganizationScope条件で同じ組織IDを参照するため、明示的な宛先通知がNULL（全組織）へ拡張されない。
- `NotificationServiceImplTest` 9件、`NotificationPagingTest` 3件、既存`NotificationGenerateServiceTest` 1件を確認し、Failures 0 / Errors 0。NULLを使う全体通知（宛先なし）の組織別fan-outは未実装であり、通知P1全体を完了扱いにしない。

## 第六次Review対応 — 全量回帰記録（2026-07-27）

- Surefireに全131テストクラスの結果が生成され、合計777件、Failures 0、Errors 0、Skipped 6だった。失敗・エラーの報告はない。
- ただし同一の`mvn test`プロセスはツール上限304秒で終了したため、Mavenコマンドの終了コードによるPASSではない。実Docker MySQLの`FlywayMigrationSmokeTest`/`FlywayLegacyV60MigrationSmokeTest`、`FlywayRepairRunbookTest`、並行更新テスト、Quotation PDF、Node.js JS syntaxの6件はskipのままである。
- この結果は回帰修正の根拠にはするが、T009完了、T008の実MySQL PASS、T013完了、またはS03開始条件の充足とは扱わない。

## 第七次Review対応 — T008/F1 legacy V60 backfill順序（2026-07-27）

- 指摘: legacy fixtureでは`t_user_organization.version`が存在しないが、V60が同列を使うbackfillを先に実行するため、実MySQLで`Unknown column 'version'`となる。
- 対応: legacy互換の`ADD COLUMN version`をbackfill直前へ移動し、列追加後にのみ`INSERT INTO t_user_organization`を実行する順序へ修正した。V59は作成していない。
- 回帰防止: `MigrationScriptIntegrityTest`へV60内の列追加位置がbackfillより前であることを検証するテストを追加。3件成功、Failures 0 / Errors 0。`FlywayLegacyV60MigrationSmokeTest`は1件skip（Docker daemonなし）。
- 判定: P1-1のコード上の実行順序は修正済み。ただしMySQL 8でのlegacy V58相当→V60実適用は未実証のため、T008はFAIL、全Task未チェック、中央台帳`FIX`、S03`NOT READY`を維持する。

## 第八次Review指摘対応 — P0/P1全件（2026-07-27）

R02第八次Review（Base `601177a` / Head `030a016`、判定 FAIL、P0=1 / P1=13 / P2=11）の指摘へ対応した。

### P0

- **待機原価のscope漏れ**: `EngineerMapper.selectAccountingWaitCost` は asOf のみを受け取り、
  組織・原価部門・法人のいずれの条件も持たないまま全社集計していた。結果は
  `ManagementAccountingServiceImpl` で無検査に行へ合流していたため、部門責任者の
  `/api/management-accounting/summary` と `/export` に他組織の待機費と組織IDが載っていた。
  → `fullAccess/allowedIds/legalEntityId/organizationId/costCenterId` をSQL条件として追加し、
  空集合は `1 = 0` で0件にする。取得後フィルターは行わない。
- 併せて Bench判定を `t_engineer.status`（現在値）から**対象月の契約有無**へ変更した
  （`UtilizationCalcService` と同じ契約ベース口径）。現在値のままだと過去月の待機費が後から変わり、R2.2に反する。

### P1

- **行レベル予算差**: 実績キーが5軸(組織/原価部門/顧客/案件/営業)、予算キーが2軸だったため両者が永久に
  噛み合わず、画面の「予算差」列が常に誤りだった（合計だけ辻褄が合うのでKPIカードでは気付けない）。
  → 予実行を**組織×原価部門**に固定し、顧客/案件/営業の分解は予算列を持たない `details` へ分離。
- **帰属のaccount link依存**: 帰属解決が `t_engineer_account_link` 必須で、要員セルフサービスを
  使わない大半の要員が「未配賦」かつ非管理者から不可視だった。
  → `t_engineer.organization_id` を追加し、解決順を「要員の所属組織 → 連携ユーザーの主所属」に統一
  （scope派生SQL・管理会計SQL・月次snapshotの全経路）。V60で既存要員をLEGACYへbackfillする。
- **組織更新でIDが変わる**: `PUT /api/organizations/{id}` が `reorganize`（新IDで行を作り旧行を無効化）
  だったため、名称変更だけで所属・原価部門・予算・snapshotが旧IDに取り残されていた。
  → 同一IDのversion CAS更新 `updateOrganization` に変更し、`reorganize` は廃止。
- **統合が参照を移さない**: `merged_into` は書くだけで読み手が無く、子組織・所属・原価部門も残置。
  → 子組織・在籍所属・要員の所属組織・原価部門・当月以降の予算を統合先へ付け替える。
  過去月の予算と月次snapshotは実績の事実として動かさない。
- **異動の楽観ロック不発**: `transferUser` が `expectedVersion` を未使用、Controllerも `null` を渡していた。
  → 異動元の有効な主所属の版番号を検査。`updateUserOrganization` にもversionを必須化。
- **退職境界**: ユーザー無効化・削除が所属を閉じず、退職者が組織scope・部門損益・上長に残り続けた。
  解除APIも存在しなかった。
  → `DELETE /api/organizations/{userId}/assignments/{id}`（履歴として終了日を入れる）と
  `closeAssignmentsForUser` を追加し、`UserApiController` の無効化・削除から呼ぶ。上長参照も外す。
  在籍者が残る組織の無効化はエラーにする。
- **scope結合規則（R3.1違反）**: 営業/HRにも組織scopeを積集合していたため、営業部の営業が
  技術部所属要員の契約を担当する通常運用で自分の担当データが0件になっていた。
  → 業務データへの組織scopeは**部門責任者のみ**。規則は `design.md` の表を唯一の正とする。
- **通知**: 宛先指定通知に発行時点の組織を重ねており、本人が異動すると自分宛の通知が消え既読にもできなかった。
  → 組織条件は `recipient_user_id IS NULL` の全体通知にのみ適用。
- **dashboard未適用（R3.3）**: `DashboardServiceImpl` が DataScope のみだった。
  → 契約・要員の母集団を組織scope∩DataScopeへ統一。
- **DB一意制約なし**: 組織コード・主所属・所属重複がアプリ検査のみだった。
  → V1/V60へ生成列 + `uk_organization_code` / `uk_user_org_active_primary` / `uk_user_org_period` を追加。
- **予算CSV**: 行数上限なし → shared-standards §3 に合わせ200行上限。
- **rollout gate**: `organization.scope.enabled` はV60が既存ユーザー・既存要員をbackfillするため既定 `true`。
  `ORGANIZATION_SCOPE_ENABLED=false` で段階導入できる。

### P2

生成列のH2/MySQL差異をコメントで明示、H2 replayへV60のmenu seedを追加（メニュー権限が自動テストで
評価されるようになる）、組織/管理会計画面を `form#searchForm` 構造にしてモバイル折りたたみを共通化、
管理会計フィルターをID直打ちから選択式へ、既定月を前月へ、組織種別を R1.1 の 事業部/部/課/チーム へ、
ルート組織作成をscope制限ロールに禁止、スナップショットの死コード整理。

### 自動検証

- `mvn clean test`: **792 tests、Failures 0、Errors 0、Skipped 4、BUILD SUCCESS**（Maven終了コードで確認）。
- 追加・改訂したテスト: `ManagementAccountingServiceImplTest` 3件（待機原価のscope引数、顧客付き実績の
  行レベル予算差、全社=組織別合計）、`OrganizationServiceImplTest` 7件（同一ID更新CAS、統合の参照付替、
  退職時の所属クローズと上長解除、在籍者ありの無効化拒否）、`NotificationOrganizationScopeTest` 2件
  （異動後も宛先指定通知が見える／全体通知は組織で絞られる）、`OrganizationScopeServiceImplTest` 7件
  （R3.1の三分岐）、`OrganizationApiControllerTest` 6件（ルート組織作成の拒否を追加）、
  `MonthlyAccountingSnapshotServiceImplTest` 3件（連携なしでも要員の所属組織で帰属）、
  `MigrationScriptIntegrityTest` 6件（V60の列追加順・生成列→UNIQUE順・V1との同期）。
- `JsSyntaxCheckTest` は本環境にNode.js v22があるため**実行され成功**（前回までskip）。

### 未検証（本番リリース前の必須条件）

- **Docker MySQL smoke**: `FlywayMigrationSmokeTest` / `FlywayLegacyV60MigrationSmokeTest` は
  Dockerデーモン未起動のためskipのまま（`docker ps` が socket 不在で失敗）。V1→V60および
  旧V58形状→V60の実適用は未実証。両テストへ新しい列・一意制約・LEGACY backfillのassertは追加済みで、
  Docker環境で実行すれば検証できる状態にしてある。
- **実ブラウザDemo**: desktop/390px の一気通貫は未実施。
- Skipされた4件: 上記smoke 2件、`FlywayRepairRunbookTest`、`ConcurrentUpdateTest`（いずれもDocker依存）。

### ロールバック

V60未適用環境ではMigration適用を止める。適用後は組織・所属・snapshotを削除せず、
`ORGANIZATION_SCOPE_ENABLED=false` でscopeだけ無効化し、必要ならバックアップから復元する。V59は作成しない。
