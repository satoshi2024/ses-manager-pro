# モジュール横断バグ改修 第5回再レビュー

- 再レビュー日: 2026-07-23
- 基準: `HEAD=49258b6`（レビュー開始時のclean worktree）
- 比較元: `2026-07-23-module-integration-fix-recheck-4.md`
- 対象: MI-01〜MI-30、R2-01〜R2-09、RR-01〜RR-07、R4-01〜R4-06、R5-01〜R5-05、および今回確認した回帰
- 方針: コード、履歴、SQL、画面、テストを読み取り専用で確認し、本書以外の業務ファイルは変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

30件の再判定は次のとおり。

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 8 | MI-07、MI-08、MI-11、MI-12、MI-14、MI-16、MI-25、MI-29 |
| 部分解消 | 12 | MI-01〜MI-06、MI-09、MI-10、MI-13、MI-17、MI-23、MI-26 |
| 未解消 | 10 | MI-15、MI-18〜MI-22、MI-24、MI-27、MI-28、MI-30 |

今回確認できた改善:

1. 前回のGit index/worktree不一致はなくなり、Repeatable、callback、試験を含む変更はcommit済みである。
2. `saveHours` はWorkRecord、日次、請求の読取前にContractをlockするようになった。
3. `saveDaily` と `deleteDaily` はどちらも月次締め設定→Contract→日次行の順となり、前回確認した逆lockの直接deadlock経路は閉じた。
4. `confirmMonth` は契約ID順のContract lockとstatus CAS、`approve` はContract lockとstatus CASを利用するようになった。
5. SQL stored procedure callbackはJava callbackへ置き換わり、通常起動時にCREATE ROUTINE権限を要求しなくなった。
6. prod BCrypt hashは引き続き `admin123` と一致する。

一方、次のP1問題が残る。

1. **全量テストが589件中7 failures、2 errorsでBUILD FAILUREとなる。**
2. **新しいFlyway repair試験は、Docker環境では現在のV5が既に持つ列・索引を再追加してrepair前に失敗する。さらに直接生成したFlywayへJava callbackを登録していない。**
3. **実際の旧prod DBをrepairした後のcallback分岐は、旧DBに不足する列・FK・旧単列UNIQUEを補わずreturnする。索引変更もFKを支える通常索引を作る前に複合UNIQUEを削除する。**
4. WorkRecordの一括確定・承認はContract lock後も普通SELECTで旧MVCC snapshotを読むため、旧金額からBP支払を生成できる。

## 2. 検証結果

### 全量テスト

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 結果: **589 tests / 7 failures / 2 errors / 6 skipped / BUILD FAILURE**
- 失敗はすべて `WorkRecordServiceImplTest` に集中した。
  - `WorkRecordServiceImplTest`: 28件中7 failures、2 errors
  - `ContractServiceImplTest`: 38件、失敗0、エラー0
- 主因は、新しいContract lock、二回目のWorkRecord読取、status CASに対してmock、contractId、戻り値、検証順が更新されていないことである。
- `FlywayMigrationSmokeTest` と `FlywayRepairRunbookTest` は各1件skipで、実MySQL migrationは実行されていない。

### BCrypt実値検証

- 使用クラス: Spring Security 6.3.4 `BCryptPasswordEncoder`
- `matches("admin123", R__update_admin_password_bcrypt.sqlのhash)` は **true**

### Flyway解決確認

- main + prod locationを指定したH2 `flyway:info` は、main V10とRepeatableを重複なく解決しBUILD SUCCESSとなった。
- これは命名・解決確認に限られ、Java callback、MySQL DDL、repair後schemaの成功を証明しない。
- 既存localhost MySQLには接続・変更していない。

### Spring Boot callback登録確認

- Spring Boot 3.3.5の `FlywayAutoConfiguration` はSpring Beanの `Callback` をFlywayへ登録するため、通常のアプリ起動では `LegacyDatabaseFlywayCallback` が登録される。
- 一方、`FlywayRepairRunbookTest` は `Flyway.configure().load()` を直接使用しており、Spring Beanを自動検出しない。

### Git・差分品質

- レビュー開始時のworktreeはcleanで、前回の `RD + ??` index事故は解消した。
- `git show --check HEAD` は、JavaのEOF余分空行、JSとcallback/testのtrailing whitespace、監査文書のEOF空行を警告した。
- 本書の追加以外にファイルは変更していない。

## 3. 今回の優先改修事項

### R6-01 【P1・新規】WorkRecord単体試験が9件失敗し、全量buildが赤い

- 対象:
  - `src/test/java/com/ses/service/impl/WorkRecordServiceImplTest.java`
  - `src/main/java/com/ses/service/impl/WorkRecordServiceImpl.java:107-249,539-568`
- 現象:
  - Contract lockを先頭へ移したが、confirmed、請求済み、日次管理の既存試験は `selectByIdForUpdate` をstubせず `error.workRecord.noContract2` で失敗する。
  - confirm試験はlock後の二回目SELECTとstatus CASをstubせず、BP生成・同期・通知を検証できない。
  - approve試験もContract lockと再読込をstubせず失敗する。
- 改修方法:
  1. 各試験へcontractId、Contract lock戻り値、lock後のWorkRecord、CAS更新件数を明示する。
  2. 呼出し順を月次締めlock→Contract lock→WorkRecord読取→status CAS→BP生成として検証する。
  3. 単にmockを通すだけでなく、MySQL双方向transaction試験で改定×confirm、改定×approve、reopen×BP支払を追加する。
- 期待効果:
  - buildをgreenへ戻し、lock追加が形式上ではなく実際の競合を防ぐことを継続検証できる。

### R6-02 【P1・新規】FlywayRepairRunbookTestはDocker環境でrepair前に失敗する

- 対象: `src/test/java/com/ses/migration/FlywayRepairRunbookTest.java:27-70`
- 現象:
  - current V1〜V9を適用すると、current V5が既に `layer_order`、`deleted_flag`、`uk_work_record_layer` を作る。
  - 試験はその後の41〜42行で同じ列・索引を再追加し、MySQLのduplicate column/indexで停止する。
  - 46〜49行の直接構築Flywayへ `LegacyDatabaseFlywayCallback` を登録しておらず、53行の「callbackが補償する」という前提が成立しない。
  - 旧prod V10 SQLや実checksumを再現せず、validate failureの内容もassertしないままrepairする。
- 改修方法:
  1. 旧prod V10適用当時のV1〜V10 SQLを専用test fixtureとして固定し、本物の旧schemaとhistoryを作る。
  2. Spring prod contextでFlywayを取得するか、直接Flywayへcallbackを明示登録する。
  3. `validateWithResult` の不一致が許可した旧V10のscript/description/checksumだけであることを確認し、それ以外ならrepairしない。
  4. repair前validate失敗、repair、migrate、login、schema比較、BP論理削除後の同階層再作成を順にassertする。
- 期待効果:
  - Docker有効CIで本当に旧prod upgradeを再現し、未知の履歴不一致を誤ってrepairしなくなる。

### R6-03 【P1・新規】Java callbackのrepair後分岐が旧prod schemaを収束させない

- 対象: `src/main/java/com/ses/config/LegacyDatabaseFlywayCallback.java:78-167`
- 履歴根拠:
  - 旧prod V10当時のV5では、`t_bp_payment` は単列 `work_record_id UNIQUE` のみで、`layer_order`、`payee_company_name`、`parent_payment_id`、`deleted_flag`、親FKを持たない。
- 現象:
  - `flyway repair` 後はhistory descriptionが現main V10になるため、102〜116行の `hasRealV10` 分岐へ入る。
  - この分岐は不足列・親FK・旧単列UNIQUEを補わずreturnし、後続V18は `deleted_flag` / `layer_order` 不足で失敗する。
  - 104〜114行は `uk_work_record_layer` を先にDROPし、その後で `idx_bp_payment_work_record` を作る。複合UNIQUEがwork_record FKを支えていればDROP自体が失敗する。
  - 存在確認は名称だけで、列順、UNIQUE属性、VARCHAR長、FK削除規則を検査しない。
- 改修方法:
  1. history分岐で早期returnせず、全経路で列、型、既定値、FK、索引の実shapeを同じ順に収束させる。
  2. 必要列と親FKを補い、`idx_bp_payment_work_record` を先に作ってから旧複合/単列UNIQUEを削除する。
  3. `information_schema` でindex列順・non_unique、列型/長さ、FK参照先/削除規則まで比較する。
  4. MySQL DDLはrollback不能であるため、途中失敗の各shapeから再実行できるidempotent処理にし、callbackのtransaction宣言も実態へ合わせる。
- 期待効果:
  - fresh、legacy baseline 9、旧prod repair、V10途中失敗の全経路が同じBP schemaへ収束する。

### R6-04 【P2・新規】WorkRecord/BP整合は旧snapshotとlock非共有で崩れる

- 対象:
  - `WorkRecordServiceImpl.java:206-249,278-300,306-348,539-568`
  - `ContractServiceImpl.java:427-488`
  - `InvoiceServiceImpl.java:457-477`
- 改善済み:
  - `saveHours`、`saveDaily`、`deleteDaily` のContract先行lockと、日次保存/削除のlock順統一は有効である。
  - statusと金額のCASは全entity上書きより安全になった。
- 残る問題:
  - `confirmMonth` は最初の普通SELECTでsnapshotを作り、Contract lock待ち後も普通SELECTする。MySQL REPEATABLE READでは待機中に改定された最新金額を読めず、旧金額でBPを生成できる。
  - `approve` もContract lock前後の両方が普通 `getById` で、lock後の再取得はcurrent readではない。
  - `reopenMonth` はContract、WorkRecord、BPをlockせず、旧全entityをbatch updateする。改定済みの確定行を旧金額の入力中へ戻し、再確定できる。
  - `syncRootBpAmount` は未払を読んだ後、IDだけで金額更新する。並行して支払済みになった行も後書きできる。
  - 単価改定は金額CAS 0件を無視し、当月確定行を警告しない。将来改定削除もContract lockと削除件数確認がない。
- 改修方法:
  1. Contract lock後、WorkRecordを `SELECT ... FOR UPDATE` のcurrent readで取得する。
  2. confirm/approve内で最新価格と工数から金額を再計算し、その同じ値で確定とBP生成を行う。
  3. reopenもContract→WorkRecord→BPの順でlockし、statusだけをCAS更新する。再open時または再confirm時に必ず最新価格へ再計算する。
  4. BP金額updateへ `status='未払' AND amount=<readAmount>` を加え、0件なら再読込して警告または409とする。
  5. 改定金額CAS 0件、将来履歴削除0件を成功扱いせず、当月を含む確定行を拒否または警告する。
- 期待効果:
  - WorkRecord、契約価格履歴、確定状態、請求、BP支払が同じ工数・単価・時点へ揃う。

### R6-05 【P2・新規】運用文書が削除済みprod V10と無条件repairを案内する

- 対象:
  - `CLAUDE.md:43-51`
  - `AGENTS.md:45`
  - `src/main/resources/application.yml:51-53`
- 現象:
  - 文書と設定commentは、prodが削除済み `V10__update_admin_password_bcrypt.sql` を追加適用すると説明する。現実はRepeatable migrationである。
  - `CLAUDE.md` はchecksum mismatch時に対象historyを限定せず `flyway repair` を案内し、固定接続情報の例も含む。
- 改修方法:
  - Repeatable方式へ記述を更新し、旧prod V10専用runbookでは事前backup、version/script/description/checksum allowlist、repair前後history、最終schema、loginの確認を必須化する。
- 期待効果:
  - 操作員が未知のmigration改変を正当化したり、削除済みファイルを探したりする誤操作を防止できる。

## 4. R4-01〜R4-06 / R5-01〜R5-05 再判定

| ID | 判定 | 結果、改修方法、期待効果 |
|---|---|---|
| R4-01 | 解消 | BCrypt hashは実値一致し、既知誤hash救済も維持されている。prod login統合試験で固定する。 |
| R4-02 | 部分解消 | Java化でCREATE ROUTINE依存とV10途中失敗準備は改善。repair後の旧prod分岐が不足列/FKを補わず索引順も誤る。R6-03で全shapeを収束させる。 |
| R4-03 | 未解消 | repair試験は存在するがfixture重複、callback未登録、allowlistなしで有効な旧prod経路を証明しない。R6-02が必要。 |
| R4-04 | 部分解消 | 日次lock順、金額CAS、confirm/approveの一部lockは改善。旧snapshot、reopen、BP status競合、CAS 0無視が残る。R6-04で統一する。 |
| R4-05 | 解消 | Project編集時の顧客応答順競合は閉じている。DOM順序試験で固定する。 |
| R4-06 | 解消 | Contract編集は候補外の退職・無効担当営業を保持する。失敗時復旧はR5-04として残る。 |
| R5-01 | 未解消 | historyだけでなくschemaも補償するJava callbackは追加されたが、旧prod分岐と試験の両方が壊れている。R6-02/R6-03を完了する。 |
| R5-02 | 部分解消 | saveDaily/deleteDailyの逆lockは解消。confirm/approveの旧snapshot、reopen/BP非共有lockが残る。R6-04で閉じる。 |
| R5-03 | 未解消 | SkillTag取得はPromise化されず、既存案件スキルより遅いと空選択を描画する。全依存完了後に行を作り、読込中操作を止める。 |
| R5-04 | 未解消 | Projectは候補失敗をresolveし、Contractはrejected Promiseを永久cacheする。非200をrejectし、失敗時cacheをnullへ戻して再試行可能にする。 |
| R5-05 | 未解消 | UIはsize=1000を要求するがserver上限は500である。検索options APIまたは全page取得と現在参照option補完で501件目以降へ到達可能にする。 |

## 5. MI-01〜MI-30 再判定

| ID | 判定 | 現状、改修方法、期待効果 |
|---|---|---|
| MI-01 | 部分解消 | fresh prodのversion解決、Repeatable、hashは正しい。旧prod repair経路が壊れている。R6-02/R6-03でloginとBP schemaを同時に収束させる。 |
| MI-02 | 部分解消 | Java callbackのlegacy未適用分岐は改善。repair済み旧prodの不足列/FK/UNIQUEと索引順が残る。実shape比較で全旧DBをfreshへ揃える。 |
| MI-03 | 部分解消 | 通知type/menuは改善したが `u.role IS NULL` がfail-openで、mail宛先/件名/dedupeも平文で残る。NULL role拒否、未知type拒否、PII mask/hashで越権表示と漏洩を防ぐ。 |
| MI-04 | 部分解消 | controller/matching scopeは改善。SkillSheet service guardと `BusinessException` 再throwがない。service境界へ集約し全入口を同じ404へ揃える。 |
| MI-05 | 部分解消 | Markdown XSSとtext error描画は改善。API keyのsessionStorageと無固定CDN/SRIなしが残る。server-side secret、依存固定、CSPで露出を縮小する。 |
| MI-06 | 部分解消 | chatのenabled確認はあるがmatching API/pageは無guardで、YAML `ai.enabled`、DB `ai_enabled`、AiConfig、Gemini hardcodeが分裂する。一つの設定源と全層gateへ統一する。 |
| MI-07 | 解消 | Proposal lock後の再検証により同一提案の二重終端成功を防止した。 |
| MI-08 | 解消 | Contract状態変更、通常編集、単価改定は契約行lockを共有し、同一契約のlost updateを防止した。将来改定削除はR6-04の残作業。 |
| MI-09 | 部分解消 | 終了契約の確定済み過去売上は残る。共通期間判定を全集計・候補へ適用し未来月計上を止める。 |
| MI-10 | 部分解消 | 金額CAS、Contract lock、日次lock順は改善。confirm/approveの旧snapshot、reopen、BP status、当月警告が残る。R6-04で同一価格時点へ揃える。 |
| MI-11 | 解消 | cache更新をafterCommitへ移し、rollback時のcache ghostを解消した。 |
| MI-12 | 解消 | WebhookをafterCommitへ移し、rollbackした業務事実の外部送信を防止した。 |
| MI-13 | 部分解消 | 新規見積受注の参照検査は追加済み。Quotation受注とContract draft生成で共通validatorを使い、既存不正データを安定した4xxへ変える。 |
| MI-14 | 解消 | JS/DTOは `additionalRemark` で一致し、旧 `additional` もaliasで受理する。JSON契約試験で固定する。 |
| MI-15 | 未解消 | role互換、menu依存、Dashboard、landing、header quick-addが未統一。role×menu行列と依存graph、allowedMenus連動navigationで設定後の403を防ぐ。 |
| MI-16 | 解消 | 管理者は全menuKeyを受け、contract-document seedも補完され、superuser bypassとSidebarが一致した。 |
| MI-17 | 部分解消 | 営業GET、write制限、403は改善。skill-tagを単独解除できるためengineer/project内部依存が壊れる。menu依存graphまたはcaller別read APIで防ぐ。 |
| MI-18 | 未解消 | form-login 302後のHTMLを `SES.api` がJSON parseする。API専用401 JSON entry pointとredirect/url/Content-Type検査でloginへ誘導する。 |
| MI-19 | 未解消 | `SES.api` は403/5xxをnull resolveし、payrollが成功Toastを出す。非成功をthrowしToast責務を統一する。 |
| MI-20 | 未解消 | Email template、Gantt、AI matching/SkillSheetの操作可能な本番mockが残る。mockをdev flagへ隔離し、本番は空/失敗状態だけにする。 |
| MI-21 | 未解消 | Contract、Invoice、Ganttと候補selectは先頭page/500件までしか扱わない。検索・期間専用APIとpage制御で全件へ到達可能にする。 |
| MI-22 | 未解消 | month、customerId、tab、invoiceId、statusを遷移先controlへ反映しない。初回load前にqueryを適用し、遷移元と同じ対象を表示する。 |
| MI-23 | 部分解消 | 編集時customerId競合は解消。新規modal早期操作、候補失敗成功扱い、customerId必須/存在検証が残る。UI/service両方でfail-closedにする。 |
| MI-24 | 未解消 | freee linkは論理削除と全体UNIQUEが衝突する。active generated keyまたはunlink物理削除で一対一と再連携を両立する。 |
| MI-25 | 解消 | 論理削除済みusernameを含む事前検査により永久再利用禁止を業務エラー化した。並行unique例外変換は追加強化。 |
| MI-26 | 部分解消 | status専用APIとPUTは0/1へ限定。POSTはstatus=2を保存できる。作成時1強制、DTO allowlist、DB CHECKで全経路を0/1へ限定する。 |
| MI-27 | 未解消 | Engineer/SkillTag/CustomerのEntity直受けとDB ENUM/NOT NULL/VARCHAR長が不一致。DB制約同期の書込DTOで不正入力を400にする。 |
| MI-28 | 未解消 | Gemini promptは円の生値へ「万円」を付け、nullも連結する。共通formatterで単位とnull表示を揃え、AI判断の1万倍ずれを防ぐ。 |
| MI-29 | 解消 | `/engineer/form` と `/project/form` はlistへredirectし、不存在templateの500を解消した。 |
| MI-30 | 未解消 | repair試験はDockerで必ず失敗するfixtureで、callbackも未登録である。fresh、legacy、旧prod、V10途中失敗の実MySQL四経路を有効なfixtureでCI実行する。 |

## 6. R2-01〜R2-09 再判定

| ID | 判定 | 結果 |
|---|---|---|
| R2-01 | 部分解消 | Java callback化とlegacy未適用分岐は改善。repair後旧prod分岐、索引順、shape検証、実MySQL試験が残る。 |
| R2-02 | 部分解消 | Repeatable/hashは解消。旧prod historyを安全にallowlist repairしschemaごと補償する経路が残る。 |
| R2-03 | 部分解消 | 確定済み過去売上の消失は解消。未来月の集計・候補分裂が残る。 |
| R2-04 | 部分解消 | 全entity上書き、工数CAS、日次逆lockは改善。旧snapshot、reopen、BP status競合が残る。 |
| R2-05 | 部分解消 | Engineer行lockはあるが、営業scopeが先に作るRR snapshotと異なる提案の並行終了が残る。fresh transaction再導出が必要。 |
| R2-06 | 解消 | `AccessDeniedException` はHTTP/APIとも403になった。 |
| R2-07 | 解消 | 候補完了後の詳細設定と候補外担当補完により、退職・無効担当の消失経路を閉じた。 |
| R2-08 | 未解消 | Helvetica/WINANSI fallbackとCJK fontなし試験skipが残る。CJK font同梱・埋込みと日本語text抽出試験が必要。 |
| R2-09 | 解消 | invalid status keyは主要bundleに追加され、利用者向け文言を返す。 |

## 7. RR-01〜RR-07 再判定

| ID | 判定 | 結果 |
|---|---|---|
| RR-01 | 部分解消 | main V10、Repeatable、hashは正しい。旧prod V10 historyと実schemaの安全な補償が残る。 |
| RR-02 | 部分解消 | Java callbackのlegacy分岐は改善。repair後旧prod、完全shape比較、実MySQL経路が残る。 |
| RR-03 | 部分解消 | 営業GET、write制限、403は改善。動的menu依存が残る。 |
| RR-04 | 部分解消 | Engineer行lockは追加済み。古いRR snapshot、lock順、実並行試験が残る。 |
| RR-05 | 未解消 | Helvetica fallbackと日本語text抽出試験不足が残る。 |
| RR-06 | 解消 | plain text、固定wrapper、sanitizeによりXSS防止とエラー表示を両立した。 |
| RR-07 | 解消 | Contractの要員/案件placeholderは空値で、保存前にも未選択を拒否する。 |

## 8. 推奨改修順

1. R6-01で既存WorkRecord試験を新lock/CAS契約へ合わせ、全量buildをgreenへ戻す。
2. R6-02/R6-03を一体で修正し、実旧prod fixture、callback登録、allowlist repair、完全schema比較を完成させる。
3. R6-04でconfirm/approve/reopen/BP支払/改定削除を同じlock・current read・CAS規約へ統一する。
4. R6-05でAGENTS/CLAUDE/application commentと安全runbookを現在のRepeatable方式へ更新する。
5. R2-05で要員statusをcommit後のfresh transactionから完全再導出する。
6. R5-03〜R5-05とMI-23をまとめ、候補取得の待機、失敗、再試行、500件超をDOM/API試験する。
7. MI-15、MI-17〜MI-22、MI-24、MI-26〜MI-28、R2-08を順次解消する。

## 9. 次回の完了条件

1. `mvn test` が0 failures、0 errorsで成功する。
2. Docker有効CIで両Flyway試験がskipされず成功する。
3. 旧prod V10当時の固定fixtureで、限定validate failure、allowlist repair、callback、migrate、loginが順に成功する。
4. fresh、legacy、旧prod、V10途中失敗の `t_bp_payment` 列、型、索引属性/順、FK規則が一致する。
5. BP行を論理削除した後、同一work_record/layerを再作成できる。
6. 改定×confirm、改定×approve、改定×reopen→再確定、reopen×BP支払、将来改定削除×録工のMySQL並行試験が成功する。
7. 500件超、候補応答逆順、一時失敗でも顧客、スキル、要員、案件、在職/退職担当を保持し再試行できる。
8. API session切れ、403、500が全通信方式でrejectされ、失敗後に成功Toastを出さない。
9. 日本語見積PDFから日本語textを抽出できる。
