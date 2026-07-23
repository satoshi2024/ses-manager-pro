# モジュール横断バグ改修 第6回再レビュー

- 再レビュー日: 2026-07-23
- 基準: `HEAD=49258b6` + 未コミットのworktree変更（R6-01〜R6-05および周辺MI/R5対応）
- 比較元: `2026-07-23-module-integration-fix-recheck-5.md`
- 対象: R6-01〜R6-05、および今回の変更で新たに触れたMI-15/17/18/20/21/22/23/24/26/27/28、R2-05/R2-08、R5-03/04/05
- 方針: コード・履歴・SQL・画面・テストを読み取り専用で確認し、全量テストを実行した。本書以外の業務ファイルは変更していない

## 1. 結論

**現時点ではマージおよびデプロイを推奨しない。**

第5回で赤かった `WorkRecordServiceImplTest`（9件）は緑化したが、**同時に投入したMI-15/MI-18/R2-05の変更が新たに5件のテスト失敗を生み、全量buildは依然として `BUILD FAILURE` である。** R6-01の完了条件（`mvn test` が0 failures / 0 errors）は未達。加えてR6-02〜R6-05はいずれも改善はしたが未完のままP2以下の課題が残る。

「改修完了」との申告に反し、**全量テストは実行されていない**（実行していれば5件の失敗を検知できたはず）。マージ前に必ず全量テストを緑化すること。

### 今回の到達点（改善）

1. `WorkRecordServiceImplTest`（第5回の7 failures / 2 errors）は解消。WorkRecordのContract先行lock・二回目読取・status CASにテストを追随させた。
2. `LegacyDatabaseFlywayCallback` は早期returnを廃し、全経路で列・FKを補償。`idx_bp_payment_work_record` をuk削除の前に作成しFKを保護する順序へ修正した。
3. `FlywayRepairRunbookTest` はcallbackを明示登録し、V10追加分の列・索引・FKを削除して旧prodのレガシー状態を再現するよう書き直した（Docker必須でローカルはskip）。
4. 日本語PDFフォント `fonts/ipaexg.ttf` を同梱・埋込みし、`InvoicePdfServiceImplTest` は日本語テキスト抽出をassertして緑（R2-08/RR-05解消）。
5. Engineer/Customer/SkillTagの書込DTO（allowlist＋Bean Validation）を導入。EngineerSaveDtoは編集対象の全カラムを網羅し、`createdBy` 等の保護フィールドのみ除外（データ欠落なし）。
6. 運用文書を削除済み `V10__update_admin_password_bcrypt.sql` からRepeatable方式へ更新。

### 残るP1問題

1. **全量テストが585件中5 failuresで `BUILD FAILURE`。5件すべて今回の変更が誘発した新規回帰である。**

## 2. 検証結果（全量テスト）

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 結果: **585 tests / 5 failures / 0 errors / 5 skipped / BUILD FAILURE**
- `WorkRecordServiceImplTest`・`ContractServiceImplTest`・`InvoicePdfServiceImplTest`・`EngineerStatusServiceImplTest` は緑。
- `FlywayMigrationSmokeTest` / `FlywayRepairRunbookTest` はDocker不在でskip（実MySQL migrationは未実行）。

### 新規失敗5件と根因

| # | テスト | 期待→実際 | 根因（今回の変更） |
|---|---|---|---|
| 1 | `ProfileApiControllerTest.testChangePasswordUnauthenticated:168` | 3xx→401 | MI-18: `SecurityConfig` に `/api/**` 未認証→401 JSON の `HttpStatusEntryPoint` を追加。テストが302想定のまま。 |
| 2 | `PageControllerEdgeCaseTest.testUnauthenticatedRequestToProtectedEndpoint:101` | 3xx→401 | 同上（`/api/**` 経路）。 |
| 3 | `PageControllerEdgeCaseTest.testUnauthenticatedRequestToNonExistentEndpoint:109` | 3xx→401 | 同上。 |
| 4 | `MobileResponsiveLayoutTest.クイック作成ボタンのラベルは…spanで包まれている` | `quick-add-label` 有→無 | MI-15: `header.html` のクイック作成dropdown全体を `allowedMenus.contains(...)` で条件化。`allowedMenus` が空/nullの文脈（当テストはm_menu無し、GlobalControllerAdviceのfallback）でdropdownごと消失。 |
| 5 | `LifecycleStatusIntegrationTest.S6_準備中契約の削除で要員がBenchに戻る` | Bench→提案中 | R2-05: `EngineerStatusServiceImpl` を `afterCommit` + `REQUIRES_NEW` へ移行。当テストクラスは `@Transactional`（ロールバック）でcommitが起きず、afterCommitが発火しないため状態が更新されない。 |

失敗1〜3はMI-18の**意図した新仕様**であり、テスト側の期待値を401へ更新すれば整合する（テスト遅延）。失敗4・5は仕様と実装の齟齬を示し、単なるテスト修正では済まない可能性がある（下記N-3参照）。

## 3. R6-01〜R6-05 再判定

### R6-01 【P1】WorkRecord試験の緑化 — 部分解消

- `WorkRecordServiceImplTest` は `selectByContractIdAndMonthForUpdate` のstub追加・Contract lock戻り値・status CASへ追随し全件緑化。lock順は「WorkRecord行lock→（新規時のみ）Contract lock→再読取」へ整理された。
- **未達**: 全量buildは新規5失敗で赤。R6-01の本来の目的（buildをgreenへ戻す）は達成していない。
- 追加観点: 新規saveHours経路でBP要員かつ既存recordのとき `syncRootBpAmount` を呼ぶ改善は入ったが、R6-01が求めた「MySQL双方向transaction試験（改定×confirm/approve、reopen×BP支払）」は未追加。

### R6-02 【P1】FlywayRepairRunbookTest — 部分解消

- 対象: `src/test/java/com/ses/migration/FlywayRepairRunbookTest.java`
- 改善: (1) V10追加分の列・uk・FKを明示DROPして真のレガシー状態を再現、(2) `.callbacks(new LegacyDatabaseFlywayCallback())` を明示登録、(3) repair→migrate後にhash一致・uk削除・idx作成をassert。
- 残る課題:
  1. `validateWithResult` で不一致内容を検査せず `repair()` を無条件実行する（R6-02改修方法③のallowlist確認が未実装）。許可外の履歴改変も黙ってrepairしうる。
  2. 旧prod V10適用当時の**実SQL/実checksum**を再現したfixtureではなく、履歴行を手書きINSERTしている。
  3. Docker必須でローカルskip。CIでDockerが無ければ本経路は依然として一度も実行されない。

### R6-03 【P1】Java callbackのrepair後分岐 — 部分解消

- 対象: `src/main/java/com/ses/config/LegacyDatabaseFlywayCallback.java`
- 改善: `hasRealV10` の早期returnを廃止し、全経路で不足列・親FKを補償。索引はV10状態判定に応じ `idx_bp_payment_work_record` を先に作ってから `uk_work_record_layer` をDROPし、FKを支える索引の順序問題を解消。単列work_record_id索引DROPのクエリからidxを除外。
- 残る課題:
  1. 存在確認は**名称のみ**。列型・VARCHAR長・既定値・UNIQUE属性・FK参照先/削除規則・索引列順（R6-03改修方法③）を `information_schema` で比較していない。
  2. `hasRealV10`（78〜100行）が算出されるが以後未使用のデッドコード。判定は下段の `shouldHaveV10Indexes`（116〜123行）で重複して行われる。整理すべき。

### R6-04 【P2】WorkRecord/BP整合 — 部分解消（第5回から小幅前進）

- 改善: `saveHours`/`saveDaily`/`deleteDaily` のContract先行lock・日次lock順統一、`confirmMonth` の契約ID昇順lock＋lock後再取得＋status CAS、`approve` のContract lock。
- 残る課題（R6-04の核が未解決）:
  1. `confirmMonth` の再取得（242行）は**非ロックSELECT**。InnoDB REPEATABLE READでは最初のSELECT（227行）で確立したsnapshotを読むため、lock待機中にcommitされた `revisePrice`/`saveHours` の最新 `payment_amount` を反映できず、旧金額でBPを生成しうる。current readにするには対象WorkRecordを `SELECT … FOR UPDATE`（既存 `selectByContractIdAndMonthForUpdate`）で取得する必要がある。
  2. `reopenMonth` はContract/WorkRecord/BPをlockせず、`updateBatchById`（全entity上書き）で状態を戻す。改定済み確定行を旧値で入力中へ戻し再確定しうる。
  3. `syncRootBpAmount` の金額UPDATEに `status='未払' AND amount=<読値>` のCASが無く、並行で支払済みになった行を後書きしうる。

### R6-05 【P2】運用文書 — 概ね解消

- `AGENTS.md`/`CLAUDE.md`/`application.yml` を `R__update_admin_password_bcrypt.sql`（Repeatable）へ更新し、5手順のrepair runbookを追記。
- 残る軽微点: `CLAUDE.md` のrepairコマンド例に固定接続情報（`root/123456`）が残り、R6-05が求めた「version/script/description/checksumのallowlist必須化」は文章化が弱い。

## 4. 今回の変更で見つかった新規指摘

### N-1 【P1】全量buildが赤 — §2の新規5失敗

- 失敗1〜3: `ProfileApiControllerTest` / `PageControllerEdgeCaseTest` の未認証期待値を401（`/api/**`）へ更新する。
- 失敗4: `MobileResponsiveLayoutTest`、または `header.html` の条件を見直す（N-2/§5参照）。テスト文脈で `allowedMenus` が空でもラベルspan自体は描画されるべきか、テスト側でallowedMenusを与えるかを決める。
- 失敗5: 下記N-3の設計判断とセットで解決する。

### N-2 【P2・新規】ページサイズ上限の全廃（`MyBatisPlusConfig`）

- MI-21対応として `paginationInterceptor.setMaxLimit(500L)` → `-1L`（無制限）へ変更し、UIは各所で `size=-1`（全件）を送るようになった。
- 副作用: **全 `/api/**` ページング endpoint で任意の巨大 `size` が通り、全件取得のガードが消えた**。単一リクエストで全行取得できるため、大規模データ時の性能/メモリ枯渇（DoS）リスクが生じる。
- 提案: 上限撤廃ではなく「全件取得は専用フラグ/専用endpointに限定」か「上限を業務上妥当な値へ引き上げつつ維持」。少なくともContract/Customer等の大表は上限を残す。

### N-3 【P2・新規】要員ステータス更新の非原子化（R2-05 afterCommit化）

- `onProposalCreated`/`onContractActive`/`releaseIfIdle` から `@Transactional` を外し、`afterCommit` + `REQUIRES_NEW` へ移した。呼び出し元commit後に別transactionで状態を再導出する意図（fresh snapshot）は妥当だが:
  1. **原子性の喪失**: 呼出し元のcommit後にafterCommitのREQUIRES_NEWが失敗すると、業務事実はcommit済みなのに要員ステータスだけ旧値のまま残る（サイレント不整合）。失敗時の補償・再試行が無い。
  2. **試験可能性の喪失**: `@Transactional`（ロールバック）な統合試験ではafterCommitが発火せず（失敗5がまさにこれ）、新設計の正しさをこの層で検証できなくなった。
- 提案: 状態遷移を業務トランザクション内に保つか、afterCommitにするなら失敗時のログ+再試行/通知と、commit前提の統合試験（`@Commit` またはTestTransaction手動commit）を用意する。

### N-4 【P3・新規】`common.js` のセッション切れ判定の演算子優先順位

- 追加された `if (response.redirected && response.url.indexOf('/login') !== -1 || contentType.indexOf('text/html') !== -1)` は `&&` が先に束縛されるため、実質 **「text/htmlレスポンスなら常に `/login` へ遷移」** となる。API応答は常にJSONのため通常は無害だが、意図が「redirect先が/login」または「HTMLが返ってきた＝セッション切れ」の両方なら、明示的に括弧で意図を固定すること。

### N-5 【P3・新規】リポジトリ衛生 — 生成物の混入

- ルートに `extract.py`（`.gemini/antigravity/brain` からsubagent transcriptを抽出するスクリプト）と `subagent_reports.txt` が未追跡で残存。改修の成果物ではなく作業スカフォールドのため、コミット対象から除外・削除すること。

## 5. MI/R2/R5 個別再判定（今回触れた分）

| ID | 判定 | 要点 |
|---|---|---|
| MI-15 | 部分解消 | landing統一（`/` index + LoginSuccessHandler→`/`）、sidebar dashboard条件化、header quick-add条件化は実装。ただし空allowedMenusでquick-addが消えテスト失敗（失敗4）。role×menu網羅の確認が要。 |
| MI-17 | 解消 | role-menu保存でengineer/project選択時にskill-tagを強制付与する依存保護を追加。 |
| MI-18 | 部分解消 | `/api/**` 未認証→401 JSONのentry pointを追加（前進）。ただし既存テスト3件が302想定のまま赤（失敗1〜3）。 |
| MI-20 | 解消 | `contract-gantt.js`/`email-template.js` の本番mock fallbackを除去。 |
| MI-21 | 解消（要注意） | `size=-1` 全件取得を許可。ただしN-2の上限全廃リスクを伴う。 |
| MI-22 | 部分解消 | `contract.js`（customerId/salesUserId）・`invoice.js`（month）は初回load前にqueryを反映。他遷移（tab/invoiceId/status）は未確認。 |
| MI-23 | 部分解消 | `project.js` に顧客必須validationを追加、候補失敗をrejectへ変更。他modalの早期操作抑止は未確認。 |
| MI-24 | 解消 | freee link: `unlink` を物理削除化し、再連携前に論理削除済み衝突行を物理削除するmapperを追加。 |
| MI-26 | 部分解消 | 作成時 `status=1` 強制を追加。POST経路のDTO allowlist/DB CHECKによる0/1限定は未実装。 |
| MI-27 | 部分解消 | Engineer/Customer/SkillTagに書込DTO+Validation、Project.statusに `@Pattern` を追加。DB ENUM/長さとの完全同期・全エンティティ網羅は残る。 |
| MI-28 | 解消 | AI promptの円/万円・null連結を三項で是正（`未設定` 表示）。 |
| R2-05 | 部分解消 | afterCommit+REQUIRES_NEWで再導出化。ただしN-3の非原子性・試験不能・失敗5が残る。 |
| R2-08 | 解消 | `ipaexg.ttf` 同梱・埋込み、日本語テキスト抽出testで固定。 |
| R5-03 | 解消 | `project.js` のskillTag取得をPromise化し、編集時に顧客・skillTag・詳細を `Promise.all` で待機。 |
| R5-04 | 解消 | 候補取得失敗時にcacheを `null` へ戻しrejectするよう `project.js`/`contract.js` を修正、再試行可能に。 |
| R5-05 | 解消（N-2依存） | `size=-1` により500件超へ到達可能。ただしN-2の上限撤廃が前提。 |

## 6. 推奨改修順

1. **N-1: 全量buildを緑化する。** 失敗1〜3はテスト期待値を401へ更新。失敗4・5はN-2/N-3の設計判断とセットで解決。**必ず `mvn test` を全量実行して確認する。**
2. N-3: 要員ステータス更新の原子性/試験可能性を決着（業務tx内に戻すか、afterCommit継続なら失敗補償+commit前提試験を追加）。
3. N-2: ページサイズ上限を全廃せず、全件取得を専用経路へ限定。
4. R6-04: `confirmMonth`/`reopenMonth`/`syncRootBpAmount` をContract→WorkRecord→BPの一貫lock・current read・CASへ統一し、改定×confirm/approve/reopen/BP支払のMySQL並行試験を追加。
5. R6-02/R6-03: repair試験にallowlist検証を入れ、callbackのshape収束を列型/長さ/FK規則/索引列順まで検証。`hasRealV10` デッドコードを除去。
6. N-4/N-5: `common.js` 条件の明示化、生成物ファイルの除去。

## 7. 次回の完了条件

1. `mvn test` が0 failures / 0 errorsで成功する（**今回最優先・未達**）。
2. Docker有効CIで両Flyway試験がskipされず、限定validate失敗→allowlist repair→callback→migrate→login→schema比較が順に成功する。
3. fresh / legacy baseline 9 / 旧prod repair / V10途中失敗の `t_bp_payment` 列・型・索引属性/順・FK規則が一致する。
4. 改定×confirm / 改定×approve / 改定×reopen→再確定 / reopen×BP支払 のMySQL並行試験が成功する。
5. 要員ステータス遷移が原子的、または失敗時に補償され、commit前提の統合試験で検証される。
6. ページング endpoint が無制限全件取得のガードを持ちつつ、必要な全件取得を安全に提供する。
7. 生成物・スカフォールドがリポジトリに混入していない。
