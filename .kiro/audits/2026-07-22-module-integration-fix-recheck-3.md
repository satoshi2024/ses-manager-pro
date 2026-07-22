# モジュール横断バグ改修 第3回再レビュー

- 再レビュー日: 2026-07-22
- 基準: main@6f160db + 現在の未コミット作業ツリー
- 比較元: `2026-07-22-module-integration-fix-recheck-2.md`
- 対象: MI-01〜MI-30、R2-01〜R2-09、RR-01〜RR-07、および今回差分で確認した回帰
- 方針: コード、SQL、画面、テストを読み取り専用で確認し、本書以外のファイルは変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

30件の再判定は次のとおり。

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 8 | MI-07、MI-08、MI-11、MI-12、MI-14、MI-16、MI-25、MI-29 |
| 部分解消 | 11 | MI-01、MI-03〜MI-06、MI-09、MI-10、MI-13、MI-17、MI-23、MI-26 |
| 未解消 | 11 | MI-02、MI-15、MI-18〜MI-22、MI-24、MI-27、MI-28、MI-30 |

前回から確認できた改善:

1. `AccessDeniedException` はHTTP 403と `ApiResult(code=403)` へ正しく変換されるようになった。
2. `error.user.invalidStatus` は全主要message bundleへ追加され、key文字列の露出は解消した。
3. 終端契約でも確定済みWorkRecordがある過去月は、Dashboard/Exportの売上から消えないようになった。
4. 単価再計算はWorkRecord全行updateから金額列だけのupdateへ変わり、工数・状態・備考の直接上書き経路は閉じた。
5. Project modalの仮顧客ID 1は空値となり、`/engineer/form` と `/project/form` は不存在templateではなくlistへredirectする。

一方、次のP1問題が残る。

1. **prod Repeatable migrationが設定するBCrypt文字列は `admin123` と一致せず、seed管理者がログインできない。**
2. **`beforeMigrate.sql` は旧FKが必要とするUNIQUEを先に削除し、さらにV10が必要とする `uk_work_record_layer` も削除するため、legacy upgradeは依然失敗する。**
3. 旧prod V10を記録済みのFlyway historyは、現在のmain V10とdescription/checksumが一致せず、専用repairなしではvalidateを通過しない。

## 2. 検証結果

### 全量テスト

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 結果: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**
- 実行時間: 6分23秒
- `FlywayMigrationSmokeTest` は1件中1件skipであり、実MySQL migrationは実行されていない。

### 対象限定テスト

- User API関連: 10件、失敗0、エラー0
- MonthlyRevenue/Contract関連: 45件、失敗0、エラー0
- Quotation/Page/PDF関連: 14件、失敗0、エラー0、1件skip
- 対象群には全量テストとの重複があるため、件数は合算しない。
- 今回の修正点であるprod hash、legacy callback、SkillTag method認可、status=2、ブラウザのAJAX応答順を直接再現する試験は追加されていない。

### BCrypt実値検証

- 使用クラス: プロジェクトと同じSpring Security 6.3.4の `BCryptPasswordEncoder`
- `matches("admin123", R__...の新hash)` は **false**
- `matches("admin123", 旧prod V10のhash)` は **true**
- 新Repeatableが一度動くとpasswordは既知の誤hashになり、現在の `WHERE password='admin123' OR password IS NULL` では修正版Repeatableからも更新できない。

### Flyway確認

- main + prod locationの `flyway:info` はmain V10、V42、V43、Repeatable migrationを一意に解決した。
- worktree上のV41は削除されており、version順による「main最新後にprodを有効化するとV41がBelow Baselineになる」問題はRepeatable方式により構造上改善した。
- `flyway:info` はcallback SQLの実行成功、BCryptの意味、旧historyのvalidateを検証しない。
- H2 probeではFlywayが `beforeMigrate` callbackを認識したが、MySQL専用routine構文のためH2実行は停止した。これはMySQL成功の証明にはならない。
- 既存localhost MySQLには接続・変更していない。

### 作業ツリー品質

- `git diff --check HEAD` はJava 4ファイルのEOF余分空行を警告した。
- 警告対象: `AiApiController.java`、`AiRestController.java`、`GeminiService.java`、`MailServiceImpl.java`
- Git indexは現在 `V10 -> V41` のrenameを保持したまま、worktreeではV41削除、Repeatableは未追跡という `RD + ??` 状態である。
- このままindexだけをcommitすると、V41を再公開してRepeatableを含めない意図外のcommitになる。

## 3. 優先改修事項

### R4-01 【P1・新規回帰】prod Repeatable migrationのBCrypt hashがadmin123と一致しない

- 対象:
  - `src/main/resources/db/migration-prod/R__update_admin_password_bcrypt.sql:7-10`
- 現象:
  - Repeatableは平文 `admin123` を、BCrypt形式には見えるが `admin123` と一致しない文字列へ更新する。
  - BCrypt認証は必ず失敗し、初期管理者はログインできない。
  - 更新後はpasswordが平文でもNULLでもないため、単にhashを直したRepeatableは対象行を再更新できない。
- 改修方法:
  1. `BCryptPasswordEncoder.matches("admin123", hash)` がtrueになる、実際に検証済みのhashへ置き換える。
  2. 修正版の `WHERE` は平文 `admin123` に加え、今回の既知の誤hashも対象にし、既に誤migrationを適用したDBを自己修復できるようにする。
  3. 対象がseed管理者の場合は、必要に応じて `failed_count=0` と `locked_until=NULL` も同時に戻す。
  4. prod migration試験でDBの保存hashを読み、実際の `BCryptPasswordEncoder.matches` をassertする。
- 期待効果:
  - fresh prod、後からprodへ切り替えたDB、誤hash適用済みDBのすべてでseed管理者が認証できる。

### R4-02 【P1】beforeMigrate callbackがlegacy schemaをV10前の期待形へ作れない

- 対象:
  - `src/main/resources/db/migration/beforeMigrate.sql:14-90`
  - `src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql:5-6`
  - `src/main/resources/db/migration/V18__add_active_relation_unique_keys.sql:17-29`
  - `src/main/resources/db/migration/V43__legacy_db_bp_payment_upgrade.sql:1-86`
- 現象:
  - callbackは旧 `work_record_id` 単列UNIQUEを、代替indexを作る前に削除する。旧FKがそのindexを使用している場合、MySQLは `needed in a foreign key constraint` として削除を拒否する。
  - callbackは `uk_work_record_layer` を削除するが、不変のV10は直後に同indexをDROPするため、V10が存在しないindexで失敗する。
  - callbackはV18が参照する `deleted_flag` を追加しない。
  - `payee_company_name` はV5のVARCHAR(200)に対しVARCHAR(100)で、空DBとlegacy DBが同じschemaへ収束しない。
  - V43は実行時期が遅く、親FKもV5のRESTRICT相当ではなく `ON DELETE SET NULL` を追加する。
  - callbackはV10適用済み判定がなく、正常DBでも毎回CREATE ROUTINE権限を要求し、将来追加された同形UNIQUEをversion履歴外で削除し得る。
- 改修方法:
  1. schema形状と `flyway_schema_history` を確認し、V10未適用のlegacy DBだけを対象にする。
  2. V5と同じ定義で `layer_order`、VARCHAR(200)の `payee_company_name`、`parent_payment_id`、`deleted_flag`、既定値を先に補う。
  3. `uk_work_record_layer(work_record_id, layer_order)` を先に作り、FKを支えるindexを維持した状態で旧単列UNIQUEを削除する。V10がDROPする複合UNIQUEは残す。
  4. V10途中失敗で `idx_bp_payment_work_record` だけ残った状態も検出し、V10を再実行できる形へ戻す。
  5. 親FKをV5と同じ削除規則へ揃え、未公開のV43は重複・不一致を避けるため削除する。
  6. 毎回CREATE ROUTINEを要求するSQL callbackではなく、条件判定できるJava Flyway callbackまたは明示的なlegacy upgrade手順も検討する。
  7. 旧手動005相当 → baseline 9 → 最新を実MySQLで実行し、空DBの最終schemaと比較する。
- 期待効果:
  - legacy DBがV10/V18を越え、再試行可能な形で空DBと同じ多段BP schemaへ収束する。

### R4-03 【P1】旧prod V10 historyはRepeatable化だけではvalidateできない

- 対象:
  - 旧 `db/migration-prod/V10__update_admin_password_bcrypt.sql`
  - 現 `db/migration/V10__fix_bp_payment_unique_key.sql`
- 現象:
  - 旧prod V10を履歴へ記録済みのDBでは、resolved migrationのversion 10が現在のmain V10へ変わる。
  - description/checksumが異なるため、FlywayはcallbackやRepeatableを実行する前のvalidateで停止する。
- 改修方法:
  1. 各対象DBのversion 10 description/checksumを監査し、旧prod V10を実行したDBだけを識別する。
  2. 対象限定の `flyway repair` runbookを用意し、実行前後の履歴とadmin passwordを記録する。
  3. 旧prod historyを再現した実MySQL fixtureでvalidate、repair、migrate、loginまで自動検査する。
- 期待効果:
  - 既存本番DBを履歴の誤認や手作業の推測なしで現行migration構成へ移行できる。

### R4-04 【P2】金額限定update後も録工・確定との競合で旧金額やBP不整合が残る

- 対象:
  - `WorkRecordMapper.java:92-101`
  - `ContractServiceImpl.java:426-459`
  - `WorkRecordServiceImpl.java:109-137,197-220,475-533`
- 改善済み:
  - `updateBillingAndPayment` は金額列だけを更新するため、改定処理が工数、状態、備考を古い値へ戻す経路は解消した。
- 残る問題:
  - 金額updateには `status != '確定'`、読取時の `actual_hours`、version条件がない。
  - 160時間を読んだ後に録工が170時間へ更新しても、改定側が160時間分の金額を後書きできる。
  - confirmが先に確定とBP支払生成を行った後でも、改定側が確定WorkRecord金額だけを更新し、BP支払額と分裂させ得る。
  - 録工/提出/確定は単価改定と同じContract行をロックせず、当月確定済み警告も `isBefore(now)` のため漏れる。
- 改修方法:
  1. 録工、提出、確定、再開、改定の全経路で同じContract行を最初に `FOR UPDATE` する。
  2. 金額updateに `status <> '確定' AND actual_hours = #{readHours}` 等のCAS条件を追加し、0件ならreloadして再計算する。
  3. confirm直前にロック下で最新価格を再解決し、その金額からBP支払を生成する。
  4. applyFrom以降の確定済み実績は当月を含め拒否または明示警告する。
- 期待効果:
  - 工数と金額の組合せ、確定WorkRecord、請求、BP支払が同じ単価・同じ実績へ揃う。

### R4-05 【P2・新規】案件編集と顧客候補のAJAX競合で選択済み顧客が消える

- 対象:
  - `static/js/modules/project.js:5,100-109,199-214,240-300`
  - `templates/project/list.html:117-122`
- 現象:
  - 初期placeholderのID 1は空値へ修正された。
  - しかし顧客候補GETと案件詳細GETは独立して動き、詳細が先にcustomerIdを設定した後で候補GETが完了すると、`empty()` により選択値を消す。
  - saveは案件名だけを確認して `customerId:null` を送信するため、利用者は正常な編集操作で400を受ける。
  - AJAX error表示はvalidationのresponse messageではなく、ネットワークエラーとして扱う。
- 改修方法:
  1. 顧客候補読込をPromise化し、完了後に案件詳細値を設定する。
  2. またはselect再構築前の値を保持し、候補追加後に存在確認して復元する。
  3. 候補読込中は編集・保存を無効化し、保存前にcustomerId必須を明示検査する。
  4. 400の `responseJSON.message` を入力エラーとして表示する。
- 期待効果:
  - 応答順に依存せず顧客選択を保持し、仮ID誤登録と正常編集時の不要な400を両方防止できる。

### R4-06 【P2】契約担当営業の保持は在職候補に含まれる場合だけ成功する

- 対象:
  - `static/js/modules/contract.js:92-107,205-258`
  - `EngineerSalesServiceImpl.java:153-158`
  - `Contract.java:107-110`
- 改善済み:
  - select再構築前の担当営業値を保存し、同IDが在職候補にある場合は復元する。
- 残る問題:
  - 退職・無効化済みの担当営業は候補APIへ含まれず、再構築時に補完optionが消える。
  - 保存すると `FieldStrategy.ALWAYS` により `salesUserId=NULL` が反映され、過去帰属と営業成績を失う。
- 改修方法:
  1. 現在値が候補にない場合は「退職済み/履歴担当」のoptionを再追加して選択を保持する。
  2. または全候補Promise完了後に詳細値と補完optionを設定し、それまで保存を無効化する。
  3. 在職/退職候補と応答順を組み合わせたDOMテストを追加する。
- 期待効果:
  - 通信順と在職状態にかかわらず、明示操作なしに契約帰属が消えない。

## 4. MI-01〜MI-30 再判定と残作業

| ID | 判定 | 現状、改修方法、期待効果 |
|---|---|---|
| MI-01 | 部分解消 | version重複と後付けprodの順序問題はRepeatable化で改善。しかしhash誤りと旧prod historyが残る。検証済みhash、誤hash救済条件、repair試験により全prod経路で起動・loginできる。 |
| MI-02 | 未解消 | callbackは索引順、必須列、型、V10期待形を満たさない。V5完全形へV10前に収束させ、実MySQL legacy試験を追加すれば旧DBでも多段BPを利用できる。 |
| MI-03 | 部分解消 | 現行typeのmenu割当ては改善。`u.role IS NULL` をfail-closed化し、未知type拒否、宛先ログ/dedupeのmask/hash化、UI type registry共有で越権表示とPII残存を防げる。 |
| MI-04 | 部分解消 | AI入力/候補scopeは改善。SkillSheet service自身へguardを置き、`AiRestController` が `BusinessException` を再throwすれば全入口を同じ404境界へ揃えられる。 |
| MI-05 | 部分解消 | Markdown XSSとエラー描画は解消。keyをserver-side管理へ移し、CDN version/SRI、CSP、XSS試験を追加すれば別XSSと供給網からのkey取得も抑止できる。 |
| MI-06 | 部分解消 | 外部Gemini停止とheader送信は改善。match API/pageにも `ai.enabled` を一貫適用し、provider/apiUrl/modelを設定から取得すれば運用設定と機能・外送信が一致する。 |
| MI-07 | 解消 | Proposalをロック後に状態遷移を再検証し、同一提案の二重終端成功を防止した。 |
| MI-08 | 解消 | Contractの状態変更、通常編集、単価改定は契約行ロックを共有し、同一契約のlost updateを防止した。 |
| MI-09 | 部分解消 | 確定済み過去売上の消失は解消。終了日必須・検証・上書きと共通期間判定をDashboard、SalesPerformance、WorkRecord、Analyticsへ適用すれば、過去を残し未来計上を全モジュールで止められる。 |
| MI-10 | 部分解消 | 金額列限定updateで全行上書きは改善。共通Contractロック、CAS、確定直前再解決、当月確定検査により旧単価確定とBP分裂を防げる。 |
| MI-11 | 解消 | cache更新をafterCommitへ移しrollback時にevictするため、rollback後のcache ghostは解消した。 |
| MI-12 | 解消 | WebhookをafterCommitへ移し、rollbackした業務事実の外部送信を防止した。 |
| MI-13 | 部分解消 | 新規見積受注の参照検査は追加済み。Quotation受注とContract draft生成で共通validatorを使い、既存不正データとproposal/project/customer整合を検査すればDB 500を安定した4xxへ変えられる。 |
| MI-14 | 解消 | JS/DTOは `additionalRemark` で一致し、旧 `additional` もaliasで互換維持した。JSON契約テスト追加で再発を固定できる。 |
| MI-15 | 未解消 | role互換、依存menu、必須Dashboard、landing、header quick-addが未統一。server-side role×menu行列と依存graph、権限連動navigationにより設定後の行き止まりを防げる。 |
| MI-16 | 解消 | 管理者は全menuKeyを受け、contract-document seedも補完され、superuser bypassとSidebarが一致した。 |
| MI-17 | 部分解消 | 営業GET、write制限、403応答は改善。参照/管理prefixの分離またはmenu依存検証により、動的権限変更後も要員/案件画面を壊さず書込みを制限できる。 |
| MI-18 | 未解消 | fetchはlogin HTMLへの302追従後にJSON parseする。API専用401 JSON entry pointとredirect/Content-Type検査でsession切れを確実にloginへ誘導できる。 |
| MI-19 | 未解消 | `SES.api` は403/5xxをnull resolveし、payrollが成功Toastを出す。非成功を必ずthrowしToast責務を共通化すれば失敗の成功表示を防げる。 |
| MI-20 | 未解消 | Email template、Gantt、AI matchingの操作可能な本番mockが残る。mockを明示dev flagへ隔離し、本番を空/失敗状態だけにすれば架空IDへの操作を防げる。 |
| MI-21 | 未解消 | Contract、Invoice、Ganttは先頭100件しか扱わない。ページャまたは期間専用APIで101件目以降へ到達できる。 |
| MI-22 | 未解消 | month、customerId、tab、invoiceId、statusの遷移先反映がない。初回load前にqueryを各controlへ適用すれば、遷移元と同じ対象を操作できる。 |
| MI-23 | 部分解消 | 顧客1の誤帰属経路は閉じた。候補完了まで保存無効、customerId必須検査、serviceで顧客存在確認、AJAX順制御により正常編集の400も防げる。 |
| MI-24 | 未解消 | freee linkは論理削除と全体UNIQUEが衝突する。active generated keyへ移すかunlinkを物理削除にすれば、一対一を守りながら再連携できる。 |
| MI-25 | 解消 | 削除済みusernameを含む事前検査により、永久再利用禁止ポリシーではDB例外前に業務エラーを返す。並行作成のunique例外変換は追加強化として残る。 |
| MI-26 | 部分解消 | status専用APIと文言は修正済み。ただしPOSTはstatus=2を保存可能。作成時status=1強制、DTO allowlist、DB CHECKで全書込経路を0/1へ限定できる。 |
| MI-27 | 未解消 | Engineer/SkillTag/CustomerのEntity直受けとDB ENUM/NOT NULL/VARCHAR長が不一致。書込DTOをDB制約と同期すれば不正入力を安定した400で拒否できる。 |
| MI-28 | 未解消 | Gemini promptは円の生値へ「万円」を付け、nullも連結する。共通formatterで円表示または1万円換算し、nullを未設定表示にすればAI判断の1万倍ずれを防げる。 |
| MI-29 | 解消 | `/engineer/form` と `/project/form` はlistへredirectし、不存在templateによる500を解消した。302/Location試験追加で公開routeを固定できる。 |
| MI-30 | 未解消 | main+prod、legacy baseline、旧history、callback、Repeatable hashを実MySQLで検査していない。三経路CIと最終schema比較によりmigration破損をリリース前に検出できる。 |

## 5. R2-01〜R2-09 再判定

| ID | 判定 | 結果 |
|---|---|---|
| R2-01 | 未解消 | callbackは認識されるが、索引順、deleted_flag、型、V10期待形、恒常的CREATE ROUTINE権限が未解決。 |
| R2-02 | 部分解消 | V41をRepeatableへ置換する方向は正しいが、hash誤りと旧history repairが残る。 |
| R2-03 | 部分解消 | 確定済み過去売上の消失は解消。未来月のSalesPerformance/WorkRecord/Analyticsとの分裂が残る。 |
| R2-04 | 部分解消 | 全行上書きは金額限定updateで解消。録工/確定とのロック・CAS・BP整合が残る。 |
| R2-05 | 部分解消 | Engineer行ロックはあるが、営業scopeが先に作るRR snapshotと異なる提案の並行終了が残る。commit後のfresh transaction再導出が必要。 |
| R2-06 | 解消 | `AccessDeniedException` は専用handlerによりHTTP/APIとも403になった。 |
| R2-07 | 部分解消 | 在職担当の値復元は追加。退職・無効担当と全候補完了前編集が残る。 |
| R2-08 | 未解消 | Helvetica/WINANSI fallbackとCJK fontなしtest skipが残る。 |
| R2-09 | 解消 | invalid status keyは主要bundleに追加され、利用者向け文言を返す。 |

## 6. RR-01〜RR-07 再判定

| ID | 判定 | 結果 |
|---|---|---|
| RR-01 | 部分解消 | main V10は不変、V41順序問題はRepeatable化で改善。hashと旧prod historyが残る。 |
| RR-02 | 未解消 | callback/V43でもlegacy V10/V18経路は通らず、最終schemaも一致しない。 |
| RR-03 | 部分解消 | 営業GET、write role制限、403は改善。動的menu依存が残る。 |
| RR-04 | 部分解消 | Engineer行ロックは追加済み。古いRR snapshotとロック順、実並行試験が残る。 |
| RR-05 | 未解消 | Helvetica fallbackと日本語text抽出試験不足が残る。 |
| RR-06 | 解消 | plain textと固定wrapperへ分離し、XSS防止とエラー表示を両立した。 |
| RR-07 | 解消 | Contract modalの要員/案件placeholderは空値となり、未選択ID 1の送信経路は閉じた。 |

## 7. 推奨改修順

1. R4-01の正しいhashと誤hash救済を先に直し、prod login試験を追加する。
2. R4-02、R4-03、MI-30をまとめ、legacy、fresh main+prod、late prod、旧prod historyの実MySQL経路を完成させる。
3. Git indexを再stageし、V41がなくRepeatableが含まれる最終diffを確認する。
4. R4-04、MI-09、MI-10でContract lock、WorkRecord CAS、確定、BP支払、期間判定を統一する。
5. R2-05で要員statusをcommit後のfresh transactionから完全再導出する。
6. R4-05、R4-06、MI-23で候補AJAXをPromise化し、応答順と退職担当を含むDOM試験を追加する。
7. MI-15、MI-17〜MI-22、MI-24、MI-26〜MI-28、R2-08を順次解消する。

## 8. 次回の完了条件

1. Repeatable適用後のDB hashが `admin123` と一致し、既知誤hash適用済みDBも自動修復される。
2. 旧prod V10履歴DBが監査済みrunbookでvalidate、migrate、admin loginまで成功する。
3. 旧手動DBをbaseline 9から最新へ上げ、多段BP二行登録まで実MySQLで成功する。
4. 空DBとlegacy DBの `t_bp_payment` 列、型、index、FK削除規則が一致する。
5. 録工、単価改定、確定を並行実行しても工数、WorkRecord金額、請求、BP支払が一致する。
6. 終端null-end契約の過去確定額は残り、未来月は全集計・候補で0になる。
7. 同一要員の異なる二提案・二契約を並行終了し、最終statusがactive集合と一致する。
8. Project/Contract候補と詳細の応答順を入れ替えても、顧客と在職・退職担当営業が保持される。
9. 営業SkillTag GET=200、write=403となり、動的権限変更後も要員/案件画面が壊れない。
10. API session切れ、403、500が全通信方式でrejectされ、失敗後に成功Toastを出さない。
11. 日本語見積PDFから日本語textを抽出できる。

