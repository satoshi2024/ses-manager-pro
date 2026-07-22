# モジュール横断バグ改修 第4回再レビュー

- 再レビュー日: 2026-07-23
- 基準: `main@6f160db` + 現在の未コミット作業ツリー
- 比較元: `2026-07-22-module-integration-fix-recheck-3.md`
- 対象: MI-01〜MI-30、R2-01〜R2-09、RR-01〜RR-07、R4-01〜R4-06、および今回差分で確認した回帰
- 方針: コード、SQL、画面、テストを読み取り専用で確認し、本書以外の業務ファイルは変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

30件の再判定は次のとおり。

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 8 | MI-07、MI-08、MI-11、MI-12、MI-14、MI-16、MI-25、MI-29 |
| 部分解消 | 13 | MI-01〜MI-06、MI-09、MI-10、MI-13、MI-17、MI-23、MI-26、MI-30 |
| 未解消 | 9 | MI-15、MI-18〜MI-22、MI-24、MI-27、MI-28 |

今回確認できた主な改善:

1. prod Repeatable migrationのBCrypt hashは実際に `admin123` と一致し、前版の既知誤hashも救済対象になった。
2. `beforeMigrate.sql` はV5相当の列・型・FKを補い、複合UNIQUEを先に作ってから旧単列UNIQUEを削除する順へ改善した。
3. WorkRecord金額更新へ `status != '確定'` と `actual_hours` のCAS条件が追加された。
4. Project編集は顧客候補の完了後に現在値を設定し、Contract編集は候補外の退職・無効担当営業も保持するようになった。
5. 旧prod V10履歴用のTestcontainers試験が追加された。

一方、リリースを止めるべき問題が残る。

1. **追加されたFlyway repair試験は、main V10のBP索引変更を実行しないまま履歴だけを「適用済み」に書き換える。誤ったschemaでもlogin確認だけで成功し得る。**
2. **legacy callbackは旧prodパスワードV10もmain BP V10と誤認し、V10途中失敗の再試行も処理できない。実MySQLでは未検証である。**
3. WorkRecordの日次保存と削除は逆のロック順を取り、同一勤怠の並行操作でデッドロックし得る。単価改定・確定・BP支払にも旧snapshot競合が残る。
4. Git indexは旧 `V10 -> V41` renameを保持し、Repeatable、callback、repair試験は未追跡である。このままindexだけをcommitすると、確認した修正内容と異なる成果物になる。

## 2. 検証結果

### 全量テスト

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 結果: **586 tests / 0 failures / 0 errors / 6 skipped / BUILD SUCCESS**
- 実行時間: 5分01秒
- `FlywayMigrationSmokeTest` と新規 `FlywayRepairRunbookTest` は各1件skipで、実MySQL migrationは実行されていない。
- CJK font依存のPDF試験もskip対象であり、日本語PDFの正常性は依然証明されていない。

### BCrypt実値検証

- 使用クラス: Spring Security 6.3.4 `BCryptPasswordEncoder`
- `matches("admin123", R__...の現hash)` は **true**
- 前版の既知誤hashに対する同じ照合は **false**
- RepeatableのWHEREは平文、NULL、既知誤hashを対象とし、失敗回数とlockも解除する。

### Flyway解決確認

- main + prod locationを指定したH2 `flyway:info` は、main V10とRepeatableを重複なく解決しBUILD SUCCESSとなった。
- これはmigrationの命名・解決確認であり、MySQL専用callback、索引DDL、repair後の最終schemaを実行検証するものではない。
- 既存localhost MySQLには接続・変更していない。

### 作業ツリー品質

- `git diff --check` は次を警告した。
  - Java 4ファイル: EOFの余分な空行
  - `contract.js:222`: trailing whitespace
  - `project.js:221,233`: trailing whitespace
- Git indexは `V10__update_admin_password_bcrypt.sql -> V41__update_admin_password_bcrypt.sql` のrenameを保持する一方、worktreeではV41が削除状態である。
- `R__update_admin_password_bcrypt.sql`、`beforeMigrate.sql`、`FlywayRepairRunbookTest.java` は未追跡である。

## 3. 今回の優先改修事項

### R5-01 【P1・新規】Flyway repairがmain V10未実行のschemaを適用済みとして扱う

- 対象:
  - `src/test/java/com/ses/migration/FlywayRepairRunbookTest.java:27-59`
  - `src/main/resources/db/migration/beforeMigrate.sql:14-18`
  - `src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql:5-6`
- 現象:
  - 試験はV1〜V9適用後、旧prod V10を実行せず、任意checksumの成功履歴だけを直接INSERTする。
  - `repair()` はその履歴を現在のmain V10へ書き換えるが、V10の `CREATE INDEX` と `DROP INDEX` は実行しない。
  - その後のcallbackも成功済みversion 10を見てskipする。
  - 最終DBには旧 `uk_work_record_layer` が残り、通常索引 `idx_bp_payment_work_record` がない可能性がある。論理削除後の同一階層再作成は引き続き失敗する。
  - 試験はadmin passwordしかassertしないため、誤schemaでも成功できる。
- 改修方法:
  1. fixtureで実際の旧prod V10 SQLと既知description/script/checksumを再現する。
  2. repair前にvalidate失敗をassertし、対象DBのversion 10履歴が許可リストと完全一致する場合だけrepairする。
  3. repair前後に、main V10相当の索引変更を専用の補償migrationまたは明示runbook SQLで実行する。
  4. `uk_work_record_layer` 不在、`idx_bp_payment_work_record` 存在・非UNIQUE、active unique、FK、列型をassertする。
  5. fresh DBと旧prod履歴DBの最終schemaを比較し、BP行の論理削除後再作成まで検証する。
- 期待効果:
  - Flyway historyだけでなく実schemaも現行状態へ収束し、旧本番DBでBP多段・再作成機能を安全に利用できる。

### R4-02 【P1・部分解消】legacy callbackに旧V10誤判定と再試行不能が残る

- 対象:
  - `src/main/resources/db/migration/beforeMigrate.sql:5-83`
  - `src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql:5-6`
- 改善済み:
  - `layer_order`、VARCHAR(200)の `payee_company_name`、`parent_payment_id`、`deleted_flag` を補完する。
  - V5と同じ親FKを補い、複合UNIQUEを先に作ってから旧単列UNIQUEを削除する。
  - 不一致だった後付けV43は削除された。
- 残る問題:
  - 判定が `version='10' AND success=1` だけなので、旧prodパスワードV10をmain BP V10と誤認する。
  - main V10が `CREATE INDEX` 後に失敗したDBでは、再実行時に同名indexの重複で再度失敗する。
  - 正常DBでも毎回stored procedureのCREATE/DROP権限を要求する。
  - 実MySQLのbaseline 9、途中失敗、再実行を試験していない。
- 改修方法:
  1. versionだけでなくdescription/script/checksumと実schema形状で対象を識別する。
  2. `idx_bp_payment_work_record` だけが残る途中状態を検出し、V10を再実行可能な形へ戻すか補償する。
  3. 毎回CREATE ROUTINEするSQL callbackではなく、Java callbackまたは一回限りの監査済みupgrade手順へ移す。
  4. 空DB、手動旧DB+baseline 9、V10途中失敗、旧prod V10履歴の4経路をMySQL 8でCI実行する。
- 期待効果:
  - 権限差や過去の適用経路に左右されず、失敗後も再試行できるmigrationになる。

### R5-02 【P2・新規】WorkRecordのロック順逆転、旧snapshot、BP金額分裂

- 対象:
  - `WorkRecordServiceImpl.java:107-137,206-228,336-423,475-565`
  - `ContractServiceImpl.java:427-460`
  - `WorkRecordMapper.java:94-102`
- 改善済み:
  - 単価改定の金額更新は `status != '確定' AND actual_hours = #{actualHours}` のCASになった。
  - `saveHours` と `saveDaily` はContract行ロックを利用するようになった。
- 残る問題:
  - `saveDaily` はContract→daily行、`deleteDaily` はdaily行→Contractの順でロックし、循環待ちを形成できる。
  - `saveHoursInternal` はContractロック前にWorkRecord、日次、請求を普通SELECTする。MySQL REPEATABLE READではロック待ち後も旧snapshotを使い得る。
  - `submit`、`approve`、`reject`、`confirmMonth`、`reopenMonth` は同じContractロックを共有しない。
  - 単価改定側はCAS結果0件を無視する。承認側は先に読んだ旧 `paymentAmount` からBP支払を生成できる。
  - `confirmMonth` は古い全行entityを `updateById` し、改定後の金額を巻き戻し得る。当月確定済みの警告も漏れる。
- 改修方法:
  1. 全経路をContract→WorkRecord(FOR UPDATE)→dailyの順へ統一し、批量処理はcontractId順でロックする。
  2. Contractロックを最初の普通SELECTより前へ移し、WorkRecordと価格履歴をロック後に再読込する。
  3. CASが0件なら最新行をreloadして再計算・再試行し、収束できなければ409を返す。
  4. confirm/approve直前に最新価格と工数を再解決し、その同じ値でWorkRecord確定とBP支払生成を行う。
  5. 当月を含む確定済みWorkRecordへの遡及改定を拒否または明示警告する。
  6. 実DB並行試験で保存×削除、改定×承認、改定×一括確定を再現する。
- 期待効果:
  - デッドロック、状態巻戻し、旧単価確定、WorkRecordとBP支払の金額分裂を防止できる。

### R5-03 【P2・新規】案件スキル候補と既存スキルの応答順競合

- 対象: `static/js/modules/project.js:7-20,234-243,327-340`
- 現象:
  - SkillTag取得だけPromise化されず、案件スキルが先に返ると空の `allSkillTags` からselectを作る。
  - 後からSkillTagが届いても既存行を再描画せず、既存スキルが未選択表示になる。
- 改修方法:
  - `skillTagsPromise` を作り、顧客、案件詳細、案件スキル、SkillTagの完了後に行を描画する。読込中はスキル追加・保存を無効化し、失敗を明示する。
- 期待効果:
  - 通信順に関係なく既存スキルを正しく保持し、空選択での誤更新を防止できる。

### R5-04 【P2・新規】候補Promiseの失敗が永久キャッシュまたは成功扱いされる

- 対象:
  - `static/js/modules/project.js:99-125,212-251`
  - `static/js/modules/contract.js:3-5,61-129,207-251`
- 現象:
  - Projectは業務エラー/HTTPエラーを `resolve()` し、空候補を成功として永続キャッシュする。
  - Contractは1件でも失敗するとrejected Promiseを保持し、初期呼出しにもcatchがない。以後はreloadまで全編集が即失敗する。
  - 局所Toastと共通AJAX Toastが重複し、権限/業務エラーをnetwork errorとして表示し得る。
- 改修方法:
  - 非200をrejectし、catchでPromiseをnullへ戻して再試行可能にする。初期呼出しにもcatchを付け、依存取得成功まではmodal/saveを無効化し、Toast責務を一箇所へ統一する。
- 期待効果:
  - 一時障害から画面reloadなしで復旧し、不完全な候補での保存と誤ったエラー表示を防止できる。

### R5-05 【P2・新規】`size=1000` が500件上限で切られ、古い候補を編集できない

- 対象:
  - `static/js/modules/project.js:103`
  - `static/js/modules/contract.js:63,70,77`
  - `MyBatisPlusConfig.java:31-33`
- 現象:
  - 画面は1000件を要求するが、MyBatis-Plusの最大page sizeは500である。
  - 501件目以降の顧客、要員、案件は候補に現れない。Contractは担当営業以外の現在値を補完しないため、古い参照を持つ契約を正常編集できない。
- 改修方法:
  - 軽量な検索型options APIを用意するか全ページを明示取得し、編集時は現在参照する顧客・要員・案件も候補外optionとして補完する。
- 期待効果:
  - マスタ件数増加後も全候補へ到達でき、既存契約の参照を誤って消さずに編集できる。

## 4. 前回優先事項 R4-01〜R4-06

| ID | 判定 | 結果、残作業、期待効果 |
|---|---|---|
| R4-01 | 解消 | 現hashは `admin123` と一致し、平文/NULL/既知誤hashを救済してlockも解除する。MySQL上のlogin統合試験で固定する。 |
| R4-02 | 部分解消 | legacy列・型・FK・索引順は改善。旧prod V10誤判定、V10途中失敗、CREATE ROUTINE権限、実MySQL試験が残る。R5-01と合わせて修正すれば全旧DBを再試行可能にできる。 |
| R4-03 | 部分解消 | repair試験は追加されたが、履歴だけをmain V10へ改認し実schemaを補償しない。R5-01のschema assertionと補償migrationが必要。 |
| R4-04 | 部分解消 | 金額CASと一部Contract lockは追加。CAS 0無視、逆ロック順、旧snapshot、確定/BP競合が残る。R5-02の統一ロックで金額を収束させる。 |
| R4-05 | 解消 | 顧客候補と案件詳細を待ってからcustomerIdを設定し、編集時の応答順競合を閉じた。DOM順序試験で固定する。 |
| R4-06 | 解消 | 全候補完了後に契約詳細を設定し、候補外の退職・無効担当営業もoptionで保持する。DOM試験で固定する。 |

## 5. MI-01〜MI-30 再判定と残作業

| ID | 判定 | 現状、改修方法、期待効果 |
|---|---|---|
| MI-01 | 部分解消 | version重複、後付けprod順序、誤hashは改善。旧prod V10をmain V10へ安全に移す補償が残る。R5-01により全prod経路でloginとBP schemaを一致させる。 |
| MI-02 | 部分解消 | callbackの列・型・FK・索引順は改善。旧V10誤判定と途中失敗再試行が残る。実schema判定とMySQL四経路試験でlegacy DBをfresh DBへ収束させる。 |
| MI-03 | 部分解消 | 通知はrole-menuで絞るが `u.role IS NULL OR` がfail-openで、宛先/件名も平文ログ・dedupeに残る。NULL role拒否、未知type拒否、宛先mask/hash、type registry共有で越権表示とPII露出を防ぐ。 |
| MI-04 | 部分解消 | controllerとmatching serviceのscopeは改善。SkillSheet service境界guardと `BusinessException` 再throwがない。両方を追加し全入口を同じ404境界へ揃える。 |
| MI-05 | 部分解消 | Markdown XSSとエラー描画は改善。API keyのsessionStorage、無固定CDN/SRIなしが残る。keyのserver-side化、依存固定、CSP/XSS試験で秘密・供給網リスクを縮小する。 |
| MI-06 | 部分解消 | 外部Gemini停止とheader送信は改善。matching API/pageへのflag適用とAiConfigのprovider/apiUrl/model利用が残る。設定と実動作を一致させる。 |
| MI-07 | 解消 | Proposal lock後の再検証により同一提案の二重終端成功を防止した。実並行試験で固定する。 |
| MI-08 | 解消 | Contract状態変更、通常編集、単価改定は契約行lockを共有し、同一契約のlost updateを防止した。 |
| MI-09 | 部分解消 | 終了契約の確定済み過去売上は残る。終了日検証と期間判定をDashboard、SalesPerformance、WorkRecord、Analyticsで共有し、未来月計上を全モジュールで止める。 |
| MI-10 | 部分解消 | 金額列CASと一部lockは改善。R5-02の統一lock、reload、確定直前再解決で旧単価確定とBP分裂を防ぐ。 |
| MI-11 | 解消 | cache更新をafterCommitへ移し、rollback時のcache ghostを解消した。 |
| MI-12 | 解消 | WebhookをafterCommitへ移し、rollbackした業務事実の外部送信を防止した。 |
| MI-13 | 部分解消 | 新規見積受注の参照検査は追加済み。Quotation受注とContract draft生成で共通validatorを使い、既存不正データとproposal/project/customer整合を検証して安定した4xxへ変える。 |
| MI-14 | 解消 | JS/DTOは `additionalRemark` で一致し、旧 `additional` もaliasで受理する。JSON契約試験で固定する。 |
| MI-15 | 未解消 | role互換、menu依存、必須Dashboard、landing、header quick-addが未統一。role×menu行列と依存graph、allowedMenus連動navigationで設定後の403と行き止まりを防ぐ。 |
| MI-16 | 解消 | 管理者は全menuKeyを受け、contract-document seedも補完され、superuser bypassとSidebarが一致した。 |
| MI-17 | 部分解消 | 営業GET、write制限、403応答は改善。menu依存graphまたはcaller menu別read APIにより、動的権限変更後も要員/案件画面を壊さず書込みを制限する。 |
| MI-18 | 未解消 | `SES.api` はform-login 302後のHTMLをJSON parseする。API専用401 JSON entry pointとredirect/url/Content-Type検査でsession切れを確実にloginへ誘導する。 |
| MI-19 | 未解消 | `SES.api` は403/5xxをnull resolveし、payrollが成功Toastを出す。非成功をthrowしToast責務を統一してfalse-successを防ぐ。 |
| MI-20 | 未解消 | Email template、Gantt、AI matchingの操作可能な本番mockが残る。mockを明示dev flagへ隔離し、本番は空/失敗状態だけにして架空ID操作を防ぐ。 |
| MI-21 | 未解消 | Contract、Invoice、Ganttは先頭ページ/100件だけを扱う。ページャまたは期間・検索専用APIで全件へ到達可能にする。R5-05も同時に解消する。 |
| MI-22 | 未解消 | month、customerId、tab、invoiceId、statusを遷移先controlへ反映しない。初回load前にqueryを適用し、遷移元と同じ対象を表示する。 |
| MI-23 | 部分解消 | 編集時customerId競合は解消。新規modalの早期操作、候補失敗の成功扱い、customerId必須/顧客存在検証が残る。候補完了まで無効化しUI/service両方で検証して不要な400/DB 500を防ぐ。 |
| MI-24 | 未解消 | freee linkは論理削除と全体UNIQUEが衝突する。active generated keyへ移すかunlinkを物理削除にして、一対一を守りながら再連携可能にする。 |
| MI-25 | 解消 | 論理削除済みusernameを含む事前検査により永久再利用禁止ポリシーを業務エラー化した。並行作成のunique例外変換は追加強化として残る。 |
| MI-26 | 部分解消 | status専用APIとPUTは0/1へ限定。POSTはstatus=2を保存できる。作成時1強制、DTO allowlist、DB CHECKで全経路を0/1へ限定する。 |
| MI-27 | 未解消 | Engineer/SkillTag/CustomerのEntity直受けとDB ENUM/NOT NULL/VARCHAR長が不一致。DB制約同期の書込DTOで不正入力を安定した400にする。 |
| MI-28 | 未解消 | Gemini promptは円の生値へ「万円」を付け、nullも連結する。共通formatterで円表示または1万円換算し、AI判断の1万倍ずれを防ぐ。 |
| MI-29 | 解消 | `/engineer/form` と `/project/form` はlistへredirectし、不存在templateの500を解消した。302/Location試験で固定する。 |
| MI-30 | 部分解消 | 旧prod履歴用MySQL試験は追加されたがDockerでskipされ、fixture/schema assertionも不足する。fresh、legacy、旧prod history、途中失敗のCIと最終schema比較でmigration破損を検出する。 |

## 6. R2-01〜R2-09 再判定

| ID | 判定 | 結果 |
|---|---|---|
| R2-01 | 部分解消 | callbackの主要DDLは改善。旧V10誤判定、途中失敗、CREATE ROUTINE権限、実MySQL試験が残る。 |
| R2-02 | 部分解消 | Repeatable hashは解消。旧prod V10 historyを実schemaごと移行するR5-01が残る。 |
| R2-03 | 部分解消 | 確定済み過去売上の消失は解消。未来月のSalesPerformance/WorkRecord/Analyticsとの分裂が残る。 |
| R2-04 | 部分解消 | 金額CASと一部Contract lockは追加。逆lock、旧snapshot、CAS 0、確定/BP競合が残る。 |
| R2-05 | 部分解消 | Engineer行lockはあるが、営業scopeが先に作るRR snapshotと異なる提案の並行終了が残る。commit後のfresh transaction再導出が必要。 |
| R2-06 | 解消 | `AccessDeniedException` はHTTP/APIとも403になった。 |
| R2-07 | 解消 | 候補完了後の詳細設定と候補外担当補完により、退職・無効担当の `salesUserId=NULL` 更新経路を閉じた。 |
| R2-08 | 未解消 | Helvetica/WINANSI fallbackとCJK fontなし試験skipが残る。CJK font同梱・埋込みと日本語text抽出試験が必要。 |
| R2-09 | 解消 | invalid status keyは主要bundleに追加され、利用者向け文言を返す。 |

## 7. RR-01〜RR-07 再判定

| ID | 判定 | 結果 |
|---|---|---|
| RR-01 | 部分解消 | main V10は不変、Repeatable hashも修正済み。旧prod V10 historyと実schemaの補償が残る。 |
| RR-02 | 部分解消 | legacy callbackは改善。旧V10誤判定、途中失敗、実MySQL schema比較が残る。 |
| RR-03 | 部分解消 | 営業GET、write制限、403は改善。動的menu依存が残る。 |
| RR-04 | 部分解消 | Engineer行lockは追加済み。古いRR snapshot、lock順、実並行試験が残る。 |
| RR-05 | 未解消 | Helvetica fallbackと日本語text抽出試験不足が残る。 |
| RR-06 | 解消 | plain textと固定wrapper/text nodeへ分離し、XSS防止とエラー表示を両立した。 |
| RR-07 | 解消 | Contractの要員/案件placeholderは空値で、保存前にも未選択を拒否する。 |

## 8. 推奨改修順

1. R5-01を先に修正し、旧prod V10履歴を実schemaごと現行V10へ収束させる。
2. R4-02とMI-30をまとめ、fresh、baseline 9、旧prod history、V10途中失敗のMySQL CIを完成させる。
3. Git indexを `git add -A` 相当で作り直し、V41がなく、Repeatable/callback/repair試験が含まれる最終diffを確認する。
4. R5-02でWorkRecord全経路のlock順、locking read、CAS再試行、確定/BP生成を統一する。
5. R2-05で要員statusをcommit後のfresh transactionから完全再導出する。
6. R5-03〜R5-05とMI-23をまとめ、候補取得の待機、失敗、再試行、500件超をDOM/API試験する。
7. MI-15、MI-17〜MI-22、MI-24、MI-26〜MI-28、R2-08を順次解消する。

## 9. 次回の完了条件

1. 旧prod V10履歴DBでrepair前validate失敗、限定repair、補償、migrate、admin loginが順に成功する。
2. fresh DBと旧prod/legacy DBで `t_bp_payment` の列、型、索引、FK、削除規則が一致する。
3. V10がCREATE INDEX後に失敗した状態から再実行してlatestまで到達する。
4. Docker有効CIで両Flyway試験がskipされず実行される。
5. 保存×日次削除、単価改定×承認、単価改定×一括確定の並行試験でdeadlockと金額分裂がない。
6. Project/Contract候補の応答順と一時失敗を変えても、顧客、スキル、在職・退職担当が保持され、再試行できる。
7. 500件を超える顧客・要員・案件を検索・選択し、既存契約を参照喪失なしで編集できる。
8. API session切れ、403、500が全通信方式でrejectされ、失敗後に成功Toastを出さない。
9. 日本語見積PDFから日本語textを抽出できる。

