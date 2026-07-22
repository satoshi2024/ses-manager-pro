# モジュール横断バグ改修 第2回再レビュー

- 再レビュー日: 2026-07-22
- 基準: main@6f160db + 現在の未コミット作業ツリー
- 比較元: `2026-07-22-module-integration-fix-recheck.md`
- 対象: MI-01〜MI-30、前回追加指摘 RR-01〜RR-07、および今回差分で生じた回帰
- 方針: 読み取り専用でコード、SQL、画面、テストを確認し、本書以外のファイルは変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

30件の再判定は次のとおり。

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 7 | MI-07、MI-08、MI-11、MI-12、MI-14、MI-16、MI-25 |
| 部分解消 | 10 | MI-01、MI-03、MI-04、MI-05、MI-06、MI-09、MI-10、MI-13、MI-17、MI-26 |
| 未解消 | 13 | MI-02、MI-15、MI-18〜MI-24、MI-27〜MI-30 |

前回から確認できた改善:

1. 公開済みmain V10の本文はHEADと同一内容へ戻り、main V10のchecksum改変は解消した。
2. SkillTagは営業へ読取り権限がseedされ、書込みにはロール制限が追加された。
3. 要員行の `FOR UPDATE`、契約modalの空placeholder、削除済みusernameを含む重複検査、不正statusの一部検査が追加された。
4. AIエラー表示は固定HTMLとtext nodeへ分離され、タグ文字列表示の回帰を解消した。

ただし、次の問題がマージ阻止要因として残る。

1. **V43は旧DBが停止するV10/V18より後にあるため、legacy upgradeとして到達不能である。**
2. **prodパスワード更新をV41へ移しただけでは、後からprodへ切り替えるDBと旧prod V10履歴を救えない。**
3. 終端契約の過去売上がDashboardから消える回帰、単価再計算と録工のlost update、契約編集時の担当営業消失が新たに確認された。
4. SkillTag書込み拒否は認可上は動くが、API応答が403ではなく500になる。
5. 要員行ロックは追加されたものの、REPEATABLE READの古いsnapshotを使う経路があり、異なる提案の同時終了は完全には閉じていない。

## 2. 検証結果

### 全量テスト

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 結果: **585 tests / 0 failures / 0 errors / 5 skipped / BUILD SUCCESS**
- 実行時間: 6分39秒
- Dockerが利用できず、実MySQLの `FlywayMigrationSmokeTest` は今回もスキップされた。

### 対象限定確認

- User、Notification、AI関連: 13件、失敗0、エラー0
- User、Contract、EngineerStatus関連: 54件、失敗0、エラー0
- PageRenderingTest: 9件、失敗0、エラー0
- 対象群には重複があるため、上記件数は合算しない。
- 新しいテスト変更はMockitoの呼出差替えが中心で、実トランザクション並行実行、HTTP method別認可、ブラウザの非同期応答順は検査していない。

### Flyway確認

- main + prod locationの `flyway:info` はV1〜V43を一意に解決した。
- version 43のDBを模したbaseline後の `flyway:info` では、prod V41は明確に `Below Baseline` となった。
- したがって、main-onlyでV43まで進んだDBへ後からprod locationを追加しても、既定設定ではV41を実行できない。
- `flyway:info` はSQL実行、既存historyのvalidate、legacy schemaの収束を証明しない。
- localhost:3306には応答があったが、既存ローカルDBをレビューで変更しないためmigrationは実行していない。

### 差分品質

- `git diff --check HEAD` はJava 4ファイルのEOF余分空行を警告した。
- 警告対象: `AiApiController.java`、`AiRestController.java`、`GeminiService.java`、`MailServiceImpl.java`

## 3. 優先改修事項

### R2-01 【P1】V43はlegacy DBが失敗する地点より後にあり、移行処理へ到達できない

- 対象:
  - `src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql:5-6`
  - `src/main/resources/db/migration/V18__add_active_relation_unique_keys.sql:17-29`
  - `src/main/resources/db/migration/V43__legacy_db_bp_payment_upgrade.sql:1-86`
- 現象:
  - baseline version 9の旧DBは、まず不変のV10を実行する。
  - 旧 `t_bp_payment` には `uk_work_record_layer` がないため、V10の `DROP INDEX uk_work_record_layer` で停止し、V43へ到達しない。
  - 仮にV10を手動で通しても、V18がV43より先に `deleted_flag` と `layer_order` を参照するため、旧列不足で停止する。
  - V43は `parent_payment_id` だけを補い、旧DBに不足する `layer_order`、`payee_company_name`、`deleted_flag` 等をV5の最終形へ収束させない。
  - V43の親FKは `ON DELETE SET NULL` であり、空DBのV5が持つRESTRICT相当の制約とも一致しない。
- 改修方法:
  1. V10より前に必ず動く、冪等なFlyway `beforeMigrate` callbackまたは専用legacy前処理locationを用意する。
  2. callbackで旧単列UNIQUEを実名検出して削除し、V5時点で必要な全列、型、既定値、複合 `uk_work_record_layer`、親FKを同じ定義へ揃える。
  3. V10は変更せず、callback後に通常どおりV10、V18以降を実行する。
  4. 低いversionのV9.1を追加する方式を採る場合は、既にV10以降へ進んだDBでignored migrationにならないよう、一時的な `outOfOrder` 運用とvalidate手順を明文化する。
  5. 旧手動005相当schema → baseline 9 → 最新までを実MySQLで自動試験する。
- 期待効果:
  - legacy DBがV10/V18を越え、空DBと同じ多段BP構造・制約へ安全に収束する。

### R2-02 【P1】prod V41は後付けprodと旧prod V10履歴に適用できない

- 対象:
  - `src/main/resources/db/migration-prod/V41__update_admin_password_bcrypt.sql:15-17`
  - `src/main/resources/db/migration/V10__fix_bp_payment_unique_key.sql`
- 現象:
  - main V10本文のchecksum改変は解消した。
  - 一方、main-onlyでV43まで適用後にprodを有効化すると、V41はcurrent versionより低く `Below Baseline` 扱いになり実行されない。
  - 旧prod V10をhistoryへ記録済みのDBでは、現在解決されるmain V10とdescription/checksumが異なり、専用repairなしではvalidateできない。
- 改修方法:
  1. 平文 `admin123` の場合だけ更新する現在の冪等条件を、prod locationのRepeatable migrationへ移す。
  2. 旧prod V10履歴は、descriptionとchecksumを事前確認する専用runbookを作り、対象DBだけ `flyway repair` する。
  3. 空DB main+prod、main最新→prod切替、旧prod V10履歴の3経路を実MySQLで検査する。
- 期待効果:
  - prodを有効化する時期に依存せずseed管理者がBCryptで認証でき、旧本番DBもvalidateできる。

### R2-03 【P2・新規回帰】終端・end_date未設定契約の合法な過去売上までDashboardから消える

- 対象:
  - `MonthlyRevenueCalcServiceImpl.java:51-65,118-131`
  - `SalesPerformanceServiceImpl.java:211-216`
  - `WorkRecordMapper.java:35-43,71-80`
- 現象:
  - 新しい期間判定は、状態が終了/解約かつ `endDate == null` の契約を全月で対象外にする。
  - この判定は確定済みWorkRecordを確認する前に実行されるため、過去に実在した確定売上・粗利もDashboardとCSVから消える。
  - SalesPerformanceとWorkRecord側は同じ契約を依然無期限として扱い、将来計上を続けるため、モジュール間の数値がさらに分裂する。
- 改修方法:
  1. 終了/解約時に検証済み実終了日を必須保存し、既存nullデータを最終確定月または監査済み日付で補正する。
  2. 確定WorkRecordが存在する月は、契約fallback判定より優先して実績額を計上する。
  3. 契約有効月判定を一つの共通サービスへ集約し、Dashboard、SalesPerformance、WorkRecord候補、Exportで共有する。
- 期待効果:
  - 終了後の未来予測は止まり、確定済みの過去売上は失われず、全画面・帳票の金額が一致する。

### R2-04 【P2・新規回帰】単価再計算の全行updateが並行録工を上書きする

- 対象:
  - `ContractServiceImpl.java:427-461`
  - `WorkRecordServiceImpl.java:109-137,197-220,475-533`
- 現象:
  - `revisePrice` は契約行をロックするが、録工、提出、確定は同じ契約行をロックしない。
  - 改定処理はWorkRecord全体を読んだ後、金額だけを変更して `updateById(wr)` する。
  - 読取後に別トランザクションが工数、状態、備考を更新すると、改定側が古い全行を後書きして変更を失わせ得る。
  - 当月からの改定は `applyFrom.isBefore(now)` がfalseのため、当月確定済み実績が残っていても警告されない。
- 改修方法:
  1. 録工、提出、確定、再開、単価改定の全経路で、最初に同じContract行を `FOR UPDATE` する。
  2. 改定再計算は `billing_amount` と `payment_amount` だけを更新する専用SQLにし、必要ならstatus/actualHoursを条件にしたCASを使う。
  3. 確定直前にロック下で最新の価格履歴を再解決する。
  4. `applyFrom` 以降の確定済み実績は当月を含め、拒否または明示警告する。
- 期待効果:
  - 旧単価の確定、工数・備考のlost update、無警告の当月不整合を防止できる。

### R2-05 【P2】要員行ロック後も古いsnapshotでBenchへ戻らない経路がある

- 対象:
  - `EngineerMapper.java:25-26`
  - `EngineerStatusServiceImpl.java:31-68`
  - `ProposalServiceImpl.java:89-125`
- 現象:
  - Engineer行の `FOR UPDATE` は追加され、同一要員の状態更新は直列化されるようになった。
  - ただし外側は先にProposal/Contractをロックし、営業scopeでは `assertAllowedEngineer/Project` の通常SELECTがEngineerロック前にREPEATABLE READ snapshotを作り得る。
  - 二つの異なる提案を同時に見送りにした場合、後からEngineerロックを取った側も古いsnapshotで相手をactiveと判断し、最終active件数が0でもBenchへ戻さない経路が残る。
- 改修方法:
  1. コミット後イベントから別Beanの `REQUIRES_NEW` 再計算を呼び、fresh transactionでEngineerをロックして、稼動契約→提案→Benchの優先順で状態を完全再導出する。
  2. または全関連更新をEngineer-firstのロック順へ統一し、active集合をcurrent readで再確認する。
  3. 同一要員の異なる二提案、二契約、提案作成と終了の並行テストを実MySQLで追加する。
- 期待効果:
  - 要員statusが最終的なコミット済み契約・提案集合と必ず一致する。

### R2-06 【P2・新規回帰】SkillTag書込み拒否が403ではなく500になる

- 対象:
  - `SkillTagApiController.java:53-76`
  - `GlobalExceptionHandler.java:76-79`
  - `V42__seed_missing_role_menu_permissions.sql:14-15`
- 現象:
  - 営業はV42によりmenu filterを通過し、GETは成功する。
  - POST/PUT/DELETEは `@PreAuthorize` により拒否されるが、method securityの `AccessDeniedException` をAPIの汎用 `Exception` handlerが500へ変換する。
  - さらにrole-menu画面で営業のskill-tag権限だけを外すと、要員/案件画面の依存GETが再び403になり、依存menu問題は残る。
- 改修方法:
  1. `AccessDeniedException` 専用handlerを追加し、HTTP 403と `ApiResult(code=403)` を返す。
  2. SkillTagの参照APIと管理書込みAPIを別prefixへ分離するか、HTTP method別のSecurity matcherを定義する。
  3. role-menu保存時にengineer/projectとskill-tag参照権限の依存関係を検証する。
  4. 営業GET=200、営業write=403、許可ロールwrite=200の統合テストを追加する。
- 期待効果:
  - 読取り依存画面を壊さず、書込みだけを正しい403で制限できる。

### R2-07 【P2・新規回帰】契約編集の非同期候補読込で担当営業がNULLへ消える

- 対象:
  - `static/js/modules/contract.js:59-128,205-258`
  - `Contract.java:107-110`
- 現象:
  - 候補4種類のAJAXと契約詳細GETは互いを待たずに動く。
  - 詳細が先に担当営業を設定した後で候補GETが完了すると、selectを `empty()` して再構築し、既存選択と補完optionを消す。
  - `salesUserId` は任意項目かつ `FieldStrategy.ALWAYS` なので、そのまま保存するとNULLがDBへ反映され、契約帰属と営業成績が無警告で失われる。
- 改修方法:
  1. 各候補読込をPromise化し、`Promise.all` 完了後に詳細値を設定する。
  2. 候補読込中は編集・保存を無効化し、失敗時も保存させない。
  3. 再構築時は現在値を保存・復元し、候補外の退職済み担当営業optionも最終段階で保持する。
  4. 遅延順を入れ替えるDOMテストを追加する。
- 期待効果:
  - 通信応答順に依存せず、既存の担当営業帰属を保持できる。

### R2-08 【P2】CJKフォント不在時のHelvetica fallbackで日本語PDFが欠落する

- 対象:
  - `QuotationPdfServiceImpl.java:171-195`
  - `QuotationPdfServiceImplTest.java:68-70`
- 現象:
  - Helvetica + WINANSIは日本語glyphを持たないため、成功扱いのPDFで日本語が欠落する。
  - テストはCJK fontがない環境でskipし、fallbackを検査しない。
- 改修方法:
  1. 日本語対応fontをアプリへ同梱して埋め込む。
  2. 同梱しない場合はHelvetica fallbackを削除し、明示的な帳票生成エラーに戻す。
  3. 生成PDFから会社名、件名、日本語labelを抽出して一致を検証する。
- 期待効果:
  - 「生成成功だが日本語が空白」という静かな帳票破損を防止できる。

### R2-09 【P3・新規】不正ユーザーstatusのメッセージキーが未定義

- 対象:
  - `UserApiController.java:143-146`
  - `src/main/resources/messages*.properties`
- 現象:
  - statusが0/1以外の場合に `error.user.invalidStatus` を返すが、全message bundleに同keyがない。
  - 利用者には説明文ではなくkey文字列がそのまま表示される。
- 改修方法:
  1. 全言語bundleへ同じkeyを追加する。
  2. status=2のMockMvcテストでHTTP 400と表示文を固定する。
- 期待効果:
  - 入力拒否理由が利用者へ明確に伝わり、bundle間の欠落も検出できる。

## 4. MI-01〜MI-30 再判定と残作業

| ID | 判定 | 現状、改修方法、期待効果 |
|---|---|---|
| MI-01 | 部分解消 | main V10復元でchecksum改変は解消。prod V41をRepeatable化し旧prod V10のrepair手順を用意すれば、後付けprodと既存本番の両方が起動できる。 |
| MI-02 | 未解消 | V43はV10/V18より後で到達不能。V10前callbackで旧schemaをV5相当へ完全収束させれば、legacy DBでも多段BPを利用できる。 |
| MI-03 | 部分解消 | 現行通知typeのmenu割当ては改善。`u.role IS NULL` をfail-closed化し、未知typeを拒否し、宛先ログ/dedupeをmaskまたはhash化すれば、越権表示とPII残存を防げる。 |
| MI-04 | 部分解消 | AI入力と候補scopeは追加済み。SkillSheet service自身へguardを置き、`BusinessException` を再throwすれば、全呼出経路で担当外を一貫した404にできる。 |
| MI-05 | 部分解消 | Markdown XSS経路はDOMPurifyで解消。API keyをserver-side管理へ移し、CDN version/SRI、CSP、XSS試験を追加すれば、別の同一origin scriptや供給網からのkey取得も抑止できる。 |
| MI-06 | 部分解消 | 外部Gemini停止とheader送信は改善。`ai.enabled` の意味を統一して全route/pageへ適用し、provider/apiUrl/modelを設定から取得すれば、運用設定どおりに機能と外部送信を停止できる。 |
| MI-07 | 解消 | 同一Proposalをロック後に状態遷移を再検証するため、同じ提案の二重終端成功は防止された。実MySQL並行テストを追加すると再発防止を固定できる。 |
| MI-08 | 解消 | Contractの状態変更、通常編集、単価改定が契約行ロックを共有し、同一契約のlost update経路は閉じた。 |
| MI-09 | 部分解消 | null終了日は一部補完されたが、既存未来日を終了時に短縮せず、終了分岐の日付検証もない。実終了日を必須・検証・上書きし、確定過去実績を優先すれば未来計上と過去消失を同時に防げる。 |
| MI-10 | 部分解消 | 未確定実績の順次再計算は追加済み。録工系と同じ契約ロック、金額のみの部分update、確定直前の再解決を実装すれば、旧単価確定と並行更新消失を防げる。 |
| MI-11 | 解消 | cache更新をafterCommitへ移しrollback時にevictするため、rollback後のcache ghostは解消した。実トランザクション試験で保証を固定できる。 |
| MI-12 | 解消 | WebhookをafterCommitへ移したため、rollbackした業務事実の外部送信は解消した。commit/rollback統合試験が残る。 |
| MI-13 | 部分解消 | 新規見積受注の参照確認は追加済み。Quotation受注とContract draft生成で共通validatorを使い、既存不正データとproposal/project/customer整合も検査すれば、DB 500を安定した4xxへ変えられる。 |
| MI-14 | 解消 | `additionalRemark` がJSとDTOで一致し、旧 `additional` もaliasで互換維持した。JSON契約テストを追加すれば再発を防げる。 |
| MI-15 | 未解消 | role互換、依存menu、必須Dashboard、login遷移、header quick-addが未統一。role×menu行列と依存graphをserver-sideで検証し、landing/header/sidebarも同じ権限源へ揃えれば、設定後の行き止まりを防げる。 |
| MI-16 | 解消 | 管理者は全menuKeyを受け、contract-document seedも補完されたため、superuser bypassとSidebarが一致した。 |
| MI-17 | 部分解消 | 営業GETとwrite制限は改善。AccessDeniedを403化し、参照/管理APIを分離してmenu依存を検証すれば、既存画面を壊さず書込みを制限できる。 |
| MI-18 | 未解消 | fetchはlogin HTMLへの302追従後にJSON parseする。API専用401 entry pointとredirect/Content-Type検査を追加すれば、session切れを確実に再loginへ誘導できる。 |
| MI-19 | 未解消 | `SES.api` が403/5xxをnull resolveし、freee画面が成功Toastを出す。非成功を必ずthrowしToast責務を共通層へ集約すれば、失敗の成功表示を防げる。 |
| MI-20 | 未解消 | Email template、Gantt、AI matchingの操作可能な本番mockが残る。mockを明示dev flag配下へ隔離し、本番は空/失敗状態だけにすれば架空IDへの操作を防げる。 |
| MI-21 | 未解消 | Contract、Invoice、Ganttは先頭100件しか扱わない。ページャまたは期間専用APIを実装すれば、101件目以降へ到達できる。 |
| MI-22 | 未解消 | month、customerId、tab、invoiceId、statusの遷移先反映がない。初回load前にqueryを各controlへ適用すれば、遷移元と同じ対象を操作できる。 |
| MI-23 | 未解消 | Project modalの顧客placeholderは `value="1"` のまま。空値化、候補読込完了まで保存無効、server-side存在確認で顧客1への誤帰属を防げる。Contract modal側の同種RR-07は解消した。 |
| MI-24 | 未解消 | freee linkは論理削除と全体UNIQUEが衝突する。active generated keyへ移すかunlinkを物理削除にすれば、active一対一を守りながら再連携できる。 |
| MI-25 | 解消 | 削除済み行を含むusername検査により、永久再利用禁止ポリシーではDB例外前に業務エラーを返す。削除後同名の実DB試験と並行作成時のunique例外変換を追加すると堅牢になる。 |
| MI-26 | 部分解消 | 専用status APIは0/1限定となり、通常updateはstatusを無視する。ただしPOSTはEntityのstatus=2を保存できる。作成時にstatus=1を強制し、DTO allowlistとDB CHECKを追加すれば全経路を0/1へ限定できる。 |
| MI-27 | 未解消 | Engineer/SkillTag/CustomerのEntity直受けとDB ENUM/NOT NULL/VARCHAR長が不一致。書込DTOへ必須、allowlist、長さ制約を定義してDBと同期すれば、不正入力を安定した400で拒否できる。 |
| MI-28 | 未解消 | Gemini promptは円の生値へ「万円」を付け、nullも連結する。共通金額formatterで円表示または1万円換算し、nullを未設定表示にすればAI判断の1万倍ずれを防げる。 |
| MI-29 | 未解消 | `/engineer/form` と `/project/form` は不存在templateを返す。route削除/redirectまたはtemplate追加と全view smoke testで直URLの500を解消できる。 |
| MI-30 | 未解消 | 実MySQLは空DB mainだけ、かつ今回はDocker skip。main+prod、legacy baseline、既存history validate、最終H2制約同期をCIへ追加すれば、移行経路の破損をリリース前に検出できる。 |

## 5. 前回追加指摘 RR-01〜RR-07 の再判定

| ID | 判定 | 結果 |
|---|---|---|
| RR-01 | 部分解消 | main V10は復元済み。prod V41の後付け適用と旧prod V10 history repairが残る。 |
| RR-02 | 未解消 | V43はV10/V18より後で到達不能かつ列・FK定義が不完全。 |
| RR-03 | 部分解消 | 営業GETとwrite role制限は追加。writeが500になるAPI契約と動的menu依存が残る。 |
| RR-04 | 部分解消 | Engineer行ロックは追加。古いRR snapshotとロック順、実並行試験が残る。 |
| RR-05 | 未解消 | Helvetica/WINANSI fallbackとfontなしtest skipが残る。 |
| RR-06 | 解消 | plain textと固定wrapperへ分離され、XSS防止とエラー表示を両立した。 |
| RR-07 | 解消 | Contract modalの要員/案件placeholderは空値となり、未選択ID 1の送信経路は閉じた。 |

## 6. 推奨改修順

1. R2-01、R2-02、MI-30を先に扱い、実MySQLでlegacy、main+prod、旧historyの三経路を固定する。
2. R2-03、R2-04、MI-09、MI-10をまとめ、実終了日・確定実績・単価・録工の共通トランザクション規約を作る。
3. R2-05で要員statusをfresh transactionから完全再導出し、並行試験を追加する。
4. R2-06、MI-15、MI-17〜MI-19で認可とAPI失敗契約を統一する。
5. R2-07、MI-20〜MI-23で非同期候補、mock、ページング、deep linkを修正する。
6. MI-24、MI-26、MI-27で論理削除、一意性、入力DTO、DB制約を一致させる。
7. R2-08、MI-28、MI-29と不足テストを仕上げる。

## 7. 次回の完了条件

1. 旧手動DBをbaseline 9から最新へ上げ、多段BP二行登録まで実MySQLで成功する。
2. main最新からprodへ切り替えたDBと旧prod V10履歴DBの双方でvalidate、migrate、admin loginが成功する。
3. 終端・endDate未設定の既存契約でも、確定過去売上は残り、未来月は全モジュールで0になる。
4. 単価改定と録工/提出/確定の並行試験で、工数、状態、備考、金額のlost updateが0件になる。
5. 同一要員の異なる二提案・二契約を並行終了し、最終statusがactive集合と一致する。
6. 営業のSkillTag GETは200、writeは403となり、権限変更後も要員/案件画面が壊れない。
7. 契約候補と詳細の応答順を全順序で入れ替えても、担当営業が保持される。
8. API session切れ、403、500が全通信方式でrejectされ、成功Toastを出さない。
9. 101件目、deep link、mockなし空表示、Project placeholderをブラウザ試験で確認する。
10. 日本語見積PDFから日本語textを抽出できる。

