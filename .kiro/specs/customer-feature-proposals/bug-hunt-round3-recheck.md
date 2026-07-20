# 第3回ポストレビュー（R3R-01〜37）対応の再検証結果

- 作成日: 2026-07-20
- 検証対象: `main` / `1da4850`（PR #29 マージ + 「故障修正」コミットを含む）
- 参照資料: `bug-hunt-round3-post-review.md`（R3R-01〜37）、`bug-hunt-round3.md`、各 spec requirements

## 1. 結論

R3R-01〜37 の **37件中32件は対応済みであることをコード突合で確認した**。
一方、**5件が部分対応のまま残存**しており（いずれも「バックエンドは実装済みだがUI導線・仕様同期・テスト基盤が未完」という形）、さらに検証中に**新規の高リスク指摘を1件**発見した。

| 区分 | 件数 | 項目 |
|---|---|---|
| 対応済み | 32 | R3R-01〜19, 21, 22, 25〜34, 37 |
| 部分対応（残件あり） | 5 | R3R-20, 23, 24, 35, 36 |
| 新規指摘 | 1 | RC-01（`validate-on-migrate: false`） |

残件は下記 RC-01〜RC-06 として整理する。**各項目は独立して着手可能**だが、RC-01（マイグレーション検証の無効化）を最優先とすること。

---

## 2. 残指摘の詳細（別AI向け・このファイル単独で作業可能な粒度）

### RC-01 【高・新規】Flyway の validate-on-migrate が全プロファイルで無効化されている

- **場所**: `src/main/resources/application.yml:56`（`spring.flyway.validate-on-migrate: false`）。
  導入コミット: `1da4850`（「故障修正」、2026-07-20）。同コミットの他の変更
  （`MyBatisPlusConfig.AutoFillMetaObjectHandler` の public 化）は無害。
- **事象・影響**: R3R-01/02 の修正で既存マイグレーションファイルの削除
  （`V15__add_invoice_id_to_mail_delivery.sql`）・書き換え（`V37__c14_c15.sql` 等）を行ったため、
  修正前に旧V15/旧V37を適用済みのローカルDBで checksum 不一致が発生し、
  その回避として validate 自体を無効化したと推定される。この設定は **dev だけでなく prod を含む
  全環境に効く**ため、今後マイグレーションファイルが誤って書き換えられても・適用順が壊れても
  起動時に一切検出されなくなる。R3R-01 の期待結果「空DB、V9 baseline DB、既存最新DBのすべてで
  **validate**/migrate が成功する」に対し、validate を実行しないことで満たしたことにしており、
  実質的な回帰である。
- **対応方法**:
  1. `validate-on-migrate: false` を削除する（既定 true へ戻す）。
  2. 旧V15/旧V37適用済みDBの救済は、validate 無効化ではなく次のいずれかで行う:
     - 開発DBなら DROP して空DBから再migrate（推奨・手順をREADMEかCLAUDE.mdに一行追記）。
     - 保持が必要なDBなら `flyway repair`（checksum 再整列）を1回実行する手順を明記する。
       Maven なら `org.flywaydb:flyway-maven-plugin:repair` をDB接続情報付きで実行。
  3. どうしても限定的に緩和が必要な場合のみ `spring.flyway.ignore-migration-patterns` を
     **対象バージョン限定**で使い、全体の validate は殺さない。
- **期待結果**: 全環境で validate が有効なまま、空DB・V9 baseline・既存最新DBの起動が成功する。
- **必要テスト**: `FlywayMigrationSmokeTest`（Testcontainers）が validate 有効で完走すること。
  可能なら「旧V15適用済み相当のDB → repair → migrate 成功」の手動検証記録を残す。

### RC-02 【中高・R3R-35残】請求・勤怠への部分的スコープガードと要件の矛盾が未解消

- **場所**:
  - `src/main/java/com/ses/controller/api/InvoiceApiController.java` —
    一覧 `list()`（63-89行）は**スコープ未適用（全件返却）**のまま、
    `assertInvoiceVisible`（55-61行）が detail/PDF/status/void/payments/reminder 系
    （98-218行の各エンドポイント）に適用され、`generate`（93行）も
    `assertAllowedCustomer` で拒否する。
  - `src/main/java/com/ses/controller/api/WorkRecordApiController.java` —
    勤怠グリッド `getGrid()`（38-41行）は**スコープ未適用**のまま、
    `saveHours`（45-47行）・`approve`/`reject`/`daily`/`report.pdf`（75-105行）は
    `allowedContractIds` で404にする。
  - `.kiro/specs/data-scope-permission/requirements.md:28` — 「適用対象外（全営業共通で閲覧可）:
    …**請求/BP支払（管理部業務でありメニュー権限で制御済み）・勤怠グリッド（同）**…」と明記。
    この行は現在も未改訂（git上、spec作成時から変更なし）。
- **事象・影響**: R3R-35 が指摘した「一覧は全件・詳細/更新は404」の非対称が**そのまま残っている**。
  scope ON の営業は請求書一覧・勤怠グリッドに担当外の行が見えるのに、行を開く/操作すると404になり、
  画面としては壊れて見える。かつ requirements とも矛盾したままである。
  なお PR #29 で修正されたのは BP支払更新の認可バグ（BP支払IDを請求IDとして検証していた件。
  現在は `InvoiceApiController.java:255-257` のコメントどおり BP支払をスコープ対象外へ戻して解消）
  のみで、非対称と仕様矛盾は未着手。
- **対応方法**（どちらかに確定する。**requirements の改訂を先に行う**こと）:
  - **案A（requirements 準拠・推奨）**: 請求・勤怠の全エンドポイントからスコープガードを除去する。
    `InvoiceApiController` の `assertInvoiceVisible` 呼び出し全箇所と `generate` の
    `assertAllowedCustomer`、`WorkRecordApiController` の `assertWorkRecordVisible` 呼び出し全箇所と
    `saveHours` 内の allowedContractIds 判定を削除。requirements 28行目はそのまま。
  - **案B（スコープ対象に含める）**: requirements 28行目から請求・勤怠を外し、
    一覧（`list()` の QueryWrapper に `customer_id IN allowedCustomerIds`、
    `getGrid()` の結果を `allowedContractIds` でフィルタ）まで一貫適用する。
    エイジング・Excel出力・一括督促の対象絞り込みも同時に揃えること。
- **期待結果**: scope ON/OFF いずれでも「一覧に見える行は必ず開ける・操作できる」が成立し、
  requirements の適用対象表と実装が一致する。
- **必要テスト**: scope ON/OFF × 管理者/営業 の4象限で、請求一覧→詳細→状態変更、
  勤怠グリッド→承認/差戻し が一覧の可視範囲と同一集合で成功/404になることを統合テストで確認。

### RC-03 【中・R3R-20残】一括督促のフロントエンドUIが未実装（APIのみ存在）

- **場所**: バックエンドは完了 — `InvoiceApiController.java:208-220`
  （`POST /api/invoices/reminders`、`BulkReminderRequest`）、
  `InvoiceServiceImpl.sendReminders`（548行〜: 行単位の検証・例外分離・
  `BulkReminderRowResult(invoiceId, status, reason, deliveryId)` の集約は実装済み）。
  フロントエンドは未実装 — `static/js/modules/invoice.js` および `templates/invoice/list.html` に
  一括送信の導線が一切ない（`invoices/reminders` への参照0件、行選択チェックボックスなし、
  結果集計モーダルなし。存在するのは単発督促モーダル `#reminderModal` のみ）。
- **事象・影響**: R3R-20 の対応方法のうち「選択UI、集計結果modalを追加する」が未実施。
  利用者は一括督促機能を使えず、実装済みのバックエンド・テストが死蔵されている。
- **対応方法**:
  1. `templates/invoice/list.html` の請求書一覧テーブルに行選択チェックボックス＋ヘッダの全選択、
     ツールバーに「一括督促」ボタンを追加（期限超過タブ/フィルタ `overdue=true` との併用を想定）。
  2. `invoice.js` に選択IDs収集→テンプレート選択（既存 `#reminderModal` を流用可）→
     `POST /api/invoices/reminders`（body: `{invoiceIds, templateId, asOf}`）→
     応答の `BulkReminderRowResult[]` を SENT/SKIPPED/FAILED 件数＋行別理由の結果モーダルで表示。
  3. 文言は i18n 4ファイルへキー追加（既存規則: キー追加のみ・既存行変更禁止）。
- **期待結果**: 混在バッチ（正常・email未設定・入金済・期限内）を選択送信すると、
  全行が処理され請求書単位の結果が画面で確認できる。
- **必要テスト**: `JsSyntaxCheckTest` 通過に加え、ブラウザで上記混在バッチの結果表示を確認。
  既存の `InvoiceServiceImpl` 側テストは流用可。

### RC-04 【中・R3R-23残】請求書別督促履歴のUIが未接続（APIのみ存在）

- **場所**: バックエンドは完了 — `InvoiceApiController.java:200-203`
  （`GET /api/invoices/{id}/reminders`、`assertInvoiceVisible` 付き）、
  `InvoiceServiceImpl.listReminders`。フロントエンドは未接続 —
  `invoice.js`・`templates/invoice/list.html` に本APIの呼び出し・表示箇所がない
  （list.html の「履歴」は入金履歴 `#paymentModal` のみ）。
- **事象・影響**: R3R-23 の「詳細/modalへ宛先、件名、status、日時、失敗理由を表示する」が未実施。
  督促を送った事実・結果を利用者が画面で確認できない。
- **対応方法**: 請求書行の操作列または督促モーダル `#reminderModal` 内に「督促履歴」タブ/ボタンを追加し、
  `GET /api/invoices/{id}/reminders` の結果（宛先・件名・status(SENT/FAILED/DRY_RUN)・送信日時・
  失敗理由）をテーブル表示する。RC-03 の結果モーダルと部品を共通化してよい。
- **期待結果**: 請求書単位で過去の督促送信履歴と結果を画面から確認できる。
- **必要テスト**: 履歴あり/なし、FAILED理由の表示、他請求書の履歴が混入しないことをブラウザ確認。

### RC-05 【中・R3R-24残】見積の備考追記UIが未接続で、受注/失注後は備考を一切変更できない

- **場所**: バックエンドは完了 — `QuotationApiController.java:158`（`POST /api/quotations/{id}/remarks`）、
  `QuotationServiceImpl.appendRemark`（148-161行: 行ロック内追記で並行喪失なし）。
  さらに `updateWithBusinessRules`（118-120行）が受注/失注後の通常updateを400で拒否する。
  フロントエンドは未接続 — `quotation.js` に `/remarks` への参照が0件、
  `templates/quotation/list.html` にも追記UIなし。
- **事象・影響**: R3R-24 の「専用『備考追記』UIを追加する」が未実施。終端（受注/失注）見積は
  通常保存がサーバで正しく拒否されるようになった結果、**画面からは備考を追記する手段が完全に無い**
  （モーダルで備考を書いて保存 → `error.quotation.terminalUpdate` で失敗する、が現状の挙動）。
- **対応方法**: 見積詳細モーダルに「備考追記」欄＋ボタンを追加し、終端状態の見積では
  通常フォームを読み取り専用にして追記欄のみ活性化する。
  `POST /api/quotations/{id}/remarks`（body: `{additional}`）を呼び、成功後に一覧を再読込。
  空白のみは送信前にブロック（サーバ側は既に blank 拒否あり）。
- **期待結果**: 受注/失注後も画面から備考を追記でき、既存備考・他項目は変わらない。
- **必要テスト**: 終端見積での追記成功・通常フォームの読み取り専用化・空白拒否をブラウザ確認。
  サービス層の並行追記テストは既存を流用。

### RC-06 【中・R3R-36残】統合・並行・ブラウザ検査のテスト基盤が未整備

- **場所・現状**:
  - 実施済み: `src/test/java/com/ses/web/JsSyntaxCheckTest.java`（全JSのnode --check、node無しはskip）、
    `FlywayMigrationSmokeTest` の assert 強化（invoice_id/recipient_user_id/reject_comment/精度の検証あり）、
    H2 schema 同期（`engineer-schema-h2.sql:128,254,257`、`schema-self-service-h2.sql:30-31`）。
    CI は `.github/workflows/ci.yml` の ubuntu-latest で `mvn test` を実行しており、
    GitHub ホストランナーには Docker/Node が標準搭載のため、smoke・JS検査は**事実上**実行される。
  - 未実施:
    1. **データスコープの実エンドポイント統合テスト** — `DataScopeServiceImplTest` は集合計算の
       ユニットテスト5件のみ。R3R-31/32 の「営業2ユーザーで各エンドポイントを1対1に検証」する
       `@SpringBootTest`+MockMvc の担当内成功/担当外404/拒否後DB不変マトリクスが存在しない
       （現状の各 `*ApiControllerTest` はモックスライス）。
    2. **実DB並行テスト** — `src/test/java` に CountDownLatch/CyclicBarrier/ExecutorService を使う
       テストが0件。R3R-05/10/18/25/29 で導入したロック/CASの実効性
       （save対confirm、承認対差戻し、入金対取消、受注対失注、通常PUT対単価同期）が
       自動検証されていない。Testcontainers MySQL 上で latch 同期の2スレッド実行を追加する。
    3. **ブラウザsmoke** — 主要画面（月次締め5カード、システム設定、マイ勤怠、請求エイジング）の
       表示・操作確認が手動のまま。最低限、Selenium/Playwright いずれかで
       「ログイン→各画面表示→JSエラー0」の smoke を用意するか、恒常的に手動Demo手順を
       spec の tasks に明記する。
    4. **CIのskip検知** — smoke/JS検査は「環境に無ければskip」設計のため、ランナー構成が変わると
       黙って skip される。CI に surefire レポートから該当テストの skipped=0 を assert するステップ
       （または `-Dsurefire.failIfNoSpecifiedTests` 相当のガード）を追加する。
- **期待結果**: IDOR・競合不整合・画面全停止の各クラスの不具合が、環境差で黙殺されずに
  merge 前のCIで自動検出される。
- **必要テスト**: 上記1・2の新テスト自体。3は smoke 1本または手動Demo手順の明文化。

---

## 3. 対応済みを確認した項目（32件・確認根拠つき）

| 指摘 | 確認内容（現mainでの根拠） |
|---|---|
| R3R-01 | 重複V15を解消（`V15__add_webhook_config.sql` のみ残存）、`V38__add_invoice_id_to_mail_delivery.sql` で追加。smoke test が列・索引をassert |
| R3R-02 | V36が唯一の `recipient_user_id` 追加、`V37__c14_c15.sql` は `reject_comment` のみ。H2 2系統へ同期済み |
| R3R-03 | `monthly-closing.js` は正しい template literal に修正済み（構文正常・全機能実装） |
| R3R-04 | `system-config.js:80` に `function unitNoteFor(key)` 復元済み |
| R3R-05 | `MonthlyClosingService.assertOpenForUpdate` を新設し、`WorkRecordServiceImpl:83`・`InvoiceServiceImpl:77` から同一config行を FOR UPDATE |
| R3R-06 | 読取含め `loadRecordsFromJson(..., true)` で fail-closed（`MonthlyClosingServiceImpl:212,221`） |
| R3R-07 | confirm はロック後に既存月なら no-op（`MonthlyClosingServiceImpl:175-177`） |
| R3R-08 | `MonthlyClosingPageController` が `canConfirm/canReopen` をモデル注入、`list.html:19-20` で th:if、JSはnullガード |
| R3R-09 | NULL金額を0集計（`MonthlyClosingServiceImpl:117-119`） |
| R3R-10 | submit/approve/reject とも条件付きUPDATE（CAS）＋0件時409（`WorkRecordServiceImpl:495-501,522-527,556-562`） |
| R3R-11 | reject は `resolveEngineerUserId` → `publishToUser`、未紐付けは警告ログのみ（`WorkRecordServiceImpl:564-578`） |
| R3R-12 | `reject_comment` 列＋Entity/DTO/API反映、trim必須・500字上限、再提出時クリア |
| R3R-13 | `V39__unify_work_hour_precision.sql` で DECIMAL(6,2) 統一、H2同期済み、`work-record.js:47` step=0.01 |
| R3R-14 | 保存許可は「入力中」「差戻し」のみ（`WorkRecordServiceImpl:113-119`） |
| R3R-15 | 0h提出も `saveHoursInternal`（期間・状態検証つき）経由、年月形式は400へ（`MyTimesheetApiController:89`、`WorkRecordServiceImpl:479-482`） |
| R3R-16 | `my-timesheet.js` は inline onclick 廃止、`data-*`＋addEventListener 方式 |
| R3R-17 | 未紐付け403/不存在404/所有者不一致403（`MyTimesheetApiController:55-75`）、`BusinessException` 既定400、`GlobalExceptionHandler.toHttpStatus` でHTTP status一致 |
| R3R-18 | changeStatus/void/payments とも FOR UPDATE、取消は入金行あり or 入金済で拒否（`InvoiceServiceImpl:484-493` 他） |
| R3R-19 | `TemplateRenderer` が snake↔camel 相互解決、`V40__seed_dunning_template.sql` で督促テンプレをシード、bulk側も残高・経過日数をparams生成 |
| R3R-21 | `SES.toast.success/error` 呼び出しへ統一（`invoice.js:120,127` 他） |
| R3R-22 | `GET /api/invoices/aging/detail`（`InvoiceApiController:161`）＋ `invoice.js showAgingDetail` のモーダル表示 |
| R3R-25 | `selectByIdForUpdate` による行ロックで update/changeStatus/createDraft を直列化（`QuotationServiceImpl:113,132,175`） |
| R3R-26 | create は status を無条件「下書き」、DTOに `@Digits/@Size` 等（`QuotationSaveRequest`）、service側validateも維持 |
| R3R-27 | 状態遷移成功直後に一覧 reload（`quotation.js:238` 付近） |
| R3R-28 | `?openId=` 導線＋scope検証済み詳細APIでモーダル表示（`quotation.js:23-27`） |
| R3R-29 | `ContractMapper.selectByIdForUpdate`/`updatePriceOnly` を新設し、通常更新は単価列除外（`ContractServiceImpl:168,180-181`）、同期・改定は単価のみ部分UPDATE |
| R3R-30 | モーダル表示・切替時に `#priceRevWarning` を初期化（`contract-price-revision.js:33-34`） |
| R3R-31 | skill/担当営業/書類/CSV/autocomplete/check-active に `assertAllowed*` または allowed集合注入（各Controller、計113箇所） |
| R3R-32 | update/delete は既存親リソースを先に認可（`EngineerSkillApiController:31`、`ContractDocumentApiController:29,53,63` 等） |
| R3R-33 | draft/停滞/bench/フォロー等を `publishToUser` 化、dedupe keyへ `#u{userId}` 付与（`NotificationServiceImpl:138-140`） |
| R3R-34 | `computeCustomerIds` の契約由来条件を `sales_user_id=userId` のみに分離（`DataScopeServiceImpl:181-190`） |
| R3R-37 | `patch*.py`/`patch_invoice.java`/`resolve.py` はリポジトリから削除済み |

## 4. 検証方法の記録と限界

- 本再検証は**静的なコード突合**（該当ファイルの読解・全文grep・migrationファイル実体確認）による。
  `mvn test`・Docker smoke・実ブラウザ操作は本検証内では実行していない。
- JS構文は node 不在環境のため機械検査できず、旧障害2ファイル（monthly-closing.js /
  system-config.js）は目視で全文確認した。CI（ubuntu-latest, node同梱）では `JsSyntaxCheckTest` が実効。
- 並行系（R3R-05/10/18/25/29）は「ロック・CASが実装されていること」までの確認であり、
  実DBでの競合再現テストは存在しない（RC-06-2 参照）。

## 5. 完了判定条件（残件クローズの基準）

1. RC-01: `validate-on-migrate: false` が削除され、smoke test（validate有効）が完走する。
2. RC-02: requirements と実装のスコープ適用表が一致し、4象限統合テストが緑。
3. RC-03/04/05: 各UI導線が実装され、ブラウザ確認記録（またはsmokeテスト）が残る。
4. RC-06: スコープ統合テスト・並行テストが追加され CI で常時実行、skip検知が入る。
5. 全対応後、`mvn test` 全緑＋CI（Docker/Node同梱）での smoke・JS検査の実行実績を本ファイル末尾へ追記する。
