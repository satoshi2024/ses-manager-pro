# 第3回全面不具合対応後レビュー

- 作成日: 2026-07-19
- レビュー対象: `main` / `95cbf6a` までの第3回対応
- 参照資料: `README.md`、`bug-hunt-round3.md`、関連 requirements / design / tests

注記: 初回確認は `841750c` を基準に実施した。レビュー中に `95cbf6a` が追加されたため、`841750c..95cbf6a` の差分を全指摘へ再照合し、37件すべてが引き続き有効であることを確認した。

## 1. 結論

第3回対応は **「全件対応済み」とは判定できない**。静的解析、実装と仕様の突合、対象テストの確認により、**37件**の指摘を確認した。

- 致命的: 3件
- 高: 20件
- 中高: 4件
- 中: 7件
- 低中: 1件
- 低: 2件

特に、次の4点は他の確認より先に対応する必要がある。

1. Flyway migration version重複および実行順序不正により、実DBの起動・更新が失敗する。
2. 通知関連migrationで同じ列と索引を二重追加しており、migrationが途中停止する。
3. 月次締め画面のJavaScriptが構文エラーで、画面機能が全停止する。
4. データスコープの読取漏れ・書込IDORが残り、担当外データの参照・更新が可能である。

`bug-hunt-round3.md` 末尾の「C1〜C62対応済み」「BUILD SUCCESS」を完了根拠として扱わず、本書の指摘対応と再検証が完了するまでステータスを再オープンすること。

## 2. 指摘一覧

### R3R-01 【致命的・C26】Flyway V15が重複し、作成前のテーブルをALTERしている

- **場所**: `V15__add_invoice_id_to_mail_delivery.sql:1-2`、`V15__add_webhook_config.sql:1-9`、`V26__create_mail_delivery.sql:1-15`
- **事象・影響**: version `15` のmigrationが2本あり、Flyway validateで失敗する。また、`invoice_id` 追加側はV26で作成される `t_mail_delivery` をV15でALTERしているため、version重複を解消しただけでも空DB migrationは失敗する。
- **対応方法**: `invoice_id` 追加を現在の最大versionより後ろの一意なmigrationへ移す。空DB用のV26 CREATEにも列と索引を反映する。既に適用済みmigrationのchecksumを変更できない環境では、新規補正migrationだけでupgradeできる構成にする。
- **期待結果**: 空DB、V9 baseline DB、既存最新DBのすべてでvalidate/migrateが成功し、`invoice_id` と索引が一度だけ存在する。
- **必要テスト**: Docker/MySQLで空DB migration smoke、V9 baselineからのupgrade、列・索引数のassertを実行する。

### R3R-02 【致命的・C14/C15】V36とV37が通知宛先列・索引を二重追加している

- **場所**: `V36__notification_recipient_and_scope.sql:2-3`、`V37__c14_c15.sql:2-5`、`src/test/resources/application-test.yml:11-29`、H2用schema
- **事象・影響**: V36で追加済みの `recipient_user_id` と `idx_notification_recipient` をV37が再追加するため、V37でmigrationが停止する。通常test profileはV36/V37を再生せず、H2 schemaにも `recipient_user_id` / `reject_comment` が不足しているため、通常テストでは本番不整合が隠れる。
- **対応方法**: V36を通知宛先追加の唯一のmigrationとし、V37は `reject_comment` だけを追加する。V4/V5の初回CREATE、MySQL migration、`schema-self-service-h2.sql`、`engineer-schema-h2.sql` を同一構造へ同期する。
- **期待結果**: MySQL/H2とも両列が一度だけ存在し、通知・勤怠Mapperが同一schemaで動作する。
- **必要テスト**: MySQL migration smokeで列・索引数を確認し、`@SpringBootTest` で通知INSERT/一覧と勤怠グリッドを実クエリする。

### R3R-03 【致命的・P3回帰】月次締め画面のJavaScriptが構文エラーで全停止する

- **場所**: `src/main/resources/static/js/modules/monthly-closing.js:33-40,80-122`
- **事象・影響**: `div.innerHTML = <div ...>`、配列内の `<a ...>`、`let html = <h5>...` など、HTML断片の引用符/バッククォートが欠落している。最初の `<` でparseが停止し、カード、明細、締め完了、締め解除の全機能が動かない。
- **対応方法**: HTML断片を正しいtemplate literalへ戻すか、DOM APIで安全に生成する。カードクリックへ実際の `titleKey` を渡し、URL値はencodeする。
- **期待結果**: 5カードが表示され、各明細・リンクを開け、confirm/reopen後に再描画される。
- **必要テスト**: 全JS moduleへの構文検査をCIへ追加し、ブラウザで5カード、各明細、confirm/reopenを確認する。

### R3R-04 【高・P5/C52回帰】システム設定画面のJavaScriptも構文エラーで全停止する

- **場所**: `src/main/resources/static/js/modules/system-config.js:79-84`
- **事象・影響**: `unitNoteFor(key)` の関数宣言が欠落し、トップレベルに `return` と余分な `}` が残っている。`Illegal return statement` となり、設定一覧の読込・保存がすべて停止する。
- **対応方法**: `function unitNoteFor(key) { ... }` を復元し、勝率設定の分岐を関数内へ戻す。
- **期待結果**: 設定一覧が正常表示され、各単位注記と保存が動作する。
- **必要テスト**: JS構文検査と `/system-config` のロード・保存ブラウザテストを追加する。

### R3R-05 【高・P3】締め処理と工数・請求変更が同じロックを使用していない

- **場所**: `MonthlyClosingServiceImpl.java:164-177`、`WorkRecordServiceImpl.java:79-82,97-99`、`InvoiceServiceImpl.java:74-77,450-455`
- **事象・影響**: confirmはsummary計算後に設定行をlockする一方、工数保存・請求取消は通常の `isClosed()` 読取だけである。並行実行により、締め成立後に工数や請求差分がcommitされ、「締め済みだが残件あり」を作れる。
- **対応方法**: `assertOpenForUpdate(month)` のような共通guardを作り、保護対象更新とconfirmの双方が同じconfig行を最初にlockする。confirmはlock取得後にsummaryを再計算する。
- **期待結果**: confirmと対象月更新は直列化され、締め成立後の変更が発生しない。
- **必要テスト**: 実MySQL＋latchでsave対confirm、void対confirm、reopen対更新を検証する。

### R3R-06 【高・C29残存】破損した締めJSONを未締めとして扱い、ハードロックがfail-openになる

- **場所**: `MonthlyClosingServiceImpl.java:199-216`
- **事象・影響**: 読取側の `loadRecordsFromJson(..., false)` は解析失敗時に空配列を返す。`closing.confirmed-months` が破損するとsummary/isClosedが未締め扱いとなり、更新を許可する。
- **対応方法**: 読取を含むJSON解析失敗を専用業務エラーにし、少なくとも更新guardはfail-closedにする。復旧手順と監視ログも定義する。
- **期待結果**: 破損時に締め状態を推測で解除せず、復旧まで対象月の更新を拒否する。
- **必要テスト**: 破損JSONでsummary/isClosed/saveHours/voidがエラーとなり、DB更新がないことを確認する。

### R3R-07 【中・C28残存】同月confirmが冪等でなく、実行者と日時を上書きする

- **場所**: `MonthlyClosingServiceImpl.java:173-177`
- **事象・影響**: 既存月をremoveして現在ユーザー・現在時刻で追加し直すため、API再送だけで最初の実行証跡が失われる。
- **対応方法**: lock後に対象月が存在する場合はno-opとし、既存recordをそのまま保持する。
- **期待結果**: 同一月を再送してもJSON、締め実行者、締め日時が変わらない。
- **必要テスト**: 同月2回confirm後の保存JSON完全一致を確認する。

### R3R-08 【中高・C30残存】HRにも締め操作ボタンが表示され、権限仕様も矛盾している

- **場所**: `templates/monthly-closing/list.html:19-20`、`monthly-closing.js:53-64`、`monthly-closing-checklist/requirements.md:29,35`
- **事象・影響**: API側403はあるが画面にrole条件がなく、HRにも実行可能に見えるボタンが表示される。また、requirements内で解除可能roleの記述が一致していない。
- **対応方法**: DTOまたはpage modelから `canConfirm/canReopen` を返して非表示/disabledを制御し、採用するrole方針へrequirements/design/testsを統一する。
- **期待結果**: HRには実行不能な操作が表示されず、仕様書と実装の権限表が一致する。
- **必要テスト**: 管理者・マネージャー・HRごとにAPI statusとボタン表示を確認する。

### R3R-09 【中・P3】未請求小計がNULL金額で500になる

- **場所**: `MonthlyClosingServiceImpl.java:105-118`
- **事象・影響**: `subtotal.add(item.getBillingAmount())` にNULL guardがなく、旧データ等にNULLがあるとsummaryがNPEで停止する。
- **対応方法**: NULLを0として集計するか、確定時の非NULL保証と既存データ補正を行う。
- **期待結果**: NULL金額を含む既存データでもsummaryが500にならず、定義どおり集計される。
- **必要テスト**: NULL金額を含む顧客groupの小計・総件数を検証する。

### R3R-10 【高・C8】勤怠の承認・差戻し競合対策が未実装

- **場所**: `WorkRecordServiceImpl.java:515-545`
- **事象・影響**: approve/rejectが同じ「提出済」を読んだ後に無条件 `updateById` を行う。承認でBP支払生成後に差戻しが状態を上書きし、「差戻し＋BP支払あり」が残り得る。
- **対応方法**: `WHERE id=? AND status='提出済'` の条件付きUPDATEへ変更し、更新件数1の処理だけ後続処理を行う。0件は409とし、submitも同じCAS方式に統一する。
- **期待結果**: 同時操作は一方のみ成功し、状態とBP支払が常に整合する。
- **必要テスト**: 実MySQLで承認対差戻し、承認対承認、差戻し対差戻しを並行実行する。

### R3R-11 【高・C14】差戻し通知が対象要員だけでなく全要員へ配信される

- **場所**: `WorkRecordServiceImpl.java:537-552`、`NotificationServiceImpl.java:115-116,129-140`
- **事象・影響**: 差戻し処理が `publishToUser` でなく `publish` を使用し、`recipient_user_id=null` になる。他要員の差戻し内容が通知一覧・未読数へ混入する。
- **対応方法**: 契約のengineerIdから紐付くuserIdを解決し、本人宛に `publishToUser` する。紐付けなしの場合は全体配信せず警告ログとする。
- **期待結果**: 要員Aの差戻し通知はAだけが参照・既読化できる。
- **必要テスト**: 要員A/Bで一覧、未読数、個別既読、一括既読をMapper統合テストする。

### R3R-12 【高・C15】差戻しコメントを保存・返却せず、空コメントも受理する

- **場所**: `WorkRecordApiController.java:82-110`、`WorkRecordServiceImpl.java:537-545`、`WorkRecord.java`、`WorkRecordGridDto.java`、`MyTimesheetApiController.java:85-102`、`my-timesheet.js:45-46`
- **事象・影響**: `comment` を保存せずEntity/DTOにも `rejectComment` がない。要員画面は業務備考 `remarks` を差戻し理由として表示し、空白や500文字超も検証しない。
- **対応方法**: Entity/DTO/API responseへ `rejectComment` を追加し、trim後必須・最大500文字を検証する。差戻し時に保存し、再提出時にclearする。画面は専用fieldだけを表示する。
- **期待結果**: 実際の差戻し理由が表示され、業務備考は変わらず、空コメントは400になる。
- **必要テスト**: 保存・表示・備考不変・空白・501文字・再提出時clearを検証する。

### R3R-13 【高・C5】日次2桁と月次1桁の工数精度差が残る

- **場所**: `V5__create_work_record_billing.sql:5`、`V32__engineer_self_service.sql:23-35`、`engineer-schema-h2.sql:254`、`work-record.js:44-49`
- **事象・影響**: 日次は `DECIMAL(4,2)`、月次は `DECIMAL(5,1)`、管理画面は `step=0.1` のままである。7.25hが再取得時に7.3hとなり、表示と精算計算の基準が分裂する。
- **対応方法**: 月次を `DECIMAL(6,2)` 以上へ変更する新規migrationを追加し、V5、H2 schema、input step、表示桁を2桁へ統一する。
- **期待結果**: 日次合計、保存月次工数、請求・支払計算が同じ値になる。
- **必要テスト**: 7.25h、7.33h、100h超の保存・再取得と金額計算をDB統合テストする。

### R3R-14 【高・C9】提出済み実績を管理画面から編集できる

- **場所**: `WorkRecordServiceImpl.java:106-114`、`work-record.js:43-55`
- **事象・影響**: service/UIとも「確定」だけを編集不可にしており、提出済みの工数、備考、請求額、支払額を承認前に変更できる。
- **対応方法**: 保存許可状態を「入力中」「差戻し」のみに限定し、serverとUIで同じ判定を使用する。
- **期待結果**: 提出後は不変となり、修正は差戻し後だけ可能になる。
- **必要テスト**: 入力中・差戻し・提出済・確定の4状態について月次/日次保存可否を確認する。

### R3R-15 【高・C4/C6】ゼロ工数提出APIが契約期間・状態検証を迂回する

- **場所**: `MyTimesheetApiController.java:142-147,189-203`、`WorkRecordServiceImpl.java:339-373,478-490`
- **事象・影響**: 所有契約IDへ `workMonth=2099-01` 等を直接送ると、期間外、準備中、解約契約でも0h提出を作成できる。不正年月形式は500になり得る。
- **対応方法**: 日次保存と0h提出で年月形式、契約状態、契約期間重複を検証する共通methodを使用する。0hも検証済み内部保存経路を通し、年月・備考へ入力制約を付ける。
- **期待結果**: 画面gridに表示される対象契約・対象月だけ提出でき、不正入力は400になる。
- **必要テスト**: 期間前後、準備中、解約、形式不正、正常0h提出をController＋Serviceで検証する。

### R3R-16 【高・新規回帰】マイ勤怠の提出ボタンに保存型XSSがある

- **場所**: `src/main/resources/static/js/modules/my-timesheet.js:68`
- **事象・影響**: `JSON.stringify(row)` を単一引用符のinline `onclick` へ未escapeで埋め込む。案件名・備考等の保存値で属性を脱出し、要員session上で任意JavaScriptを実行できる。
- **対応方法**: inline handlerを廃止し、buttonには数値IDだけを `data-*` で保持する。行dataはJS Mapから参照し、event listenerで処理する。表示文字列はtext nodeで描画する。
- **期待結果**: 引用符やHTMLを含む値も属性・scriptとして解釈されない。
- **必要テスト**: 引用符、HTML、event属性を含む案件名/備考でDOMとclick処理をブラウザテストする。

### R3R-17 【高・C7】未紐付け・他要員アクセスが500になる

- **場所**: `MyTimesheetApiController.java:55-76`、`BusinessException.java:54-59`
- **事象・影響**: `BusinessException.of("error.my.notLinked")` が既定code=500となり、認可違反がsystem障害として処理される。
- **対応方法**: 未紐付け/所有者不一致を403、純粋な不存在を404へ統一し、HTTP statusとJSON codeを一致させる。
- **期待結果**: 認可違反が500監視へ混入せず、clientが適切に処理できる。
- **必要テスト**: 未紐付け、他要員、自分、不存在IDのHTTP status/JSON codeを確認する。

### R3R-18 【高・C16/C18】請求書lockが取消・手動状態変更へ適用されていない

- **場所**: `InvoiceServiceImpl.java:173-187,196-250,450-461`
- **事象・影響**: add/delete paymentは行lockするが、changeStatus/voidInvoiceは同じlockを取得しない。入金行があるのに最終状態が未送付、または入金追加と取消が両方成立する可能性がある。「入金済＋入金行0件」のlegacy dataも取消可能である。
- **対応方法**: changeStatus/voidInvoiceもtransaction化し、最初に同じ請求書行を `FOR UPDATE` する。取消は入金行あり、またはstatus=入金済のどちらでも拒否する。
- **期待結果**: 同一請求書の全更新が直列化され、完済/入金あり請求書は取消できない。
- **必要テスト**: legacy完済取消、入金対取消、入金対手動状態変更を実MySQLで並行検証する。

### R3R-19 【高・C22】督促templateの記法・変数・初期dataが一致していない

- **場所**: `TemplateRenderer.java:12-28`、`V2__init_master_data.sql:126-176`、`templates/email-template/list.html:75-82`、`InvoiceServiceImpl.java:414-422,529-535`
- **事象・影響**: rendererは `{{camelCase}}`、seed/UI案内は `{snake_case}` であり、差込文字が未置換のまま送信される。督促専用seedがなく、bulk側は残高と経過日数もparamsへ渡していない。
- **対応方法**: 後方互換を考慮して記法とkey命名を一本化し、seed/UI/rendererを同期する。督促templateをseedし、単発/bulkで共通の必須変数生成methodを使う。
- **期待結果**: 顧客名、請求番号、請求額、残高、期限、経過日数が初期DBでも正しく展開される。
- **必要テスト**: 初期seedを実際にrenderし、未置換tokenが残らないことを確認する。

### R3R-20 【高・C27】一括督促が行単位継続・結果集約になっておらず、UIもない

- **場所**: `InvoiceApiController.java:190-205`、`InvoiceServiceImpl.java:514-537`、`MailDispatchResult.java`、invoice画面
- **事象・影響**: 顧客なしでNPE、emailなしで例外となり、loop全体が500で停止する。結果にinvoiceId/reasonがなく行との対応もできず、frontendからbulk APIを呼ぶ導線もない。
- **対応方法**: invoiceId/status/reason/deliveryIdを持つ行別result DTOを作る。各行を独立検証・例外処理し、skip後も継続する。request検証、scope検証、選択UI、集計結果modalを追加する。
- **期待結果**: 混在batchでも全行を処理し、送信/失敗/skipを請求書単位で確認できる。
- **必要テスト**: 正常、emailなし、顧客なし、期限内、担当外を混在させ、後続継続と結果対応を確認する。

### R3R-21 【中高・C21】Toast APIを誤って関数呼出しし、成功後処理が停止する

- **場所**: `common.js:128-132,459`、`invoice.js:119-130,405-411`
- **事象・影響**: `SES.toast` はmethodを持つobjectだが、`SES.toast(message,type)` として呼ばれてTypeErrorになる。督促結果が表示されず、請求取消後は一覧reload前に停止する。
- **対応方法**: `SES.toast.success/error` または既存 `Toast.success/error` に統一する。
- **期待結果**: API結果に応じた通知が表示され、取消後に一覧が更新される。
- **必要テスト**: 督促SENT/DRY_RUN/FAILED、取消成功/業務errorをブラウザテストする。

### R3R-22 【中・C25】エイジングdrill-downが404で、選択bucketの明細も表示しない

- **場所**: `invoice.js:334-365`、`InvoicePageController.java:10-22`
- **事象・影響**: link先 `/invoice/list` にmappingがない。`/invoice` へ直すだけでもcustomerId/bucket/asOfを読まず、cell金額を構成する請求書を特定できない。
- **対応方法**: `customerId + asOf + bucket` を受ける明細APIを追加し、同一tab内または専用modalへ対象請求番号、残高、経過日数を表示する。
- **期待結果**: 各cellから、そのcell合計を構成する請求書一覧へ正確に遷移できる。
- **必要テスト**: 30/31/60/61/90/91日境界でcell合計と明細合計を照合する。

### R3R-23 【中・C26】請求書別督促履歴がServiceだけでAPI/UIに未接続

- **場所**: `InvoiceServiceImpl.java:386-389`、`InvoiceApiController.java:132-211`、`templates/invoice/list.html:190-211`
- **事象・影響**: `listReminders(invoiceId)` はあるがendpointと画面導線がなく、利用者が履歴を確認できない。
- **対応方法**: 可視性検証付き `GET /api/invoices/{id}/reminders` を追加し、詳細/modalへ宛先、件名、status、日時、失敗理由を表示する。
- **期待結果**: 請求書単位で過去の督促結果を確認できる。
- **必要テスト**: 他請求混入なし、担当外404、SENT/FAILED/DRY_RUN表示を検証する。

### R3R-24 【高・C48未完了】見積備考追記に画面導線がなく、並行追記で履歴を失う

- **場所**: `QuotationApiController.java:150-156`、`QuotationServiceImpl.java:112-123`、`quotation.js`、`QuotationServiceImplTest.java:154-170`
- **事象・影響**: 追記APIは非transactionのread→結合→updateで、同時追記は後勝ちとなる。画面から `/remarks` を呼ぶ導線もなく、終端見積では通常保存も拒否される。既存testはなお上書きを正としている。
- **対応方法**: 追記をserviceのtransactionへ移し、行lock/version付きで最新値へ追加する。終端状態の通常updateはserviceでも拒否し、専用「備考追記」UIを追加する。
- **期待結果**: 受注/失注後も画面から追記でき、既存備考と並行追記を失わない。
- **必要テスト**: 2回追記、blank拒否、並行追記、他項目不変、画面導線を確認する。

### R3R-25 【高・P4】見積状態遷移と編集がread-check-update競合になる

- **場所**: `QuotationServiceImpl.java:112-145`
- **事象・影響**: row lock/version/条件付きUPDATEがなく、提出済から受注/失注の並行要求が両方検証を通る。通常編集も終端化と競合し、受注後に金額等を上書きし得る。
- **対応方法**: `UPDATE ... WHERE id=? AND status=?` の更新件数判定、またはtransaction内 `SELECT ... FOR UPDATE` へ統一する。通常編集もold statusを更新条件に含める。
- **期待結果**: 競合時は片方だけ成功し、終端化後に金額・顧客・要員を変更できない。
- **必要テスト**: 受注対失注、編集対受注を2 threadで検証する。

### R3R-26 【中・C37/C47残存】見積作成時statusと数値・文字数制約がserviceで保証されない

- **場所**: `QuotationServiceImpl.java:91-95`、`QuotationSaveRequest.java:25-32`、`V29__quotation.sql:14-20`
- **事象・影響**: statusが空のときだけ下書きにするため、内部経路から受注等を指定できる。単価の桁数、小数、精算幅非負、備考500文字のvalidationがなく、DB丸めまたは500になり得る。
- **対応方法**: createではstatusを無条件に下書きへ設定する。DTOへ `@Digits`、非負制約、`@Size(max=500)` を追加し、serviceにも同じ不変条件を置く。
- **期待結果**: 全登録経路が下書き開始となり、不正値はDB到達前に400で拒否される。
- **必要テスト**: status注入、小数円、桁超過、負精算幅、500文字超を検証する。

### R3R-27 【中・C38残存】受注後のdraft通信失敗で再試行導線が現れない

- **場所**: `quotation.js:223-268`
- **事象・影響**: status PUT成功後にcreate-draftがnetwork rejectすると、catchはToastだけで一覧を再読込しない。画面は提出済のままで再試行buttonが出ず、再受注は遷移errorになる。
- **対応方法**: status成功直後、またはcreate-draftの `finally` で必ず一覧をreloadする。
- **期待結果**: draft生成失敗後も受注状態と再試行buttonが直ちに表示される。
- **必要テスト**: status成功→draft fetch rejectをmockし、reloadと再試行を確認する。

### R3R-28 【低中・C45部分対応】契約から元見積詳細を開けず、PDF直linkだけである

- **場所**: `contract.js:235-237`、`quotation.js:16-21`
- **事象・影響**: 契約modalのlinkはPDFのみで、round3の期待である元見積画面への導線と `openId` 処理がない。
- **対応方法**: `/quotation?openId={id}` へlinkし、scope検証済み詳細APIからmodalを開く。PDFは別buttonとして残す。
- **期待結果**: 元見積の番号、状態、金額、条件を画面上で参照できる。
- **必要テスト**: quotationId有無、担当内、担当外404、削除済みを確認する。

### R3R-29 【高・P6/C53/C54】通常契約更新と単価同期の競合で単価・他項目が巻き戻る

- **場所**: `ContractServiceImpl.java:160-175,402-411`、`ContractPriceSyncService.java:50-71`
- **事象・影響**: 通常更新と月替わり同期が取得済みの `Contract` 全体を `updateById` する。通常更新が同期後の新単価を旧値へ戻す、または同期側が最新備考等を古い値で上書きする可能性がある。同月改定のcheck-then-insertも原子的でない。
- **対応方法**: 通常更新SQLから単価列を除外し、同期/改定は単価列だけを部分UPDATEする。契約単位のrow lock/versionを導入し、lock取得後に履歴を再解決する。同月改定はatomic upsertにする。
- **期待結果**: 通常編集、単価改定、日次同期が並行しても対象月の最新単価と他契約項目が保持される。
- **必要テスト**: 実DB＋barrierで通常PUT対同期、改定対同期、同月改定同士を並行実行する。

### R3R-30 【低・P6/C58】遡及改定警告が別契約のmodalへ残る

- **場所**: `contract-price-revision.js:31-35,38-58,76-80`
- **事象・影響**: warning表示後にmodalを閉じて別契約を開くと、次の登録まで旧warningが残る。
- **対応方法**: modal open、契約切替、履歴load開始/失敗時に `#priceRevWarning` を必ず初期化する。
- **期待結果**: warningは当該操作で遡及影響が検出された場合だけ表示される。
- **必要テスト**: 契約A=true→close→契約B open、同一契約再表示、true→falseを確認する。

### R3R-31 【高・P7/C59】読取・CSV・子resourceのデータスコープ漏れが残る

- **場所**: `EngineerSkillApiController.java:20-28`、`ProjectSkillApiController.java:20-28`、`EngineerSalesApiController.java:25-59`、`CsvApiController.java:43-98`、`ContractApiController.java:135-138`、`ContractDocumentApiController.java:40-71`、`AutocompleteApiController.java:32-65`
- **事象・影響**: scope ONの営業Bが営業AのIDを直接指定すると、skill、担当営業履歴、契約書類、稼働有無等を取得できる。CSVとautocompleteも全件を返す。
- **対応方法**: 子APIは親IDを共通 `assertAllowed*` で検証する。document IDからcontract IDを解決して認可し、CSV/autocompleteのDB queryへallowed ID集合を適用する。`check-active` もengineer scopeを検証する。
- **期待結果**: 画面、直接URL、子resource、CSV、PDF、autocompleteの可視範囲が一致し、担当外IDは404になる。
- **必要テスト**: 営業2人の統合テストで各endpointを1対1に検証し、scope OFF/非営業も確認する。

### R3R-32 【高・P7/C61】担当外データへの書込IDORが複数残る

- **場所**: `EngineerSkillApiController.java:25-28`、`ProjectSkillApiController.java:25-28`、`EngineerSalesApiController.java:37-59`、`ContractDocumentApiController.java:46-66`、`ContractApiController.java:89-113`、`QuotationApiController.java:109-147`、`ProjectApiController.java:85-90`、`ProposalApiController.java:100-103`
- **事象・影響**: 担当外要員のskill/担当営業、担当外契約のdocument、担当外IDを参照する契約・見積・提案を変更できる。案件更新は既存案件を認可せずpayload customerだけを見るため、他人の案件を自分の顧客へ移せる。
- **対応方法**: update/deleteではpayloadより先にDB上の既存親resourceを認可する。create/関連変更ではcustomer/project/engineer等の全参照IDを検証し、子resourceの親認可と所属確認をservice境界へ集約する。拒否は404かつDB不変とする。
- **期待結果**: 担当外IDによる登録、更新、削除、状態操作、document送信がすべて拒否される。
- **必要テスト**: 各resourceで担当内成功、担当外404、拒否後DB不変を統合テストする。

### R3R-33 【高・P7/C60】個人通知化が発行元まで完了せず、dedupeも受信者を考慮しない

- **場所**: `ProposalServiceImpl.java:109-121`、`WorkRecordServiceImpl.java:537-552`、`NotificationGenerateService.java:104-145`、`NotificationServiceImpl.java:129-143`、通知migration
- **事象・影響**: 契約draft、勤怠差戻し、bench、提案停滞の一部がgeneric `publish()` のまま全体通知になる。`dedupe_key` も全体一意のため、同一eventを複数userへ個別発行すると2人目が破棄される。
- **対応方法**: draft.salesUserId、要員account、現任担当営業、proposedBy等を解決して `publishToUser` を使用する。NULL宛先は本当に全体通知のeventだけに限定し、dedupe keyまたはunique制約へrecipientを含める。
- **期待結果**: 各通知は対象本人だけに届き、複数受信者には各1件が生成される。
- **必要テスト**: 個人/全体、営業A/B、要員A/B、未読数、既読化、同一event複数受信者をMapper統合テストする。

### R3R-34 【中高・P7/C62】未帰属契約から顧客全体へ権限が拡大する

- **場所**: `DataScopeServiceImpl.java:181-190`
- **事象・影響**: customer ID計算が `sales_user_id=userId OR sales_user_id IS NULL` を使用し、未帰属契約が1件あるだけで無関係な営業にも顧客詳細、案件、活動、summary全体を開示する。
- **対応方法**: contract一覧では「自分＋未帰属」を維持しても、customer権限のcontract由来条件は `sales_user_id=userId` のみにする。未帰属contract一覧には必要最小限のcustomer名だけを返す。
- **期待結果**: 未帰属contract自体は確認できるが、それを理由にcustomer全体へアクセスできない。
- **必要テスト**: 自分・他人・未帰属contractを持つcustomerを分離した統合テストを追加する。

### R3R-35 【中高・P7回帰】仕様対象外の請求・勤怠へ部分的なscope制限を追加している

- **場所**: `InvoiceApiController.java:55-61,91-150,182-185,225-229`、`WorkRecordApiController.java:30-47,75-98`
- **事象・影響**: requirementsで対象外の請求/BP支払/管理勤怠にguardが一部だけ追加され、一覧は全件、詳細/更新は404という非対称になる。BP支払更新はBP支払IDを請求IDとして `assertInvoiceVisible(id)` へ渡し、誤拒否または誤認可する。
- **対応方法**: 確定仕様に従って対象外moduleからscope guardを除去する。方針変更する場合はrequirementsを先に変更し、一覧から全操作まで一貫して適用する。BP支払認可はpaymentからinvoiceIdを解決する。
- **期待結果**: scope ON/OFFで対象外管理業務の可視・操作範囲が変わらず、一覧と詳細が一致する。
- **必要テスト**: scope ON/OFF×管理者/営業の4象限で請求、BP支払、勤怠の後方互換を確認する。

### R3R-36 【中・テスト基盤】今回の重大不具合を自動検出する完了条件が不足している

- **場所**: `src/test/resources/application-test.yml`、H2 schema、`DataScopeServiceImplTest.java`、frontend test設定
- **事象・影響**: test profileはFlyway無効で通知schema差分も未反映、MySQL smokeはDockerなしでskipされる。DataScopeの実endpoint統合test、JS構文検査、browser smoke、並行処理testがなく、現在のunit testが全緑でも上記不具合を検出できない。
- **対応方法**: MySQL migration smokeをCI必須にし、H2 schemaを同期する。全JSの構文check、主要画面browser smoke、scope棚卸し表に対応するendpoint test、実DB並行testを追加する。
- **期待結果**: migration破損、画面script全停止、IDOR、競合不整合がmerge前に自動検出される。
- **必要テスト**: 本書各項目の再現testを追加した上で、`mvn test`、MySQL smoke、JS check、browser smokeをすべて完走させる。

### R3R-37 【低・repository hygiene】実装用一時patch fileがrepository直下に残る

- **場所**: `patch.py`、`patch_c20.py`〜`patch_c26.py`、`patch_invoice.py`、`patch_invoice.java`、`patch_invoice_js.py`、`resolve.py`
- **事象・影響**: 正式なsourceではない機械的patch helperが追跡され、保守時に実装/運用fileと誤認される。
- **対応方法**: 本実装に不要であることを確認して削除し、必要ならrepository外の作業用directoryで管理する。
- **期待結果**: repository直下にはbuild・運用・開発に必要なfileだけが残る。
- **必要テスト**: 削除後にbuild/testへ影響がないことを確認する。

## 3. 正しく対応されていることを確認した主な項目

以下はsourceと対象testの範囲で、主目的が反映されていることを確認した。ただし、上記指摘によって画面全体または周辺処理が利用不能な場合は、指摘解消後に再確認すること。

- P1: C1、C2、C3、C10、C11、C12、C13
- P2: C17、C19、C20、C23、C24
- P3: C31、C32、C36
- P4: C39、C40、C41、C42、C43、C44、C46、C49
- P5: C50、C51
- P6: C55、C56、C57、および逐次実行時のC53/C54の一部
- P7: 顧客summary、営業活動、案件本体、要員経歴、見積PDF、Excel出力のscope guard、通知Mapperと `publishToUser` の基盤

## 4. 検証記録と判定上の注意

- P1/P2関連の対象65test、およびP5〜P7関連の対象81testは成功した。
- P3/P4関連5 test classと全体 `mvn test` は今回の実行時間内に完走結果を得られなかった。
- JavaScript静的検査では `monthly-closing.js` と `system-config.js` のparse errorを再現した。
- 通常test profileはFlywayを実行しないため、migrationの2件の致命的不具合はunit test成功では否定できない。
- 既存testには誤った挙動を期待しているものもある。例: 差戻しでgeneric通知を期待するtest、見積備考の上書きを期待するtest。

したがって、既存testの成功件数だけで「対応済み」と判定せず、仕様上の期待を表すtestへ修正した後に再実行する必要がある。

## 5. 推奨対応順序

1. R3R-01〜04を修正し、MySQL migrationとJS構文検査を通す。
2. R3R-31〜35の認可・通知漏洩を修正し、営業2userの統合testを通す。
3. R3R-05、10、18、24、25、29の競合処理を共通lock/CAS方針で修正する。
4. 残る高・中高指摘を機能単位で修正する。
5. R3R-36のtest基盤を追加し、全test、MySQL migration smoke、JS check、browser smokeを完走する。
6. `bug-hunt-round3.md` の対応状況と実測test件数を、最終結果に合わせて更新する。

## 6. 完了判定条件

- 致命的・高・中高の全指摘が対応済みである。
- 中以下は対応済み、または未対応理由・影響・期限・責任者が明記されている。
- 空DB、V9 baseline、既存最新DBのmigrationが成功する。
- 全JavaScript moduleの構文検査が成功し、主要画面のbrowser smokeが成功する。
- scope ONの営業2userで読取/書込の担当内・担当外matrixがすべて期待どおりである。
- 並行処理testで締め、勤怠承認、請求入金、見積状態、単価同期の不整合が発生しない。
- `mvn test` とMySQL migration smokeが完走し、failure/errorが0である。
