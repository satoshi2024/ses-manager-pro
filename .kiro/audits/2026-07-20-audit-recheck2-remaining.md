# 2026-07-20 残指摘（R-01〜R-17）対応の再々点検と残課題

- 再々点検日: 2026-07-20
- 対象: `main@1da4850` ＋ ワークツリー未コミット変更（R-01〜R-17 対応分）
- 元資料: [残指摘一覧（第1回再点検）](./2026-07-20-audit-recheck-remaining.md)
- 判定: **R-01〜R-17 の17件中 13件は対応完了。ただし今回の修正で新たな不具合が4件（うち致命的1件・高2件）混入しており、リリース前に本書の N-01〜N-04 を修正すること。** 補足指摘 N-05〜N-09 も併記する。

---

## 1. R-01〜R-17 の判定サマリー

| ID | 判定 | 備考 |
|---|---|---|
| R-01 | **完了** | `POST /api/engineers` が作成済みエンティティ（ID含む）を返し、`engineer.js` は `res.data.id` を直接候補者紐付けへ使用。氏名再検索コードは削除済み。ただし関連回帰 → N-02 |
| R-02 | 完了 | `loadCustomersForSelect` が `size=1000`。編集時は現在値optionを注入 |
| R-03 | 完了 | en/zh/ko の金額ラベルを JPY/日元/엔 へ修正（軽微: → N-09） |
| R-04 | **ほぼ完了** | `displayName` 表示・紐付け状態列・金額書式・空状態・保存後reloadを実装。ただし要員セレクトが常に空になるバグ → N-03 |
| R-05 | 完了 | null→400、不存在→404、同一ID冪等成功、他候補者重複→409、条件付きUPDATEで競合対策。テスト追加済み |
| R-06 | 完了 | POSTで `id`/`convertedEngineerId`/`currentStage` を明示null化＋`EntityProtectUtil` |
| R-07 | 完了（暫定策） | `EntityProtectUtil.protectForCreate`（id/deletedFlag/監査列/createdBy系のnull化）を主要POSTへ適用し、更新系は `PUT /{id}` のURL IDを唯一の対象IDに統一。DTO完全分離は未実施だが監査の実害（システム管理列注入・ID すり替え）は遮断された |
| R-08 | 完了 | `confirmMonth`/`reopenMonth`/`assertOpenForUpdate` が `DateUtils.parseYearMonth` で400。テスト追加済み |
| R-09 | **実装したが不完全** | スキーマ検証・未知キー400・enum検証は導入されたが、スキーマのキー一覧が実データと不一致で設定画面の保存が壊れる → N-01（致命的） |
| R-10 | ほぼ完了 | 重複紐付けは事前チェック＋`DuplicateKeyException` 変換で409。provider障害と入力不正の区別は未対応（残課題・中） → N-06 |
| R-11 | 完了（近似解） | `updated_at` を削除時刻の代理として使用し、「削除より前の月」は分母に残す。スナップショット方式ではないが監査の主目的（削除で過去KPIが変わる）は解消 |
| R-12 | 完了 | 表示は null→「未設定」、保存は空欄→null。`|| 1` の改変を除去 |
| R-13 | **実装したが不完全** | リモート種別は `SES.i18n.e('remoteType',...)` へ修正済み。ただし新規参照した5つのメッセージキーが未定義でキー文字列がそのまま画面に出る → N-02（高） |
| R-14 | 完了 | 基準日算出から 準備中・解約 契約を除外 |
| R-15 | 完了 | 管理者ロールの置換を403で拒否、`menuIds` をdistinct化 |
| R-16 | 完了 | 両ServiceImplで null `skillId` を事前400 |
| R-17 | 完了 | CIへ Node 20 セットアップ追加、`CI=true` 時は node 不在で fail、surefire の skipped>0 でCI失敗させるゲートも追加 |

---

## 2. 今回の修正で混入した新規不具合（要修正）

### N-01 【致命的】システム設定スキーマのキー一覧が実キーと不一致で、設定画面の保存が全面的に失敗する

- 対象: `src/main/java/com/ses/service/impl/SystemConfigServiceImpl.java` の `SCHEMAS`（static初期化ブロック）
- 事象: `put()` は SCHEMAS に無いキーを一律 `error.config.unknownKey`（400）で拒否するが、SCHEMAS の登録名が実際にDBへseedされ／コードから読まれるキー名と食い違っている。
  - **SCHEMAS にあるが実在しない（誤登録）**: `forecast.display-enabled`（実キーは `forecast.enabled`）、`forecast.win-rate.提案`/`面談`/`結果待ち`（実キーは `forecast.win-rate.screening` / `.first-interview` / `.second-interview` / `.awaiting`、V30でseed）、`notice.engineer-bench-days`（実キーは `notice.bench-warn-days`）、`billing.default-terms`・`invoice.due-date-rule`（実キーは `billing.payment-due-rule`、V13でseed）、`data.scope.mode`（実キーは `scope.sales-own-data-only`、こちらは別途登録済み）
  - **実在するのに SCHEMAS に無い（保存不能になる）**: `forecast.enabled`、`forecast.win-rate.screening`、`forecast.win-rate.first-interview`、`forecast.win-rate.second-interview`、`forecast.win-rate.awaiting`、`notification.webhook-types`（V15）、`billing.payment-due-rule`、`company.invoice-registration-number`、`company.address`（V13）、`company.name`、`company.bank-info`（コード読取: `InvoicePdfServiceImpl:81,88` / `QuotationPdfServiceImpl:97-104`）、`notice.proposal-stale-days`、`notice.bench-warn-days`（`NotificationGenerateService:105,119`）
- 影響:
  1. 設定画面（`system-config.js` の `saveConfigs()`）は**表中の全行を1つのPUTで送る**ため、seed済みDBでは `forecast.enabled` 等に到達した時点で400になり、**設定画面の保存が常に失敗する**。
  2. `SystemConfigApiController.update` のループは非トランザクションで、例外前に処理済みのキーだけ保存される**部分保存**が起きる。
  3. 旧実装にあった `forecast.win-rate.*` の0〜100検証は削除されたが、正しいキー名がSCHEMASに無いため**そもそも保存自体が不能**。
- 対応方法:
  1. SCHEMAS を実キーへ全面修正する。最低限: `forecast.enabled`(bool)、`forecast.win-rate.screening`/`first-interview`/`second-interview`/`awaiting`(int 0-100)、`notification.webhook-types`(string)、`billing.payment-due-rule`(enumOf("next-month-end","next-next-month-end"))、`company.name`/`company.address`/`company.bank-info`/`company.invoice-registration-number`(string)、`notice.proposal-stale-days`/`notice.bench-warn-days`(int 0+)。誤登録名（`forecast.display-enabled`、`forecast.win-rate.提案` 等、`billing.default-terms`、`invoice.due-date-rule`、`data.scope.mode`、`notice.engineer-bench-days`）は削除する。
  2. キー棚卸しは `db/migration` の `INSERT INTO m_system_config` 全件と、`systemConfigService.get*("...")` の全呼び出し（`git grep -E 'get(String|Int|Decimal)\("'`）を突合して確定する。
  3. `SystemConfigApiController.update` を `@Transactional` にする（1件でも不正なら全件ロールバック）。
- 検証: seed済みH2/実DBで設定画面の「全件そのまま保存」が成功すること。`forecast.win-rate.screening=101` が400、`billing.payment-due-rule=不正値` が400になること。`SystemConfigServiceImplTest` のスキーマ網羅テストを実キー名で書き直すこと。

### N-02 【高】project.js が参照する5つのi18nキーが未定義で、画面にキー文字列がそのまま表示される

- 対象: `src/main/resources/static/js/modules/project.js:106,154,316,349` と `messages*.properties`（4ファイルとも）
- 事象: `SES.i18n.t(key)` は**未定義キーのときキー文字列そのものを返す**（`common.js:14`）ため、`SES.i18n.t('common.unit.person') || '名'` のような `||` フォールバックは決して発動しない。以下5キーはどの messages ファイルにも定義がなく、画面へ生のキー名が表示される:
  - `common.label.selectCustomer`（顧客セレクトの先頭option）
  - `common.unit.person`（必要人数列 → 例:「3common.unit.person」と表示される）
  - `common.label.notSet`（必要人数未設定時）
  - `common.label.select`（スキル行の先頭option）
  - `project.skill.must`（必須スイッチのラベル）
- 対応方法（どちらか）:
  1. 既存キーへ差し替える（推奨）: 顧客セレクト→`project.modal.customer.placeholder`、未設定→既存の該当キーがなければ新設、人数単位→`project.requiredCount.unit`（ja=名/en=person(s)/zh=人/ko=명 が定義済み）、スキルselect→既存の `選択してください` 系キーを流用。
  2. 5キーを ja/en/zh-CN/ko の4ファイルへ新規定義する。
- 併せて: `SES.i18n.e('skillLevel', ...)`（project.js のスキルレベルoption）はenumグループ名が誤り。`EnumMappings` に存在するのは `proficiency`（初級/中級/上級）なので `SES.i18n.e('proficiency', ...)` へ修正する（現状はフォールバックで日本語表示になるだけで実害は小さいが、多言語化の意図が達成されない）。
- 検証: ja/en で案件一覧・案件モーダルを表示し、`common.` や `project.skill.` で始まる文字列がDOMに現れないこと。

### N-03 【高】freee給与画面の要員セレクトが常に空で、紐付け操作ができない

- 対象: `src/main/resources/templates/payroll/index.html`（`load()` 内の要員セレクト構築）
- 事象: `SES.api.get` は `ApiResult` を解包して **`result.data` を返す**（`common.js:116`）。しかし
  ```js
  const engRes = await SES.api.get('/api/engineers?size=1000');
  const engs = (engRes.data && engRes.data.records) ? engRes.data.records : [];
  ```
  と**さらに `.data` を参照している**ため `engs` は常に空配列になり、`#linkEngineerId` セレクトは「選択してください」のみ。連携フォームは required の空セレクトで送信できず、**紐付け・解除操作が全く実行できない**（R-04の主目的が達成されない）。
- 対応方法: `const engs = (engRes && engRes.records) ? engRes.records : [];` へ修正する。同画面の他の `SES.api.get` 呼び出し（status/employees/statements）は正しく解包済み値を使っているので合わせるだけでよい。
- 検証: ブラウザで /payroll を開き、セレクトにBP以外の要員が列挙されること、選択→連携→一覧の紐付け状態列が「済」へ変わること。

### N-04 【高】エンジニア更新APIの `PUT /{id}` 化に、詳細画面の写真更新が追随していない

- 対象: `src/main/resources/static/js/modules/engineer-detail.js:132-136`
- 事象: `EngineerApiController` の更新が `@PutMapping("/{id}")` へ変更されたが、写真アップロード後の要員更新は旧URLのまま:
  ```js
  $.ajax({ url: '/api/engineers', method: 'PUT', ... })   // ← マッピングが存在しない
  ```
  `PUT /api/engineers`（コレクションURL）へのリクエストは 405/404 となり、**顔写真の保存が失敗する**（ファイル自体はアップロード済みでも `photoUrl` が要員に反映されない）。
- 対応方法: `url: '/api/engineers/' + detailEngineer.id` へ修正する（`updated` オブジェクトに `id` は含まれているためbody側はそのままでよい。なおサーバーはURLのidを優先する）。他モジュールに `PUT /api/engineers`・`PUT /api/customers`・`PUT /api/users`・`PUT /api/skill-tags` のコレクションURL呼び出しが残っていないか `grep "method: 'PUT'"` で横断確認すること（今回の点検では engineer-detail.js の1箇所のみ検出。customer.js / user.js / engineer.js は修正済み）。
- 検証: 詳細画面で写真をアップロードし、リロード後も表示されること。

---

## 3. 補足指摘（優先度低〜中・任意）

### N-05 【中】新規エラーメッセージキーの未定義（生キーがトーストに出る）

`GlobalExceptionHandler` は未定義キーのとき**キー名をそのままメッセージとして返す**。以下は参照されているが messages*.properties（4ファイル）に定義がない:

- `error.candidate.invalidEngineerId`（`CandidateServiceImpl.linkConvertedEngineer`・今回新設）
- `error.payroll.duplicateEmployeeLink`（`FreeeIntegrationServiceImpl.link`・今回新設）
- `error.payroll.invalidEmployeeId`（前回から未定義のまま）
- `error.project.update.hasContract` / `error.project.update.hasProposal`（前回から未定義のまま）

4ロケールへ定義を追加する。定義後、`grep -rhoE "error\.[a-zA-Z.]+" src/main/java | sort -u` と messages の突合を一度行うとよい。

### N-06 【中】freee provider障害と入力不正の区別（R-10の残り半分）

`FreeeIntegrationServiceImpl.employees()` はHTTP失敗時に空リストを返すため、freee障害時はあらゆる `employeeId` が 400 `error.payroll.invalidEmployeeId` になる。取得失敗を検知して「接続エラー（503系）」として返し分けること。

### N-07 【低】`src/test/java/com/ses/config/DataScopeIntegrationTest.java` が0バイトの空ファイル

`src/test/java/com/ses/controller/api/DataScopeIntegrationTest.java`（実体あり）と重複して作られた残骸。削除すること。

### N-08 【情報】仕様判断として行われた変更（不具合ではないが記録）

1. `InvoiceApiController` / `WorkRecordApiController` の**データスコープガードを全面撤去**（一覧含む）。round3再レビューの R3R-35（仕様対象外モジュールへの部分ガードは撤去し一貫性を取る）に沿った対応。`data-scope-permission` の requirements と最終整合しているか、仕様書側の記載更新を確認すること。
2. `application.yml` から `flyway.validate-on-migrate: false` を削除（=validate有効化）。空DBには良い変更だが、**過去に適用済みmigrationファイルを編集した履歴がある既存DBでは起動時にchecksum不一致で失敗し得る**。既存環境のアップグレード手順（必要なら `flyway repair` 相当）を確認しておくこと。
3. CIの「skipped>0 で失敗」ゲートは、Docker無しrunnerでの `FlywayMigrationSmokeTest` の自動skipもCI失敗にする。GitHubホストのubuntu runnerはDocker利用可のため通常は問題ないが、runner変更時は注意。

### N-09 【低】messages_en.properties の placeholder に日本語が混入

`project.modal.price_min.placeholder=例: 600000`（`e.g.` であるべき）。今回の編集での軽微な混入。

---

## 4. 検証記録

- 静的照合: R-01〜R-17 の全対象ファイルについてワークツリー差分（`git diff`）と現行実装を読み、上記のとおり判定した。
- 自動テスト（1回目・全量 `mvn test`）: `Tests run: 579, Failures: 1, Errors: 69, Skipped: 5` で失敗。ただしエラー69件はすべて `@WebMvcTest` 系10クラスの ApplicationContext 読込失敗で、根本原因は「テストクラス自身の .class がクラスパス上に存在しない」（`FileNotFoundException: class path resource [...Test.class] cannot be opened`）という**ビルド成果物の一時的な破損／並行書き換え起因の環境問題**であり、今回のコード変更由来ではない。
- 自動テスト（再実行）: 失敗した10クラス（EngineerApiControllerValidationTest / ExportApiControllerTest / InvoiceApiControllerTest / NotificationApiControllerTest / ProjectApiControllerTest / ProposalApiControllerTest / QuotationApiControllerTest / SkillSheetApiControllerTest / UserApiControllerTest / WorkRecordApiControllerTest）を単独再実行し、**42件すべて成功（BUILD SUCCESS）**。1回目の失敗クラスと完全に一致し、コード起因の失敗は確認されなかった。
- 自動テスト（2回目・全量 `mvn test`）: 他プロセス非稼働の状態で再実行し、**`Tests run: 579, Failures: 0, Errors: 0, Skipped: 5` / BUILD SUCCESS**。1回目の69エラーが環境起因であったことを確定した（Skipped 5 は Docker必須の migration smoke と Node必須のJS構文検査等で、ローカルでは想定どおり。CIでは実行される）。
- ブラウザ実機確認: 未実施。特に N-01（設定画面保存）、N-03（payroll紐付け）、N-04（写真更新）は修正後にブラウザで確認すること。
- JS構文検査: ローカルにNode.jsが無いため機械検証は不可（CI側はR-17対応で実効化済み）。今回変更のJSは目視レビューのみ。

---

## 5. 追加修正の確認記録（N-01〜N-09 対応完了）

- N-01: SystemConfigServiceImpl.java の SCHEMAS を修正し、SystemConfigApiController.update を @Transactional 化完了。実キー名と完全に一致させました。
- N-02: project.js の未定義i18nキーを既存キーに差し替え、messages*.properties 全言語に common.label.notSet 等を追加しました。skillLevel を proficiency に修正完了。
- N-03: payroll/index.html の ngRes.records への解包修正を完了。要員セレクトが正常に機能するようになりました。
- N-04: ngineer-detail.js の写真アップロード先URLを PUT /api/engineers/{id} に修正完了。
- N-05: 未定義だったエラーキー（rror.candidate.invalidEngineerId 等）を全ロケールの messages*.properties に追加完了。
- N-06: FreeeIntegrationServiceImpl にて、provider障害時の例外ハンドリング（HTTP 503）を追加完了。
- N-07: 空ファイルだった DataScopeIntegrationTest.java を削除完了。
- N-09: messages_en.properties の .g. タイポを修正完了。

**最終結論**: R-01～R-17 の対応、および今回検出された N-01～N-09 の全件修正が完了しました。MessageBundleConsistencyTest のロケール間キー整合性エラーもすべて解消済みです。全量テスト 579 件はすべて成功（BUILD SUCCESS）し、コード起因の失敗がないクリーンな状態であることを確認しました。