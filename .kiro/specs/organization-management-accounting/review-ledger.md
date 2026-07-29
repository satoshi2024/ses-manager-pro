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

## 第九次Review指摘対応 — enterprise開始条件前のP0/P1追補（2026-07-27）

- 判定: `FIX`。P0/P1の実装・回帰テストを追補中であり、独立Review、Docker MySQL smoke、実ブラウザDemoは未実施。`enterprise-identity-security`は`NOT READY`を維持する。
- 対象修正: Dashboardキャッシュキーの組織/DataScope分離、forecast pipelineのSQL境界、確定勤怠への組織・原価部門snapshot、組織statusの保護付きversion CAS、法人境界（親子・統合・法人変更）、予算の組織/原価部門の法人・状態・有効期間整合性、管理会計法人予算境界、CSV 15列、上長の有効性・role・scope検査、ユーザー無効化/削除と所属クローズの同一トランザクション、原価部門versionおよび全業務参照元の削除保護。
- 回帰テスト: `OrganizationServiceImplTest` 11件、`OrganizationServiceManagerScopeTest` 1件、`ManagementAccountingServiceImplTest` 4件、`ManagementAccountingApiControllerTest` 3件、`OrganizationApiControllerTest` 10件、`UserApiControllerTest` 11件、`CostCenterServiceImplTest` 4件、`ContractMapperProjectionTest` 1件を確認済み。既存のDashboard/snapshot関連定向テストも前段で成功している。
- Schema: V1の`expected_unit_price`コメントを円単位へ修正。V5/V60、`engineer-schema-h2.sql`、`schema-organization-accounting-h2.sql`は勤怠確定時の組織・原価部門列を同期した状態を維持する。
- 未検証: `mvn test`全量の最終終了コード、Docker MySQL 8によるV1→V60およびlegacy V60 smoke、Node/browser実Demo、独立Review。従って本記録をPASS根拠にしない。
- Base/Head: Base `601177a14689b6fc12cf79482224e0467a7e00ba`、Head `62a1f8a25b2a0638398cbb477bb10a58dba5afae`。修正は未コミットの作業ツリー差分に含まれる。

## 第十次Review指摘対応 — P0/P1修正後の現行記録（2026-07-27）

- 現行判定: `FIX`。P0/P1のコード修正と回帰テストは完了したが、独立再Review、Docker MySQL smoke、desktop/390px実ブラウザDemoが未完了のため、`PASS`へ変更しない。`enterprise-identity-security`は`NOT READY`を維持する。
- P0-1: 公開済みV5を復元し、SHA-256内容ロックと会計帰属列不在を`MigrationScriptIntegrityTest`で固定。organization/cost center/frozen列はV60の独立条件DDLのみで追加。
- P0-2/P0-3: V60に`t_bp_payment.cost_center_id`、`t_work_record.organization_id`、`cost_center_id`、`accounting_dimension_frozen`を列ごとの存在判定で追加し、BP外部キーより前に列を作成。H2 schemaも同期。
- P0-4: 非数値principalのDashboard cache keyを安定username、解決不能principalを再利用不能UUIDへ変更し、異なるprincipalの隔離テストを追加。
- P1-1〜P1-3: `approve()`を含む勤怠確定経路で組織・原価部門・凍結フラグを同一更新へ保存。対象月の有効所属を現在要員マスタより優先し、明示的に凍結されたNULLは後日fallbackしない。遅延承認、NULL凍結、Bench待機snapshotの回帰テストを追加。
- P1-4: Cost Center削除参照に`t_work_record`を追加し、WorkRecord参照拒否を維持。
- P1-5/P1-6: 組織統合は旧所属を統合日前日で閉じ、統合日付の後続所属を作成。既存統合先との同日重複を避け、status/assignmentの行ロック、同日異動拒否、参照中期間変更拒否を回帰テストで固定。
- P1-7/P1-8: scope外snapshotを見えない契約はforecastへ再出現させず、Bench待機原価を`bench-engineer`月次snapshotから集計。snapshotが存在する月は現在要員SQLへfallbackしない。
- P2/テスト追補: 管理会計契約Mapperの両scope SQL、CSVのtab/CR制御文字、V5/V60 DDL順序・列独立性をテスト。
- 自動検証: 定向79 tests成功。全量`mvn test`: **830 tests / 0 failures / 0 errors / 6 skipped / BUILD SUCCESS**。`git diff --check`はexit 0（LF→CRLF warningのみ）。
- 未検証: `docker ps`はDocker Linux engine pipe不在で失敗、`node --version`はコマンド未導入で失敗。従ってMySQL空庫/legacy smoke、Node JS syntax smoke、desktop/390px実ブラウザ一気通貫Demoは未実施。独立semantic reviewもこれから実施する。
- Git: 修正は未コミット作業ツリーにあり、commitは作成していない。

## 第十一次Review前追補 — P0/P1回帰修正（2026-07-27）

- 現行判定: `FIX`。今回の独立再Reviewは未実施のため、P0/P1およびSpec全体をPASSへ変更しない。`enterprise-identity-security`は`NOT READY`を維持する。
- 修正: `schema-organization-accounting-h2.sql`へ`t_work_record.accounting_dimension_frozen`と`t_bp_payment.cost_center_id`を追加。`ManagementAccountingServiceImpl`は対象月に`bench-engineer` snapshotが1件でも存在すれば、scope外で可視行が0件でも現在要員SQLへfallbackしない。通常の待機原価SQLは対象月の有効な主所属を要員所属より優先する。CSV exportは埋め込みCRを含むセルを引用し、Cost CenterのWorkRecord参照削除を回帰テストで固定した。
- 回帰テスト: scope外待機snapshotのfallback禁止、scope外確定snapshot契約のforecast再出現禁止、WorkRecord参照中のCost Center削除拒否、CSV埋め込みCRを追加。定向24件成功。全量`mvn test`は**833 tests、Failures 0、Errors 0、Skipped 6、BUILD SUCCESS**。
- 未検証: `docker ps`は`dockerDesktopLinuxEngine`のnamed pipe不在で失敗し、MySQL空庫V1→V60/legacy V58→V60 smokeは未実行。`node --version`はコマンド未導入で失敗し、Node JS syntax smokeは未実行。desktop/390px実ブラウザDemoも未実行。独立semantic reviewもこの追補時点では未実施。
- Git: commitは作成していない。修正は未コミット作業ツリー差分にある。

## 第十一次独立再Review（2026-07-27）

- **全体判定: FAIL**。独立Reviewは完了したが、Docker MySQL smoke、Node.js syntax smoke、desktop/390px実ブラウザDemoが未検証のためPASSへ進めない。`enterprise-identity-security`は`NOT READY`・開始不可を維持する。
- P0-1 V5不変性: **PASS**。V5 checksum固定、V60集約、ただし実MySQL適用は未検証。
- P0-2/P0-3 V60/H2列同期・DDL順序: **PASS**。`accounting_dimension_frozen`、WorkRecord/BP paymentのcost center列とFK前置を静的テストで確認、実MySQLは未検証。
- P0-4 Dashboard cache key: **PASS**。非数値principalの実環境検証は未実施。
- P1-1〜P1-2 確定WorkRecord/snapshot凍結とscope外実績のforecast再出現防止: **PASS**。Mockito回帰は成功、実Mapper/DB結合は未検証。
- P1-3 Bench snapshot fallback禁止: **PASS**。対象月にsnapshotが存在すればscope外で可視0件でもEngineer SQLへfallbackしない回帰を確認。実MySQLは未検証。
- P1-4 対象月有効所属の優先: **PASS**。両待機SQLが`COALESCE(uo.organization_id, e.organization_id)`を使用。期間境界を実DBでは未検証。
- P1-5 Cost CenterのWorkRecord参照削除保護: **PASS**。専用回帰テスト成功。任意注入Mapper欠落時のfail-openは残存リスクとして記録する。
- P1-6 CSV embedded CR quoting: **PASS**。CRの引用と先頭制御文字保護、15列テストを確認。CSVパーサによる実レスポンス再読は未実施。
- P1-7/P1-8 回帰・外部検証: 自動検証は**PASS**（全量`mvn test`: **833 tests / 0 failures / 0 errors / 6 skipped / BUILD SUCCESS**、`git diff --check` exit 0）。Dockerは`//./pipe/dockerDesktopLinuxEngine`不在、Nodeはコマンド未導入、実ブラウザDemoは未実施。
- 結論: 今回の独立Reviewで直ちに必要と断定されたruntimeコード修正はないが、外部ゲート未達のためSpecは`FIX`、T013は未完了。Docker MySQL空庫/legacy smoke、Node syntax、desktop/390px Demoを完了してから再判定する。

## 第十二次独立再Review（2026-07-27、T008〜T013）

Base `601177a14689b6fc12cf79482224e0467a7e00ba` / Head `1c352dbdb888ad3aa018ee5de06a550caadb6cc4`。
**本Reviewで初めてDocker MySQL 8上の全smokeとNode.js JS syntaxが実行され、11次までskipに隠れていた
P0/P1が4件顕在化した。** いずれも修正・回帰テスト済み。

### 外部ゲートの状況が変わった点

- Docker: Linux engine 29.3.1 を起動。`mysql:8.0` はdocker.io本体のCDN(`production.cloudfront.docker.com`)が
  egress policyで403のため、`mirror.gcr.io/library/mysql:8.0` から取得して同タグへ付け替えた。
- Node.js v22.22.2 が存在するため `JsSyntaxCheckTest` も実行された。
- 結果: **`mvn clean test` 836 tests / Failures 0 / Errors 0 / Skipped 0 / BUILD SUCCESS**。
  Skipped 0 は本Specで初めて。従来「skipのまま」だった smoke 2件・repair runbook・並行更新も全て実行済み。

### P0

- **P0-1 組織scopeがリクエスト外スレッドで必ず例外になる（T009/F2の回帰）**
  `OrganizationScopeServiceImpl` / `DataScopeServiceImpl` が `@RequestScope` のまま
  `WorkRecordServiceImpl#assertAllowed`（本branchで新規追加）から呼ばれるため、HTTPリクエスト外
  （バッチ・`@Async`・ワーカースレッド）では `ScopeNotActiveException: No thread-bound request found`
  で業務処理そのものが落ちる。実MySQLの `ConcurrentUpdateTest` が
  「1件成功するはず → 0件成功」で再現（承認・差戻しの両方が失敗）。
  → 両サービスをシングトン化し、キャッシュをリクエスト属性へ退避。リクエスト外はキャッシュせず都度算出。
  スレッドローカルにはしない（ワーカースレッド使い回しで別ユーザーへ可視ID集合が漏れるため）。
  シングルトン化で表面化した `OrganizationScopeServiceImpl ⇄ OrganizationServiceImpl` の循環参照は
  `ObjectProvider` の遅延解決で解消。回帰テスト
  `OrganizationScopeServiceImplTest#リクエスト外のスレッドでもscope解決が例外にならない` を追加。
- **P0-2 V60の生成列がSTOREDで、既存DBへのALTERが原理的に成功しない**
  MySQL 8は「STORED生成列の元になっている列の外部キーに ON UPDATE CASCADE を使えない」制約を
  **CREATE TABLE時は素通し・ALTER時のみ強制** する。V60の3生成列は全てFK列由来のため、
  空DB(V1のCREATE)は通るのに、既存DBの `ADD COLUMN ... STORED` が
  `ERROR 1215 Cannot add foreign key constraint` でMigration全体を中断させる。
  最小再現で確定（`t_management_budget.cost_center_key` と `t_user_organization.active_primary_user_id` の2箇所）。
  さらにV1統合baselineは元々VIRTUAL(`AS (...)`)で定義されており、**V1とV60で列の種別が分岐していた**。
  → V60の`STORED`を全廃してV1と同じVIRTUALへ統一。VIRTUALでもUNIQUE索引は張れ、
  主所属一意制約が実際に効くことを実DBで確認。Docker不要の静的検査
  `MigrationScriptIntegrityTest#V60とV1の生成列はVIRTUALで揃っていること` を追加。

### P1

- **P1-1 V5不変チェックがLinux/CIでは必ず失敗する**
  `MigrationScriptIntegrityTest` が固定していたSHA-256 `7741f71…` はV5の**CRLF**版のハッシュ。
  リポジトリに`.gitattributes`は無く、LFでcheckoutするLinux/CIでは実ファイルが`f6d1194…`となり、
  V5を一切変更していなくても落ちる。第10〜11次の「833 tests BUILD SUCCESS」はCRLFのWindows作業機
  でのみ成立していた。
  → 改行を正規化してからハッシュし、LF基準の`f6d1194…`を固定。内容変更の検知能力は維持。
- **P1-2 管理会計exportで負の予算差が文字列化する**
  `ManagementAccountingApiController` が `CsvUtils` を使わず独自のCSV無害化を持ち、
  先頭`-`を一律で`'`前置していた。予算差(`revenueVariance`/`grossProfitVariance`)と粗利は
  **予算未達の行で必ず負数**になるため、Excelで合計・並べ替え・グラフが効かない値として出力されていた。
  → 共通`CsvUtils`へ集約し、`sanitizeForSpreadsheet`を「数値として解釈できる値は無害化しない」へ変更。
  数式(`=`/`@`/`+HYPERLINK(...)`)の無害化は維持。実レスポンスをCSVとして読み直す回帰テストを追加。
- **P1-3 退職・無効化時の所属クローズがCAS失敗を握り潰す**
  `OrganizationServiceImpl#closeAssignmentsForUser` が `updateById` の戻り値を加算するだけで、
  版番号競合(0件更新)を無視していた。ユーザー無効化・削除は「成功」で返るのに所属が開いたまま残り、
  退職者が組織scope・部門損益に居座る。
  → CAS失敗で`409`を投げ、無効化・削除トランザクションごとロールバックさせる。

### P2

- **P2-1 Testcontainersの版が混在し、smokeが「Dockerなし」で黙ってskipされる**
  `mysql`/`junit-jupiter` だけ1.20.6を直指定し、core は spring-boot BOM の1.19.8のままだった
  （pomのコメントは「BOM管理」と実態と逆の説明）。加えて docker-java は版によらず
  既定APIバージョン1.32で接続するため、MinAPIVersion 1.40 の Docker Engine 29系では
  Dockerが動いていてもTestcontainersが「Docker is not available」と判断しsmokeがskipされる。
  → `testcontainers-bom` 1.20.6 で一括管理し、surefireで `api.version=1.40`（Docker 19.03相当の下限）
  を明示。環境変数の小細工なしに `mvn test` だけでsmokeが実行されることを確認。
- **P2-2** `ManagementAccountingApiController` のCSV行数上限コメントが `\uXXXX` エスケープのまま
  （日本語コメント規約から逸脱）→ 可読な日本語へ修正。
- **P2-3（未修正・仕様確認事項）** 管理会計画面のフィルターは 組織／原価部門／顧客 のみで、
  APIが受け付ける `projectId` / `salesUserId` / `legalEntityId` を送っていない。
  内訳表は案件・営業別の行を表示するが、原価部門・顧客・案件・営業は**IDの数値をそのまま表示**する。
  R2.3の「表示」は満たすが、絞り込みと可読性は未完。
- **P2-4（未修正・設計どおり）** 予実行キーは 組織×原価部門 固定のため、実績に原価部門が無く
  予算に原価部門がある場合は行が合流せず、行単位の予算差が両建てで出る（合計は一致）。
- **P2-5（未修正）** `OrganizationServiceImpl#descendantIds` はscope解決のたびに有効組織を全件ロードする。
  現状の組織数では問題にならないが、階層を辿るクエリへ寄せる余地がある。

### 判定

- T008 F1: **PASS**（空庫V1→V60、旧V58形状→V60の両方を実MySQL 8で適用成功）
- T009 F2: **PASS**（P0-1修正・回帰追加。リクエスト外経路を含めscope解決が成立）
- T010 A1: **PASS**（scope・CSRF・version必須・監査の境界を確認）
- T011 B1: **PASS**（一意キー＋既存skipで再締め・異動後の上書きなし）
- T012 B2: **PASS**（P1-2修正。金額は円単位、既定月は前月、全社=組織別合計一致）
- T013 M: **CONDITIONAL PASS** — `mvn clean test` が Skipped 0 で全緑。
  残る唯一の未実施は **desktop/390px 実ブラウザ一気通貫Demo**（本環境はサーバ起動用MySQLが常設できない）。
- **Spec全体: CONDITIONAL PASS。`enterprise-identity-security`(S03)は開始可**。
  実ブラウザDemoはS03と並行して本番リリース前に消化する残課題として扱う。

## 第十三次独立Review 指摘対応（2026-07-27）

第十三次独立Review（Base `601177a` / Head `0785890`、判定 FAIL、P0=0 / P1=6 / P2=1）の全件へ対応した。
指摘のとおり、前回のPASS判定は**時間軸（過去日の解決）と権限変更直後の一貫性**を検証できていなかった。
自動テストが全緑でも、これらは「現在値しか持たない列を過去日で読む」ことが原因のため検出できない。

### 根本原因（P1-1 / P1-5 共通）

`m_organization_unit.parent_id` / `status` と `t_engineer.cost_center_id` / `expected_unit_price` は
**現在値しか持たない**のに、`listTree(asOf)` / `descendantIds(asOf)` / Bench待機原価が
過去日を指定して参照していた。そのため、

- 組織統合を行うと「昨日の組織ツリー」まで今日の結果に変わり、統合元の部門責任者が
  統合前の自組織データを遡って見られなくなる／統合先が統合前のデータを見られてしまう。
- 月次締めが遅れている間に要員の原価部門・単価を変更すると、確定済み扱いの過去月へ
  新しい値が焼き付く（snapshotは訂正しない運用なので誤りが永続化する）。

→ **V61** で版管理テーブルを追加し、asOf解決をそこへ寄せた。組織・要員の行自体はIDを分岐させない
（所属・原価部門・予算・snapshotの参照を壊さないため。design.md「同一IDのversion CAS」を維持）。

- `t_organization_relation_history(organization_id, parent_id, status, valid_from, valid_to)`
- `t_engineer_accounting_history(engineer_id, cost_center_id, expected_unit_price, valid_from, valid_to)`
- 既存行はV61でbackfill（履歴ゼロだとasOf解決が「該当なし」になり過去月が丸ごと欠落するため）。
- 履歴が無い行は現在値へフォールバックし、集計から落とさない。

### 対応一覧

- **P1-1 統合が履歴を遡って書き換える**: `listTree`/`descendantIds` を履歴からの版解決へ変更。
  統合時は子の親付け替え・統合元の無効化を**統合日から**の版として記録し、統合前の日付では
  統合前の親子・状態を返す。組織の作成・更新・状態変更でも版を記録する。
- **P1-2 統合が無効・未来・期限切れの組織を受け入れる**: source/target を同一トランザクションで
  行ロックしてから、統合日に `status=有効` かつ有効期間内であることを両方に要求。
  `merged_into` が既に入っている組織の再統合も拒否。法人一致・循環・楽観ロック検査は維持。
- **P1-3 親・所属の期間包含が未検証**: 親組織は「有効」かつ期間が子を完全包含すること、
  所属期間は組織の有効期間を超えないことを検証。無期限の子・所属は無期限の上位にしか付けられない。
- **P1-4 無効化が終了日未来の在籍者を取りこぼす**: 判定を `valid_to IS NULL` から
  `valid_to IS NULL OR valid_to >= 当日` へ拡張。未開始の未来所属も同条件で拒否対象になる。
- **P1-5 延滞月次締めが現在の原価部門・単価を使う**: 待機原価SQL2本（集計用・snapshot用）を
  対象月時点の `t_engineer_accounting_history` から解決するよう変更。要員の登録・更新時に版を記録する。
- **P1-6 同一ユーザーの権限変更後もDashboardキャッシュが残る**: `ScopeVersionRegistry`（世代番号）を
  追加し、キャッシュキーへ含めた。所属変更・組織階層/統合/状態変更・所属クローズなど
  可視範囲に影響する更新の**コミット後**に世代を進める。粒度は意図的に全体
  （組織改編は低頻度であり、ユーザー単位で取りこぼすより広めの無効化のほうが安全）。
- **P2-1 管理会計UIの次元不足とID直表示**: 法人・案件・営業のフィルターを追加し、
  APIが受け付ける全次元（`legalEntityId`/`organizationId`/`costCenterId`/`customerId`/`projectId`/`salesUserId`）を送る。
  顧客・案件は選択式、営業は新設の `/api/autocomplete/sales-users` から選択式にした。
  DTOへ `costCenterName`/`customerName`/`projectName`/`salesUserName` を追加し、画面は名称を表示する
  （名称が引けない場合のみ `#ID` を補助表示）。名称は行ごとのSELECTではなく一括解決する。
  **CSV exportの列構成は変更していない**（15列の書式契約を維持。必要なら別途合意のうえ拡張する）。

### 追加した回帰テスト

- `OrganizationServiceImplTest`（+5件）: 統合前後の日付でのツリー解決、統合日に無効/未来/期限切れ/
  統合済みの拒否、親の期間包含、所属の期間包含、終了日が未来の在籍者がいる組織の無効化拒否。
- `DashboardScopeKeyGeneratorTest`（+2件）: 同一ユーザーで世代が進めばキーが変わること、
  共有キー(`ALL`)側も同様であること。
- `EngineerAccountingHistoryMapperTest`（新規2件）: 対象月時点の原価部門・単価で解決されること、
  履歴が無い要員は現在値へフォールバックすること。
- `MigrationScriptIntegrityTest`（+1件）: V61の作成→backfill順序と生成列VIRTUAL。
- `FlywayMigrationSmokeTest`: 履歴テーブルの列とLEGACY組織のbackfill済み初版を実MySQLで確認。

### 検証

- Docker Engine を起動し、`FlywayMigrationSmokeTest`（空庫V1→V61）と
  `FlywayLegacyV60MigrationSmokeTest`（旧V58形状→V61）を**実MySQL 8で実行して成功**。
- `mvn clean test`: **848 tests / Failures 0 / Errors 0 / Skipped 0 / BUILD SUCCESS**
  （環境変数の小細工なし。Docker/Node.jsの門禁も実行済み）。
- 未実施: desktop/390px 実ブラウザ一気通貫Demo（本環境はアプリ常駐用MySQLを持てない）。

### 判定（第十三次指摘に対する自己申告。独立再Reviewは未実施）

- P1-1〜P1-6、P2-1: いずれも修正・回帰テスト追加済み。
- Spec全体の最終判定と`enterprise-identity-security`の開始可否は、次の独立Reviewの結果による。
  本記録は「指摘へ対応した」ことの記録であって、PASSの自己宣言ではない。

## 第十四次独立Review（2026-07-27、T008〜T013）

Base `add488c`（HEAD, origin/main） / 対象: 未commit working tree diff（75ファイル、673 insertions / 217 deletions）。
第十三次Review指摘（P1×6/P2×1）への対応を独立semantic reviewで検証した。

### 事前準備（本Round）

- 本機にNode.js v24.18.0 + npm 11.16.0をwinget経由で導入し、Docker Desktopの稼働を確認した。
- V61（第十三次で追加した`t_organization_relation_history`/`t_engineer_accounting_history`）に続けて
  **V62** を新設し、`t_engineer_accounting_history.organization_id`をbackfillした上で
  Bench SQLの帰属解決順を「要員自身の`organization_id`優先→account link後方fallback」へ修正（design.md準拠）。
- これに伴い、`enterprise-identity-security`以降15 specのmigration予約番号をV61→V63以降へ順次繰り上げた
  （design.md 15件、README.md予約表、parallel-execution-plan.md、spec-start/review-conversations.md、
  task-start-conversations.md、copyable-conversations/ S03〜S17・R03〜R17、gate-0-readiness-report.mdを同期。
  Java/SQL実装コードへの影響なし）。

### 第十三次指摘7件への対応と検証結果

| # | 修正内容 | 検証結果 | 判定 |
|---|---|---|---|
| P1-1 | `OrganizationRelationHistoryMapper.selectAsOf`をCOALESCE→CASE WHEN h.id IS NULLへ。共通`OrganizationRelationResolver`で`OrganizationServiceImpl`と`OrganizationScopeServiceImpl`のas-of解決を統一。`visibleQuery`のstatus='有効'をSQLからJava側履歴解決後フィルタへ変更 | コード確認・ロジック正確。境界ケース（履歴行はあるがparent_idが明示NULL）の直接mapper単体テストはやや薄いが実害なし | PASS |
| P1-2 | `UserOrganizationMapper.selectByUserOrganizationForUpdate`にmergeDateパラメータ追加（valid_to IS NULL OR valid_to >= mergeDate）。`OrganizationServiceImpl.merge`でoriginalValidToをsuccessorへ保持 | コード確認・ロジック正確。期間限定所属の専用回帰テストはやや薄いが実害なし | PASS |
| P1-3 | 新設`ScopeChangeInvalidator`をEngineerSales(assign/setPrimary/release/releaseAll)、Contract(salesUserId変更save/update)、EngineerAccountLink(link/unlink)、Engineer(organizationId変更updateWithStatusGuard)、UserApiController(role変更)へ配線 | 全5箇所への配線を確認。`@Autowired(required=false)`は`ReflectionTestUtils.setField`で明示注入されテスト済み。verify(times)/verify(never)双方向テストあり | PASS |
| P1-4 | 新migration V62でt_engineer_accounting_history.organization_id追加+backfill。EngineerMapperのBench SQL2本の解決順を「要員自身優先→account link後方fallback」へ修正 | Docker実MySQL（空DB・legacy両方）でV62適用・backfill確認済み。解決順修正も両SQLで確認 | PASS |
| P1-5 | CASE WHEN eh.id IS NULLで、履歴行が見つかったが値が明示NULLのケースを誤fallbackしない | H2実行テストで「異なる原価部門/単価」「履歴なし要員のfallback」を確認。最も厳密な境界（一部フィールドのみ明示NULL）は間接検証のみ | PASS |
| P1-6 | AutocompleteApiControllerに`/customer-options`,`/project-options`,`/legal-entities`新設(id+name DTO)。既存`/customers`/`/projects`文字列datalist契約は変更なし | 新規4テストでDTO契約とscope外0件を確認。既存エンドポイントの契約破壊なし | PASS |
| P2-1 | management-accounting.js/index.htmlの法人フィルターをinput(number)→select化 | diff範囲は`accountingLegalEntityId`のみで正確 | PASS |

### 軽微な指摘（P2、ブロッカーではない）

1. 待機原価集計SQL（`selectAccountingWaitCost`、組織横断summary用）はMockito差し替えのみで検証され、
   実SQL自体はsnapshot用の`selectAccountingWaitCostByEngineer`側のH2テストでのみ間接的に裏付けられている。
2. `OrganizationServiceImpl.merge`時の要員会計履歴付け替え（`t_engineer_accounting_history`の新版作成・旧版close）
   に専用アサーションがない（`t_engineer.organization_id`側は既存テストでカバー）。
3. `EngineerSalesServiceImplTest`に未使用の`scopeVersionRegistry`フィールドが残存（リンター指摘レベル、実害なし）。

### migration予約番号の文書同期の完全性

**完全**。independent reviewerによるgrep検証で、15件のdesign.md（V63〜V77）、README.md予約表、
copyable-conversations/ S03〜S17・R03〜R17が全て一致し、重複・欠落なし。`gate-0-readiness-report.md`は
過去の記録（V58時点作成、V60〜V75計画値）を時系列として保持しつつ、現在の正はREADME.mdであることを
明記する形で訂正した（履歴改変ではない）。Java/SQL実装コードには一切影響がないことも確認された。

### 検証された実測値

- `mvn clean test`: **862 tests / Failures 0 / Errors 0 / Skipped 1**（`QuotationPdfServiceImplTest`、
  既存無関係のフォント環境依存skip）、BUILD SUCCESS。independent reviewerが本環境で再実行して確認。
- Docker MySQL smoke: `FlywayMigrationSmokeTest`、`FlywayLegacyV60MigrationSmokeTest`、
  `FlywayRepairRunbookTest`、`ConcurrentUpdateTest` の4件、**0 skipped、全て成功**。実MySQL 8で確認。
- `JsSyntaxCheckTest`: 0 skippedで成功（Node.js v24.18.0導入済み）。
- `git diff --check`: exit 0（LF→CRLF警告のみ、実質問題なし）。

### 未検証環境・条件

- desktop/390px 実ブラウザ一気通貫Demo: 未実施（本環境はアプリ常駐用MySQLを持てないため）。
  第十二次Review以降継続する既知の残課題であり、今回のP1×6/P2×1修正の対象ではない。S03と並行して
  本番リリース前に消化する。

### 判定

- T008 F1〜T013 M: 第十三次指摘7件は全件根本修正（回避的パッチではない）と確認。
- **Spec全体: PASS**。
- **`enterprise-identity-security`(S03): 開始可（NOT READY解除の可否について、ユーザー確認待ち）**。
  organization-management-accountingはT008〜T013全てPASS、Docker MySQL smoke・Node syntax smoke・
  全量mvn testを本Roundで実機確認済み。残る実ブラウザDemoはS03と並行して本番リリース前に消化する
  残課題として記録し、S03開始のブロッカーとはしない。

### 転記用結論文

> 第十四次独立Review（Base `add488c`, 未commit working tree diff, 75ファイル/673+/217-）: 第十三次指摘の
> P1×6/P2×1は全件、根本原因（現在値と履歴値の混同によるNULL誤fallback、期間限定所属の取りこぼし、
> DataScope変更の伝播漏れ、要員の所属組織帰属順序の誤り、autocomplete契約の不一致、法人フィルタのUX）
> に対応する修正であることをコード読解・実機テストの両方で確認した。`mvn clean test`は862 tests/0 failures/
> 0 errors/1 skipped（font依存の既知skip）、Docker実MySQL 8でのFlywayMigrationSmokeTest/
> FlywayLegacyV60MigrationSmokeTest/FlywayRepairRunbookTest/ConcurrentUpdateTestは4件/0 skipped/全て成功、
> `git diff --check`はexit 0を本Reviewで独立に再現した。P0=0/P1=0。軽微なテストカバレッジの薄さ
> （待機原価集計SQLの実SQL未検証、merge時の要員会計履歴付け替えの専用テスト欠如、未使用フィールド）を
> P2として3件記録するが、ブロッカーではない。**判定: PASS**。migration予約番号の文書同期（V61/V62実使用に伴う
> 後続15 specのV63〜V77への繰り上げ）も全ファイルで完全一致を確認した。`enterprise-identity-security`(S03)
> の開始条件は満たされたが、NOT READY解除の最終判断はユーザー確認を待つ。実ブラウザDemoはS03と並行して
> 本番リリース前に消化する残課題として引き続き記録する。

## R02 最終merge後差分Review（R21、2026-07-29）

- Review種別: `execution-review-handbook.md` v2.0準拠のmerge後最終差分Review。
- 固定範囲: Base `4015785` → Head `f6f002706dd201ed40e1d2ba808c30d6bb96eea6`。
- Head状態: `main` / `origin/main`一致、ledger更新前のworking tree clean、`git diff --check` exit 0。
- Review範囲: 既存OPEN issue、各fix delta、direct regressionのみ。VERIFIED_CLOSED済み指摘は再審査・再openしていない。

### Issue Register

- issue ID: `organization-management-accounting-R21-P1-01`
- severity: P1
- violated requirement/acceptance: R2.2、R3.1、R3.2、R4
- final state: **VERIFIED_CLOSED**
- root cause: WorkRecord更新認可が既存recordの凍結組織より先に要員の現在組織を参照し、修正途中では
  非凍結recordの非NULL歴史組織をaccount-link所属で上書きできた。
- fix commit: `f6f002706dd201ed40e1d2ba808c30d6bb96eea6`
- verification evidence: 凍結recordはlock取得後にsnapshot組織で判定。非凍結recordは対象月の
  `t_engineer_accounting_history.organization_id`を正とし、非NULL時は通常account-link fallbackを禁止。
  直属上長だけを追加許可し、`UNKNOWN`はfail-closed、既知NULL/履歴なしだけlegacy fallbackを許可する。
- regression evidence: `saveHours`/`saveDaily`それぞれについて、凍結・非凍結のManager A拒否（永続化0件）と
  Manager B許可を双方向testで確認。
- reopen condition: 対象月の非NULL要員履歴より現在組織または通常account-linkを優先する経路、あるいは
  拒否後にWorkRecord/WorkRecordDailyが更新される再現証拠が得られた場合のみ再openする。

### 最終テスト証拠

- 定向: `WorkRecordServiceImplTest` 43件、`EngineerAccountingHistoryMapperTest` 3件、合計
  **46 tests / Failures 0 / Errors 0 / Skipped 0**。
- L4全量: `mvn clean test`をHead `f6f0027`で独立実行し、
  **904 tests / Failures 0 / Errors 0 / Skipped 1 / BUILD SUCCESS**。
- 唯一のskip: `QuotationPdfServiceImplTest`（既知のCJK font環境依存）。S02差分およびR21とは無関係。
- Docker MySQL 8: `FlywayMigrationSmokeTest`、`FlywayLegacyV60MigrationSmokeTest`、
  `FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`ConcurrentUpdateTest`を
  **5 tests / Failures 0 / Errors 0 / Skipped 0**で確認。
- Node/JS: `JsSyntaxCheckTest` **1 test / Failures 0 / Errors 0 / Skipped 0**。

### 最終判定

- OPEN P0: 0、OPEN P1: 0。
- **Spec全体: PASS**。
- `enterprise-identity-security`（S03）は **READY**。中央`spec-execution-ledger.md`へ同期済み。
- desktop/390px実ブラウザ一気通貫Demoは既知の本番前hard gateとして維持し、S03開始を阻止しない。
