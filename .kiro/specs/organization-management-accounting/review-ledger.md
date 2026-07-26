# Review Ledger — 組織・管理会計

## 運用

各TaskはObjective・自動テスト・Demoを確認してから完了にする。未検証の外部環境はPASSと記録しない。
V59は作成せず、既存DB更新用MigrationはV60を使用する。

## T008 F1 — 組織/所属/cost center/予算DDL

- 状態: 完了（実装済み、独立Review待ち）
- 対応requirements: R1.1〜R1.4、R2.1、R4の組織階層・異動履歴・参照中削除禁止に必要な基盤
- 変更範囲: V1統合baseline、V60、H2 replay schema、`engineer-schema-h2.sql`、Entity/Mapper/Service、楽観ロック設定、4言語メッセージ、MySQL smoke assert
- 自動テスト: `OrganizationServiceImplTest` 4件成功（親子/子孫、循環、期間重複、主所属重複、参照中削除、無効化、予算version競合）
- Migration整合性: `MigrationScriptIntegrityTest` 2件成功。V59なし、V60重複なし、空Migrationなし
- MySQL smoke: `FlywayMigrationSmokeTest` はDocker daemon未起動のため1件skip。実MySQL適用は未検証
- Demo: H2上で `legal_entity_id` を将来法人境界として保持し、事業部→課の階層を登録。管理者ユーザーを上長として所属履歴を登録できるサービス/API基盤を確認。参照中組織は削除拒否、無効化は成功
- 未検証: Docker上のMySQL 8空DBからV1→V60の実適用、ブラウザ操作、実法人マスタとのFK接続
- ロールバック: V60未適用環境はMigration適用を止める。適用後は過去Migrationを編集せず、組織レコードを削除せず無効化し、必要ならV60適用DBをバックアップから復元する

## T009 F2 — OrganizationScopeService

- 状態: 完了（実装済み、独立Review待ち）
- 対応requirements: R3.1〜R3.3、R4の上長配下閲覧とscope漏洩防止
- 変更範囲: `OrganizationScopeService`、request-scoped実装、組織Mapper query boundary、4言語scopeエラー
- 規則: 管理者は無条件、マネージャーはprimary所属の自組織+子孫、営業/HR/一般ユーザーは有効所属組織のみ。menu roleは独立認可、既存DataScopeは同一ID母集団を渡した場合のみ積集合で結合し、scopeを拡張しない
- SQL境界: list/count/exportが共通query builderを使い、`IN`条件をDBへ追加。空集合は`id = -1`で0件。画面後フィルターなし
- cache key: tenant（現行独立DBではnull）/user/role/as-of/versionをrequest内cache keyに含めた
- 自動テスト: `OrganizationScopeServiceImplTest` 4件成功（管理者、部門責任者、営業、HRの一覧・件数・export、未許可組織assert）
- Demo: 部門長ログインで配下2組織だけ、営業/HRログインで所属1組織だけ、管理者で全組織を一覧・件数・出力できることをH2で確認。SQLログに対象ID条件が出ることを確認
- 未検証: 実ブラウザのログイン画面、既存業務テーブルへ組織IDを結合する後続Task、Docker MySQL smoke
- ロールバック: `OrganizationScopeService` を呼び出す後続経路を無効化し、組織レコードは無効化運用。V60以前のDBへ戻す場合はバックアップ復元のみ

## T009〜T013

- T010 A1: 完了（実装済み、独立Review待ち）
  - 対応requirements: R1.1〜R1.4、R2.1、R3.1〜R3.3の管理画面/API境界
  - 変更範囲: `OrganizationApiController`、page controller、DTO、Thymeleaf画面、module JS、V60 menu seed、sidebar、4言語i18n
  - API: 組織一覧/詳細/CRUD、状態切替、所属一覧/登録、cost center一覧/CRUD。全一覧・所属・cost centerはOrganizationScopeServiceのSQL wrapper適用後に取得し、画面後フィルターを行わない
  - 横断規約: Spring Security CSRFを更新系APIへ適用。`ApiAuditFilter`が更新APIを監査。versionをDTOから受けてMyBatis-Plus楽観ロックを利用。menuはV60の`m_menu`/`t_role_menu`で管理
  - 自動テスト: `OrganizationApiControllerTest` 5件成功（validation、CSRF有/無、scope境界、一覧query boundary）
  - Demo: MockMvcでCSRF付き登録がcode=200、CSRFなし更新系が403、マネージャーの一覧がscope serviceへ基準日付きで委譲、scope外詳細が拒否されることを確認
  - 未検証: 実ブラウザの異動前後フォーム操作、実MySQLのV60 menu seed（Docker daemon未起動）、後続Taskの契約/勤怠画面との組織表示
  - ロールバック: V60適用前はAPI/画面をリリース対象から外す。適用後は組織を削除せず無効化し、必要ならバックアップ復元。V59は作成しない
- T009 F2: 完了（前項記録済み）
- T011 B1: 完了（実装済み、独立Review待ち）
  - 対応requirements: R2.2、R2.4、R4の異動後過去実績不変
  - 変更範囲: `MonthlyAccountingSnapshotService`、月次締めhook、snapshot API、予算API/CSV、scope付きsnapshot/予算一覧、V60管理会計menu、4言語i18n
  - snapshot: 締め対象月の確定work recordを月初時点の主所属へ帰属し、cost center/sales user/売上/原価を保存。`work_month/source_type/source_id`一意キーと既存行skipで再締め・異動後の上書きを禁止。並行実行は一意制約で一度だけ確定
  - 予算: 組織scopeをSQL query boundaryへ適用し、単件upsertとUTF-8 CSV取込を提供。既存versionの不一致は409相当の業務例外、CSVの数式/負数など不正値は拒否し、CSV取込はトランザクションで全体ロールバック
  - 月次締め: `MonthlyClosingServiceImpl.confirmClosing`の締め確定前にsnapshot hookを実行。既存のreopen規約・CSRF・監査フィルタを維持
  - 自動テスト: `MonthlyAccountingSnapshotServiceImplTest` 2件成功、`MonthlyClosingServiceImplTest` 12件成功（snapshot hook、reopen含む）。F1の予算version競合テストも成功
  - Demo: 2026-06の確定work recordを1月初の主所属へsnapshotし、同じ月を再実行して既存snapshotが0件追加・所属再参照なしになることをMockで確認。締め確認時にhookが呼ばれること、再開（reopen）が既存規約どおり動くことを確認
  - 未検証: 実DBでのCSVファイル取込とロールバック、Docker MySQL V60適用、実際のwork_record/engineer_account_linkデータでのブラウザ操作、cost center既定配賦の業務データ網羅
  - ロールバック: V60未適用環境では管理会計API/メニューを公開しない。適用後はsnapshotを削除・更新せず、誤帰属は明示訂正Taskと監査理由で扱い、必要ならバックアップ復元
- T012 B2: 完了（実装済み、独立Review待ち）
  - 対応requirements: R2.3、R3.1〜R3.3、R4の全社合計/組織別合計一致
  - 変更範囲: `ManagementAccountingService`/summary DTO、契約の組織scope SQL query、管理会計API/export、管理会計画面/JS、V60 menu、4言語i18n
  - 金額口径: 既存`MonthlyRevenueCalcService`へ契約単位の金額解決を委譲。確定work recordはsnapshotの組織を優先し、未snapshotの見込みはwork month時点の有効primary所属でSQL取得した契約組織を使用。予算は同じ組織scope wrapperで取得し、売上/粗利の予算差を算出
  - scope: 非管理者は契約の所属組織をJOIN条件で`IN`制限し、snapshot/予算もSQL `IN`。管理者のみ全件。export endpointも同じsummary serviceを再利用し、画面後filterなし
  - 自動テスト: `ManagementAccountingServiceImplTest` 1件成功（売上/原価/粗利、予算差、SQL scope引数、組織名）。F2 scope testとA1 API CSRF testも継続成功
  - Demo: 2026-06の組織100で売上120・原価70・粗利50、売上予算110・粗利予算40を入力したsummaryが、売上差10・粗利差10を返し、同じ行の組織名とscopeを保持することをMockで確認
  - 未検証: 実ブラウザのChart/CSV download、実MySQL JOIN/インデックス計画、待機費の既存業務データ連携（現実装はsnapshot/既存金額にない待機費を0として明示）、Docker smoke
  - ロールバック: `/management-accounting` menu/APIを無効化し、予算/snapshotは削除せず読み取り停止。V60以前へ戻す場合はバックアップ復元
- T013 M: 完了（実装済み、独立Review待ち）
  - 対象: T008〜T012の全自動回帰、Migration整合性、i18n、既存mobile layout
  - 自動テスト: `mvn test` 成功。761 tests、Failures 0、Errors 0、Skipped 5。`MessageBundleConsistencyTest`、`MigrationScriptIntegrityTest`、`MobileResponsiveLayoutTest`を含む
  - JS: `JsSyntaxCheckTest`はNode.js未導入のため1件skip
  - MySQL: `FlywayMigrationSmokeTest`はDocker daemon未起動（Docker clientは存在するがLinux engine pipeへ接続不可）のため1件skip。V1→V60実MySQL適用は未検証
  - Demo: サービス/APIのMock Demo（組織scope、snapshot不変、予実差、CSRF）と、H2統合回帰（mobile layoutを含む）を確認
  - 未検証: 実ブラウザでのログインから組織作成→所属異動→契約→締め→部門損益の一気通貫、Docker MySQL smoke、Node JS syntax smoke
  - ロールバック: 本Specで追加したAPI/menu/pageをリリース単位で無効化し、V60適用済みDBの組織/予算/snapshotを削除せずバックアップ復元。V59は作成しない。既存機能の変更は本Spec差分だけを選択的にrevertする
- T013 M: 未着手
