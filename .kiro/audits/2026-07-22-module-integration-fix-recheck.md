# モジュール横断バグ改修 再レビュー

- 再レビュー日: 2026-07-22
- 基準: main@6f160db + 現在の未コミット作業ツリー
- 参照元: 2026-07-22-current-module-integration-bug-audit.md の MI-01〜MI-30
- 方針: 現行差分を読み取り専用で確認し、解消・部分解消・未解消を再判定した
- 本書では業務ソース、SQL、設定、テストを変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

30件の再判定は次のとおり。

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 6 | MI-07、MI-08、MI-11、MI-12、MI-14、MI-16 |
| 部分解消 | 10 | MI-01、MI-02、MI-03、MI-04、MI-05、MI-06、MI-09、MI-10、MI-13、MI-17 |
| 未解消 | 14 | MI-15、MI-18〜MI-30（MI-24〜MI-30を含む） |

優先して差し戻すべき点:

1. **公開済みのmain V10を直接変更している。** 適用済みDBはFlyway checksum mismatchで起動できない。
2. **旧手動DBの移行はまだ完了しない。** 旧 t_bp_payment の単列UNIQUEと親FK不足が残る。
3. **V42のSkillTag権限追加が営業ロールの既存画面を壊す。** 営業が要員/案件画面でタグ一覧を取得すると403になる。
4. 契約の実終了日、当月確定済み勤怠の単価改定、通知のnull-role、AIスコープの404契約などは部分修正のまま。
5. 今回の差分には、CJK非対応PDFフォントfallbackとAIエラー表示の新しいUI回帰がある。

## 2. 検証結果

### 全量テスト

- 実行: .\apache-maven-3.9.6\bin\mvn.cmd test
- 結果: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**
- 実行時間: 7分48秒
- Dockerが利用できないため、実MySQLの FlywayMigrationSmokeTest はスキップされた。

### 対象限定テスト

- 対象: ProposalServiceImplTest、ContractServiceImplTest、SystemConfigServiceImplTest、NotificationServiceImplTest、QuotationServiceImplTest、UserApiControllerTest
- 結果: **81 tests / 0 failures / 0 errors**
- ただし、実DBのmigration履歴、二重トランザクション、ブラウザJS契約は網羅していない。

### Flyway resolver

- main と prod の両locationを指定した flyway:info は成功した。
- V10と旧prod V10のversion重複はなくなり、V41、V42まで一意に解決された。
- これはSQLの実行成功、既存履歴のvalidate成功、旧V9 DBからのupgrade成功を証明しない。

### 差分品質

- git diff --check は5ファイルのEOF余分空行を警告した。
- 重大な機能問題ではないが、マージ前に整形対象とする。

---

## 3. MI-01〜MI-30 再判定

| ID | 判定 | 再レビュー結果 |
|---|---|---|
| MI-01 | 部分解消 | prod migrationをV41へ変更し新規resolverのV10重複は解消。ただしmain V10自体を改変したため適用済みDBはchecksum不一致。dev/main-onlyでV42まで進めてからprodへ切り替えるDBでは、未適用V41がout-of-order扱いで無視される可能性もある。 |
| MI-02 | 部分解消 | V10で不足列を補う処理は追加されたが、旧 work_record_id 単列UNIQUE、fk_bp_payment_parent、失敗後のprocedure再実行性が未対応。空DBと旧DBは同じschemaへ収束しない。 |
| MI-03 | 部分解消 | 現行のBP不一致、契約更新、メール失敗はmenuKeyへ割当てられ、menu_key=nullの全員公開は閉じた。一方、SQLの u.role IS NULL はfail-open、未知typeは拒否されず、メールログ/通知dedupeには完全な宛先が残る。Todo種別UIも未同期。 |
| MI-04 | 部分解消 | 入力IDと候補集合のデータスコープは追加され、主要な越権取得は閉じた。AiSkillSheetServiceImpl自身にはguardがなく、AiRestControllerは担当外404をcatchしてcode=500へ変換する。 |
| MI-05 | 部分解消 | DOMPurifyによりMarked応答のXSS経路は閉じた。APIキーはlocalStorageからsessionStorageへ移ったが同一originスクリプトから読める。CDN URLはversion/SRI未固定、CSPとXSS回帰テストもない。 |
| MI-06 | 部分解消 | 外部Gemini chatとServiceはenabledを確認し、APIキーをheaderへ移し、例外も脱機密化した。一方、rule matching APIとAIページはenabled=falseでも利用でき、provider/apiUrl/modelの共通設定は使用していない。 |
| MI-07 | 解消 | ProposalMapper.selectByIdForUpdateを使用し、ロック後の最新statusで遷移を再検証する。同一提案の成約/見送り同時成功は防止された。 |
| MI-08 | 解消 | Contract.changeStatusもselectByIdForUpdateを使用し、通常編集・単価改定と同じ契約行ロック規約になった。同一契約の終端lost updateは閉じた。 |
| MI-09 | 部分解消 | endDate=nullの終了時に日付を保存するため無期限計上は減った。ただし既存の未来endDateは実終了時も短縮されず未来月に残る。終了時にcancelDateを受け入れるが開始日前/未来日の検証がなく、DTO説明とも矛盾する。 |
| MI-10 | 部分解消 | applyFrom以降の非確定WorkRecordは再計算され、順次操作の旧単価固定は改善した。当月確定済みは警告なしで旧額が残り、勤怠入力/確定と単価改定が契約ロックを共有しないため並行実行でも旧額を確定できる。 |
| MI-11 | 解消 | キャッシュ更新をafterCommitへ移し、rollback時は対象keyをevictする。元の「DB rollback後に未コミットcacheだけ残る」経路は閉じた。 |
| MI-12 | 解消 | Webhookはトランザクション中ならafterCommit後に送られ、rollback時には送信されない。非トランザクションpublishは従来どおり即時送信する。 |
| MI-13 | 部分解消 | 新規受注時にproject必須、engineer/project存在を確認する。createDraft側は再検証せず、既存の不正受注データは依然DB 500になり得る。proposalId、案件/顧客再整合も未検証。 |
| MI-14 | 解消 | JSはadditionalRemarkを送信し、DTOは旧additionalもJsonAliasで受ける。機能契約は一致したが専用契約テストは未追加。 |
| MI-15 | 未解消 | role互換、依存menu、必須Dashboard、login遷移、Header quick-add、管理者編集UIは未修正。設定可能・表示・静的認可・内部APIがまだ一致しない。 |
| MI-16 | 解消 | 管理者はGlobalControllerAdviceから全menuKeyを受け、V42もcontract-documentを補完する。Sidebarとsuperuser bypassが一致した。 |
| MI-17 | 部分解消・回帰あり | /api/skill-tagsはmenu filter対象になったが、GETと全CRUDが同じ権限。営業にはskill-tagをseedしないため、営業が既存の要員/案件画面からGETすると403になる。 |
| MI-18 | 未解消 | API専用401 entry pointがなく、fetchはlogin HTMLへの302追従後にresponse.json()を実行する。 |
| MI-19 | 未解消 | SES.apiは403/5xxをthrowせずnullでresolveし、freee画面は失敗後も成功Toastを出す。 |
| MI-20 | 未解消 | Email template、Gantt、AI matchingの本番mock fallbackと操作可能な偽IDが残る。 |
| MI-21 | 未解消 | 契約、請求、Ganttは先頭100件だけを表示し、ページャ/期間専用取得がない。 |
| MI-22 | 未解消 | 月次締め/Dashboardのmonth、customerId、tab、invoiceId、statusを遷移先JSが反映しない。 |
| MI-23 | 未解消 | 案件modalの顧客placeholderはvalue=1のまま。非同期読込前の保存で顧客1を送信できる。 |
| MI-24 | 未解消 | freee linkは論理削除だがengineer/freee employeeの全体UNIQUEが残り、解除後の再連携ができない。 |
| MI-25 | 未解消 | username全体UNIQUEと論理削除の不整合が残り、削除後の同名作成は事前検査を通ってDBで失敗する。 |
| MI-26 | 未解消 | ユーザーstatusは任意Integerを受け、status=2で営業担当guardを迂回できる。 |
| MI-27 | 未解消 | Engineer/SkillTag/CustomerのAPI validationとDB ENUM/NOT NULL/VARCHAR長の不一致は未修正。 |
| MI-28 | 未解消 | Gemini promptは円の生値を万円と表示し、nullもnull万円になる。 |
| MI-29 | 未解消 | /engineer/form と /project/form は存在しないtemplateを返す。PageRenderingTestへのtest profile追加だけではルートを検査していない。 |
| MI-30 | 未解消 | 実MySQL smokeは空DB mainのみ。main+prod migrate、旧V9 upgrade、既存history validate、最終H2制約同期がない。 |

---

## 4. マージ前に必須の改修

### RR-01 【P1】公開済みmain V10の改変で既存DBがchecksum mismatchになる

- 対象:
  - src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql
  - src/main/resources/db/migration-prod/V41__update_admin_password_bcrypt.sql
- 現象:
  - version重複の解消と同時に、既に配布済みのmain V10本文へstored procedureを追加した。
  - Flywayは適用済みversionのchecksumを検証するため、旧main V10を実行済みのDBはvalidateで停止する。
  - 旧prod V10だけを実行した履歴がある場合、現在のmain V10とはdescription/checksumの意味も一致しない。
  - main-onlyでV42まで進んだDBへ後からprod locationを追加すると、V41が現行versionより低くなり、既定outOfOrder=falseでは適用されない。
- 改修方法:
  1. main V10をHEADの配布済み内容へ戻し、適用済みmigrationを不変にする。
  2. legacy bridgeはV9より大きくV10より小さい新version、例 V9.1として別ファイルにする。
  3. prodパスワード更新は平文adminだけを対象とするRepeatable migrationへ移すか、profileを後付けしても確実に実行される方式にする。
  4. 既に旧prod V10履歴を持つ環境にはdescription/checksumを確認した専用repair手順を用意する。
- 期待効果:
  - 新規DB、main運用済みDB、prod運用済みDB、後からprodへ切り替えるDBのすべてでvalidate/migrateできる。

### RR-02 【P1】legacy bridgeが旧単列UNIQUEを残し、多段BPを依然作成できない

- 対象:
  - V10__fix_bp_payment_unique_key.sql
  - 旧 sql/005_create_work_record_billing.sql
- 現象:
  - 旧 t_bp_payment は work_record_id BIGINT NOT NULL UNIQUE であり、同じ勤怠に二行を作れない。
  - 現bridgeはuk_work_record_layerだけを削除対象にし、旧単列UNIQUEを識別・削除しない。
  - parent_payment_id列は追加するがfk_bp_payment_parentを追加しない。
  - CREATE PROCEDURE前にDROP IF EXISTSがなく、途中失敗後の再試行で残存procedureと衝突し得る。
- 改修方法:
  1. information_schema.statisticsでwork_record_id単列UNIQUEの実名を特定して削除する。
  2. V10実行前の状態としてuk_work_record_layerを作り、その後に不変のV10で通常indexへ移行する。
  3. 親FK、列、既定値を空DBのV5 schemaと一致させる。
  4. 旧005 → baseline 9 → 現行migrateを実MySQLで自動試験する。
- 期待効果:
  - 旧DBでも多段BPを登録でき、空DBと同じ最終制約へ収束する。

### RR-03 【P1/P2】SkillTag権限追加により営業の既存要員/案件画面が403になる

- 対象:
  - V42__seed_missing_role_menu_permissions.sql
  - SkillTagApiController.java
  - engineer.js、engineer-detail.js、project.js、availability-calendar.js
- 現象:
  - V42は /api/skill-tags を独立menuへ割り当てるが、営業にそのmenuを付与しない。
  - 営業が利用する既存画面はタグ候補取得のためGET /api/skill-tagsを呼ぶ。
  - GETとPOST/PUT/DELETEが同一prefixなので、menuを付けると読取りだけでなく全CRUDも許可される。
- 改修方法:
  1. 公開read endpointと管理write endpointを分離する。
  2. GETはengineer/project等の依存menuを持つロールへ許可し、書込みは管理者/HR等へ明示制限する。
  3. role×HTTP method×menuの統合テストを追加する。
- 期待効果:
  - 営業の既存画面を壊さず、SkillTagマスタの更新だけを限定できる。

### RR-04 【P2】異なる提案/契約の同時終了で要員statusが古いまま残る

- 対象:
  - EngineerStatusServiceImpl.java:51-63
  - ProposalServiceImpl.changeStatus
  - ContractServiceImpl.changeStatus
- 現象:
  - 提案/契約自身の行ロックは追加されたが、同じ要員行はロックしない。
  - 同じ要員の提案A/Bまたは契約A/Bを同時に終了すると、各releaseIfIdleが相手の未コミットactive行を見て「まだ稼働/提案中」と判断できる。
  - 両トランザクション終了後はactive行が0でも、どちらも要員をBenchへ戻さない。
- 改修方法:
  1. releaseIfIdleで先にEngineer行をFOR UPDATEし、その後にactive proposal/contractを再集計する。
  2. 全経路で Engineer → Proposal/Contract のロック順を統一してdeadlockを避ける。
  3. 同一要員の異なる二行を終了する実トランザクション並行テストを追加する。
- 期待効果:
  - 派生statusが最終的なactive提案/契約件数と必ず一致する。

### RR-05 【P2】CJKフォント不在時にHelveticaへfallbackし、日本語見積PDFの文字が欠落する

- 対象:
  - QuotationPdfServiceImpl.java:171-195
  - QuotationPdfServiceImplTest.java
- 現象:
  - 新fallbackはHelvetica + WINANSIであり、日本語glyphを持たない。
  - OpenPDF 2.0.2でHelveticaのcharExistsを確認すると、ASCIIはtrueだが「日」「本」「語」はすべてfalseだった。
  - これまでの明示的な「CJK fontなし」エラーが、生成成功に見える文字欠落PDFへ変わる。
  - テストはCJK fontなし環境でgenerate前にskipするためfallbackを検査しない。
- 改修方法:
  1. Helvetica fallbackを削除し、CJK fontがなければ従来どおり明示エラーにする。
  2. 代替するなら日本語対応fontをアプリへ同梱してembeddingする。
  3. PDFからtextを抽出し、会社名、件名、日本語labelが存在することをテストする。
- 期待効果:
  - 「PDFは生成されたが日本語が空白」という静かな帳票破損を防止できる。

### RR-06 【P3】AIエラー表示がHTMLタグを文字列として表示する

- 対象:
  - ai.js:235-245,296-299
- 現象:
  - 呼出側はエラーをspan/iを含むHTMLとしてappendMessageへ渡す。
  - 新しいparseMarkdown=false経路は全文をescapeHtmlしてからhtml()へ設定する。
  - XSSは防げるが、利用者にはspanやiのタグ文字列がそのまま表示され、既存の色/アイコンが消える。
- 改修方法:
  1. エラー本文はplain textだけを渡し、固定の安全なwrapperをappendMessage内部で組み立てる。
  2. または信頼済み固定templateと可変textを分離し、可変部分だけtextContentで設定する。
- 期待効果:
  - XSS防止を維持しながら、エラー表示の体裁と可読性を復元できる。

### RR-07 【P2】契約modalにもvalue=1の非同期placeholder競合が残る

- 対象:
  - templates/contract/list.html:111-120
  - contract.js:59-77,273-293
- 現象:
  - 要員/案件placeholderがvalue=1で、AJAX完了前でも必須チェックを通る。
  - 顧客候補だけ先に読込完了し顧客1を選んだ場合など、要員/案件を選んでいなくてもID 1の組合せを送信できる。
- 改修方法:
  1. 初期placeholderをvalue=""にする。
  2. 全lookup完了まで保存を無効にし、失敗時も保存させない。
  3. サーバーでEngineer存在、Project/Customer整合を再検証する。
- 期待効果:
  - 非同期応答順により未選択の要員/案件1へ契約が誤帰属しない。

---

## 5. 部分解消項目の残作業

### 通知・AI

- NotificationMapperの u.role IS NULL を許可条件から外し、ユーザー不存在/無効sessionをfail-closedにする。
- 未知通知typeはpublish時に拒否するか、管理者audienceを明示する。
- MailServiceのログとdedupe keyから完全なメールアドレスを除く。
- AiRestControllerでBusinessExceptionを再throwし、GlobalExceptionHandlerの404を維持する。
- skill-sheet service自身にもDataScope guardを置く。
- ai.enabledの対象を「外部AIのみ」か「AI画面全体」か定義し、全endpointで一貫させる。
- Gemini endpoint/model/providerをAiConfigから取得する。

### 契約・見積・金額

- 終了用completionDateを追加し、startDate以後かつ業務上許可した日付か検証してendDateを上書きする。
- 確定処理で最新価格を再解決するか、録工/確定と価格改定で同じ契約ロックを使用する。
- applyFrom以降に確定済みrecordが一件でもあれば、当月を含めて警告/拒否する。
- 見積受注とcreateDraftの双方でEngineer、Project、Customer、Proposalの組合せを共通検証する。

---

## 6. 未解消14件の改修順

1. MI-30の実MySQL三経路を先に作り、RR-01/RR-02を安全に修正する。
2. MI-15/MI-17で権限マトリクス、menu依存、read/write分離を確定する。
3. MI-18/MI-19で通信共通層を修正し、セッション切れと失敗を必ずrejectする。
4. MI-20〜MI-23でmock、100件上限、deep link、placeholder競合を修正する。
5. MI-24〜MI-27で論理削除UNIQUE、status、DTO validationをDBと一致させる。
6. MI-28/MI-29でAI金額単位と不存在page routeを修正する。

## 7. 再レビュー完了条件

次回は少なくとも以下をすべて満たすこと。

1. 既存main V10適用済みDBで flyway validate が成功する。
2. 旧手動005を含むbaseline 9 DBがV42まで移行し、多段BPを二行登録できる。
3. main-only V42適用後にprodへ切り替えてもadmin BCrypt migrationが実行される。
4. 営業の要員/案件画面でSkillTag GETが成功し、書込みは許可ロールだけ成功する。
5. 同一要員の異なる二提案/二契約を並行終了して最終statusがBenchになる。
6. 終了日、当月確定単価、見積参照整合の再現テストが通る。
7. API session切れ、403、500が全通信方式でrejectされ、成功Toastを出さない。
8. 101件目、deep link、mockなし空表示、placeholder競合のブラウザ試験が通る。
9. 日本語見積PDFから日本語textを抽出できる。
