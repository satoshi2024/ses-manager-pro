# 第7回監査（A7-01〜A7-27）対応の再レビュー

- 再レビュー日: 2026-07-23
- 基準: commit `41924ea`（A7-08/09/11/12ほか）＋ 未コミットのworktree変更（残りのA7対応）
- 比較元: `2026-07-23-full-audit-round7.md`
- 方針: コード・差分・テストを確認した。検証のため `EngineerApiController` のコンパイルエラー1行のみ一時的に仮修正して全量テストを実行し、**実行後に仮修正は元へ戻した**（worktreeは納品状態のまま。本書以外の業務ファイルは変更していない）。

## 1. 結論

**現時点ではコミット・マージ・デプロイのいずれも不可。**

1. **ビルドがコンパイルエラーで失敗する**（R7-01）。`mvn test` は本体コンパイルの時点で停止する。つまり**改修者は納品前に一度もビルドしていない**。
2. コンパイルエラーを1行仮修正して全量実行した結果は **585 tests / 22 failures / 22 errors / BUILD FAILURE**（R7-02）。新しいコンストラクタ・シグネチャ・依存へのテスト追随がほぼ行われていない。
3. 実装内容自体の質は前回までより高い（A7の27件中、**解消14件・部分解消9件・退行/未解消1件・判定保留3件**）。ただし新規の退行が4件ある（ロック順逆転・請求一覧の表示件数退行・ログイン遷移404・メニューキャッシュの権限反映）。

### 判定サマリ

| 判定 | 件数 | ID |
|---|---:|---|
| 解消 | 14 | A7-02, A7-03, A7-08, A7-09, A7-10, A7-12, A7-14, A7-15, A7-19, A7-20, A7-21, A7-22, A7-25, A7-27 |
| 解消（テスト赤/未追加のため確認保留） | 3 | A7-01, A7-26, A7-06(鮮度面) |
| 部分解消 | 9 | A7-04, A7-05, A7-07, A7-11, A7-13, A7-17, A7-18, A7-23, A7-24 |
| 退行 | 1 | A7-16 |

## 2. 検証結果（全量テスト）

- 実行: `.\apache-maven-3.9.6\bin\mvn.cmd test`
- 納品状態: **COMPILATION ERROR で即失敗**（`EngineerApiController.java:75` 型不一致）
- 仮修正後: **585 tests / 22 failures / 22 errors / 5 skipped / BUILD FAILURE**

### 赤の内訳と根因

| グループ | 件数目安 | 根因 |
|---|---|---|
| `InvoiceServiceImplTest`（NPE多数） | 約20 | A7-07/A7-05で追加した `@Autowired dataScopeService` / `workRecordMapper` が `@InjectMocks` テストへモック追加されていない。`resolvePaymentStatus` の3引数化にもテスト未追随（コンパイルエラー1件）。 |
| `SalesActivityApiControllerTest`（200→403）・`CandidateApiControllerTest`（403→200） | 8 | A7-14の `MenuCacheService`（TTL 60秒）がテスト間で権限状態をキャッシュし、テストが直接SQLで変更した `t_role_menu` を反映しない（R7-06）。 |
| `WorkRecordServiceImplTest`（approve/reject） | 3 | approve/reject の初回読取を `selectByIdForUpdate` に変えたが、テストのstubが `getById` のまま（notFound2で失敗）。 |
| `ContractRenewalServiceImplTest` | 3 | コンストラクタに `EngineerSalesService`・`ApplicationContext` を追加したがテスト未更新（テストコンパイルエラー）。 |
| `InvoicePdfServiceImplTest` / `SkillSheetGeneratorTest` | 3 | `PdfFontUtils` 依存化にテスト未追随（直接 `new` していてnull→PDF生成失敗）。 |
| `MonthlyClosingServiceImplTest`（summary系2件） | 2 | 期待1件→実際0件。A7-26 `lockConfig`（insert-if-absent）導入との関連を要調査。 |

## 3. 新規指摘（R7-01〜R7-11）

### R7-01 【P1】本体がコンパイルエラーでビルド不能

- 対象: `controller/api/EngineerApiController.java:38,75`
- 現象: A7-11対応で `Page<Engineer> page` の宣言を誤って `Page<EngineerListDto>` に変更した。75行の `engineerService.page(page, queryWrapper)` は `Page<Engineer>` を要求するため型不一致でコンパイル失敗。この変数はDB取得用で、DTO変換は78行以降が別変数（`dtoPage`）で行っている。
- 改修方法: 38行の型を `Page<Engineer>` に戻す（1行）。**修正後は必ず `mvn test` を全量実行してから納品する**（前回・前々回と同じ指摘の再発。「全量テスト実行」を改修完了の定義に含めること）。
- 期待効果: ビルド復旧。以降の全検証が可能になる。

### R7-02 【P1】テストコードが新実装へ未追随（44件の赤）

- 対象: §2の表の各テストクラス
- 改修方法:
  1. `InvoiceServiceImplTest`: `@Mock DataScopeService`（`assertAllowedCustomer` は既定でno-op）と `@Mock WorkRecordMapper` を追加。`resolvePaymentStatus` の呼び出しを3引数に更新し、「未送付+入金0→未送付を維持」の新ケースを追加。
  2. `ContractRenewalServiceImplTest`: 新コンストラクタ引数（`EngineerSalesService`・`ApplicationContext`）のモックを追加し、`processSingleRenewal` の per-item tx・退職営業→NULL・削除済みドラフトの再生成抑止をテストする。
  3. `InvoicePdfServiceImplTest` / `SkillSheetGeneratorTest`: 実インスタンスの `PdfFontUtils` を渡す。
  4. `WorkRecordServiceImplTest`: approve/reject の `selectByIdForUpdate` stubを追加（ただしR7-03の設計判断を先に確定してから）。
  5. `SalesActivity`/`Candidate` のAPIテスト: R7-06の解決（テストからのキャッシュ無効化）とセットで緑化。
  6. `MonthlyClosingServiceImplTest`: 期待1→0の2件は根因を特定して直す（lockConfig のモック相互作用の可能性）。
- 期待効果: `mvn test` 0 failures / 0 errors。

### R7-03 【P2・新規退行】approve/reject のロック順が逆転し、デッドロックを新設した

- 対象: `service/impl/WorkRecordServiceImpl.approve/reject`
- 現象: A7-06対応で**最初の読取**を `selectByIdForUpdate` に変えたため、ロック順が「WorkRecord→Contract」になった。`saveHours`/`saveDaily`/`confirmMonth` はR6で確立した「Contract→WorkRecord」順のため、要員が日次保存中にマネージャーが同じ月次を承認するとAB-BAデッドロックが成立しうる（InnoDBが片方をロールバック→未ハンドリングの `DeadlockLoserDataAccessException` で500）。
- 改修方法: 監査の元提案どおり、**初回読取は普通読みに戻し、Contractロック後の再読取（584行）を `selectByIdForUpdate` にする**。これで順序は Contract→WorkRecord に統一され、かつ再読取が current read になり鮮度も確保される。reject も同様。テストは「lock順: 月次締め→Contract→WorkRecord」の呼び出し順を検証する。
- 期待効果: A7-06の目的（最新金額でのBP生成）をデッドロックを増やさずに達成する。

### R7-04 【P2・新規退行】請求一覧のページャは描画されず、表示が100件→20件に減っただけ

- 対象: `static/js/modules/invoice.js`（`SES.pagination.render(...)` 呼び出し）、`templates/invoice/list.html`（`#pagination` 要素）
- 現象: `SES.pagination` は **common.js に存在しない**（今回も追加されていない）。`if (SES.pagination)` ガードにより無言でスキップされ、ページャは永遠に出ない。一方でページサイズは100→20に変更されたため、**21件目以降の請求書に到達する手段が消えた**（A7-22の意図と真逆の退行）。
- 改修方法: `SES.pagination.render(elementId, current, pages, onClick)` を common.js に実装する（Bootstrap の `.pagination` マークアップで前へ/次へ+ページ番号を描画する小関数）。実装するまでの間はガード付きスキップではなく、少なくとも旧 size=100 を維持する。他画面（契約一覧等）の既存ページャ実装があればそれを共通化して使う。
- 期待効果: 請求一覧が全件へ到達可能になり、「静かに機能しないコード」が残らない。

### R7-05 【P2・新規退行】ログイン後フォールバックが主要メニューで404へ遷移する

- 対象: `controller/page/LoginPageController.index`
- 現象: A7-16対応で `m_menu.path_prefix` へのリダイレクトに変えたが、`path_prefix` は**権限判定用のURL前置詞であり着地可能なルートではない**。`/engineer`・`/customer`・`/proposal`・`/contract`・`/user` にはコントローラーのマッピングが存在せず（実ルートは `/engineer/list`、`/proposal/kanban` 等）、dashboard権限を持たないロールはログイン直後に**404エラーページ**へ落ちる。旧実装（固定チェーン）は実URLへ飛べていたので明確な退行。
- 改修方法: 次のいずれかに決める。(案A) `m_menu` に「ランディングURL」列（または menuKey→URL の定数Map）を持たせ、そこへリダイレクトする。(案B) 各Pageコントローラーへ「bare prefix → 一覧へのredirect」を追加する（`/engineer` → `/engineer/list` 等）。案Bは MenuPermissionFilter のprefix一致とも整合しやすく推奨。sort_order 先頭が dashboard であることに依存した「dashboard優先」も、明示的な「dashboard を最優先する」分岐として残すこと。
- 期待効果: どのロール×メニュー構成でもログイン直後に404/403へ落ちない（A7-16の本来の狙い）。

### R7-06 【P2】MenuCacheService のキャッシュが権限変更の即時反映とテスト分離を壊す

- 対象: `service/MenuCacheService`（TTL 60秒）、`SalesActivityApiControllerTest`・`CandidateApiControllerTest` の赤8件
- 現象: 同一Springコンテキストを共有するテスト群では、先行テストが載せたキャッシュが後続テストへ漏れ、SQLで直接 `t_role_menu` を変更するテストが即時反映を前提に書かれているため 403/200 が反転する。本番でも、`RoleMenuApiController.replace` 以外の経路（SQL直接変更・複数インスタンス構成）では最大60秒権限が古いまま。
- 改修方法:
  1. テスト影響: `@TestConfiguration` でTTL=0（または各テストの `@BeforeEach` で `invalidate()`）を注入できるよう、TTLをプロパティ化（`menu.cache.ttl-ms`、test profileでは0）する。
  2. 本番影響: 「保存経由以外の権限変更は最大60秒遅延する」ことを設計コメントとAGENTS.mdに明記する（許容範囲の判断を記録）。
- 期待効果: キャッシュの性能効果（A7-14）を保ったままテストが決定的になり、反映遅延が明文化される。

### R7-07 【P3】freee refresh の @Transactional(REQUIRES_NEW) は自己呼び出しで無効

- 対象: `service/impl/FreeeIntegrationServiceImpl.get()` → `refresh()`
- 現象: `refresh()` に `@Transactional(REQUIRES_NEW)` を付けたが、呼び出し元は**同一クラス内の `get()`**のためSpringプロキシを経由せず、アノテーションは効かない。`selectLatestForUpdate` の FOR UPDATE はautocommitで即時解放され、A7-18①（refresh直列化）は実質未達。同じworktree内で `MailServiceImpl` はself-injection、`ContractRenewalServiceImpl` は `applicationContext.getBean` で正しく回避しており、ここだけ漏れた。
- 改修方法: 既存2箇所と同じ手法（self-injection か `TransactionTemplate`）で refresh をプロキシ経由にする。`TransactionTemplate` の方が明示的で推奨。
- 期待効果: リフレッシュトークンのローテーション競合による連携恒久破損を実際に防げる。

### R7-08 【P3】ガント改修の半分は実効性がない

- 対象: `static/js/modules/contract-gantt.js`
- 現象:
  1. `startDate=...&endDate=...` をクエリに付けたが、**`/api/contracts` にそのパラメータは存在しない**（あるのは `endDateFrom`/`endDateTo`）。サーバーは黙って無視し、従来どおり先頭1000件を返す。「期間フィルタ」は見かけだけ。
  2. ハードコード日付が `'2026-04-01'`〜`'2027-03-31'` に置き換わっただけで残存（コメントに "or from a date picker" とあるがピッカーは無い）。
  3. `SES.i18n.t('js.gantt.maxLimitReached', '上限1000件…')` — `t()` の第2引数は**フォールバック文字列ではなくプレースホルダー引数**。キー未定義なら生キーが表示される。
  4. 改善済みの点: 開始日欠損の除外+件数表示、終了日未定のオープンバー化、>1000件の警告自体は有効。
- 改修方法: サーバー側に期間パラメータ（表示範囲と重なる契約: `start_date <= 範囲末` AND (`end_date` IS NULL OR `end_date >= 範囲頭`)）を実装してから接続する。表示範囲は「今日を含む年度」をJSで動的算出し、ハードコードを廃止。`js.gantt.maxLimitReached` キーを全ロケールの messages へ追加。
- 期待効果: 期間絞り込みが実際に機能し、2027年度以降も保守なしで正しく表示される。

### R7-09 【P3】新設メッセージキーが未定義（生キー表示）

- 対象: `error.common.optimisticLock`（changeBpPaymentStatus CAS 0件時）、`error.payroll.invalidType`（statements type検証）、`js.gantt.maxLimitReached`
- 現象: いずれも `messages*.properties` に存在しない。`use-code-as-default-message: true` のため画面にはキー文字列がそのまま出る。
- 改修方法: 3キーを ja/en/zh_CN/ko の全ロケールへ追加する（既存の I18n 欠落検出テストがあるなら対象へ含める）。
- 期待効果: 競合・入力エラー時に利用者向けの文言が表示される。

### R7-10 【P3】文書対応が半分（CLAUDE.md未更新・AGENTS.md新表に不正確な記述）

- 対象: `CLAUDE.md`、`AGENTS.md`
- 現象:
  1. A7-13の指摘対象のうち **CLAUDE.md が未更新のまま**（「CSRFは/api/**で無効」「ロールは4種固定ENUM」「V1〜V14」が残存）。Claude Code が読む方のファイルが古いままでは片手落ち。
  2. AGENTS.md に追加された表に不正確な記述がある: `SES.csrf.setup()`（実際は `SES.csrf.token()/header()`）、`config/DataScopeConfig`（存在しないファイル。実体は `DataScopeServiceImpl` + `scope.sales-own-data-only`）。
  3. 表内の「A7-05未対応」「A7-03フォント未埋め込み」等の注記は、今回のworktree変更で対応済みになる項目のため、**コミット時に記述が事実と矛盾する**。
- 改修方法: CLAUDE.md の該当3箇所を AGENTS.md と同内容へ更新。AGENTS.md の誤記2点を修正し、「未対応」注記は改修のコミットと同時に現状へ書き換える（文書更新を改修コミットに含める運用）。
- 期待効果: 2つの指示書が一致し、次のAI改修が正しい前提で動く。

### R7-11 【P3】残置物・軽微な仕上げ漏れ

- 対象と改修方法:
  1. `QuotationPdfServiceImpl:172` に旧 `resolveCjkFont` privateメソッドが未使用のまま残存 → 削除。
  2. `PdfFontUtils` はA7-17の「キャッシュ」が未実装（毎回TTF読込+BaseFont生成のまま） → 初回生成した `BaseFont` をフィールドにキャッシュ。
  3. `InvoiceServiceImpl.sendReminders` の「入金済のため対象外」行のインデントが崩れている → 整形。
  4. `changeBpPaymentStatus` に `@Transactional` が無く、`assertOpenForUpdate` の締めロックがメソッド終了前に解放される（締めチェックが「その瞬間の判定」に弱化） → `@Transactional` を付与し、締めロック→CAS更新を同一トランザクションにする。あわせて `BpPaymentServiceImpl` の `addLayer`/`updateLayer`/`deleteLayer` はA7-05の締めチェックが**未対応のまま** → 同じ「workRecordId→workMonth→assertOpenForUpdate」を追加する。
  5. `syncRootBpAmount` の金額UPDATEに `status='未払'` 条件が依然として無い（A7-05③） → CAS条件を追加。
  6. 多数のファイルがLFで保存されGitがCRLF変換警告を出している → コミット前に改行を正規化（.gitattributes があればそれに従う）。
  7. A7-24残: AIエラー応答の `e.getMessage()` 露出と共通金額フォーマッタは未対応（優先度低・次回へ持ち越しで可）。

## 4. A7-01〜A7-27 個別判定

| ID | 判定 | 要点 |
|---|---|---|
| A7-01 | 解消（確認保留） | `/` と `/api/profile/**` を `authenticated()` 化し、共通経路の一覧コメントも追加。**指定した統合テスト（要員login→/my/timesheet着地、パスワード変更200）は未追加**のため、ビルド緑化後に追加して固定すること。 |
| A7-02 | 解消 | 6箇所すべて `SES.toast.success/warning` へ修正。文言の i18n 化は未対応（軽微）。 |
| A7-03 | 解消 | `PdfFontUtils` でCJKフォント埋め込み+`<br>`/`</p>`の改行整形。日本語テキスト抽出テストは未追加。 |
| A7-04 | 部分解消 | 締結済+file_id時のPDF取得を実装。締結証明書は未実装のまま、ダウンロード失敗は握りつぶし（ログ無し）。「証明書は未対応」の明示が残タスク。 |
| A7-05 | 部分解消 | `changeBpPaymentStatus` に締めチェック+状態CAS+冪等化 ✔。ただし R7-11-4/5（@Transactional無し・層CRUD未対応・syncRootBpAmount CAS無し）が残る。 |
| A7-06 | 部分解消+新規欠陥 | 読取鮮度は確保されたが、手段が「初回読取のFOR UPDATE化」だったためロック順が逆転（R7-03）。再読取側をFOR UPDATE化する方式へ変更要。 |
| A7-07 | 部分解消 | 一覧のクエリレベルフィルタ+主要更新系/detailのassert ✔。`agingDetail` のコメントアウト行は**そのまま残置**、`listPayments`/`listReminders`/`aging`/`agingExport` は未対応。設計判断（案A/案B）の明文化も無し。テスト未追随でNPE約20件（R7-02）。 |
| A7-08 | 解消 | per-item REQUIRES_NEW（getBeanでプロキシ経由）・失敗続行+通知・退職営業→NULL・削除済み含む再生成抑止、いずれも監査提案どおり。テスト追随のみ残（R7-02）。 |
| A7-09 | 解消 | `isTargetInMonthWithActual` へ一本化し、ローカル判定を削除。ダッシュボードと突合する集計テストは未追加。 |
| A7-10 | 解消 | JS全補間点に `SES.escapeHtml`。サーバー側sanitizeは「プレビューは平文エスケープ前提」の設計コメントで決着（許容）。 |
| A7-11 | 部分解消 | `PageUtils.safePage` を9コントローラーへ展開（既定サイズも維持）✔。ただし Engineer で型を誤りビルド不能（R7-01）。全一覧APIへの size=-1 検証テストは未追加。 |
| A7-12 | 解消 | 未送付維持+`isOverdue`/`applyOverdueFilter` 一元化、一覧・通知・督促が同一口径に。bulk側のインデント乱れのみ（R7-11-3）。 |
| A7-13 | 部分解消 | AGENTS.md はV42/CSRF/5ロール/要員動線/モジュール表まで更新 ✔。CLAUDE.md 未更新+表に誤記（R7-10）。 |
| A7-14 | 解消 | `MenuCacheService`（60秒TTL+保存時invalidate）でフィルター/アドバイス/ログイン遷移を共通化。テスト分離とTTLプロパティ化が必要（R7-06）。 |
| A7-15 | 解消 | fail-openを設計判断として明文化（監視前提を記述）。 |
| A7-16 | 退行 | データ駆動化の方向は正しいが、`path_prefix` は着地不能なURLのため主要メニューで404（R7-05）。 |
| A7-17 | 部分解消 | 4箇所+電子契約が `PdfFontUtils` へ集約 ✔。BaseFontキャッシュ未実装・Quotationに旧メソッド残置（R7-11-1/2）。 |
| A7-18 | 部分解消 | type allowlist・saasRestTemplate(5s/15s)・通常整形 ✔。refresh直列化は自己呼び出しで無効（R7-07）。 |
| A7-19 | 解消 | self-injection+@Asyncで真の非同期化、直接new時は同期fallback（既存テスト互換）。BusinessException化 ✔。応答が常にQUEUEDになる仕様変化はUI文言で吸収要検討。 |
| A7-20 | 解消 | モック行・`window.matchAI` とも削除。 |
| A7-21 | 解消 | `fix.py` 削除、独白コメント除去、同期ログを処理数/更新数の2値に修正。 |
| A7-22 | 解消 | `recalcPaymentStatus` が未送付を維持。境界テストの3引数化が未追随（R7-02）。 |
| A7-23 | 部分解消 | 開始日欠損の除外・オープンバー・>1000警告 ✔。期間パラメータは実在せず無効、ハードコード日付残存、i18n誤用（R7-08）。 |
| A7-24 | 部分解消 | 万円→円の単位修正 ✔（下限にも単位付与）。共通フォーマッタ・`e.getMessage()` 露出は未対応。 |
| A7-25 | 解消 | `CredentialsContainer` 実装でセッション内ハッシュを消去。 |
| A7-26 | 解消（確認保留） | `lockConfig`（insert-if-absent→FOR UPDATE）実装 ✔。`MonthlyClosingServiceImplTest` の2件赤との関連を要調査（R7-02-6）。 |
| A7-27 | 解消 | logout をPOST限定化。サイドバーは元からform POST+CSRFトークン付きで整合。 |

## 5. 推奨改修順

1. **R7-01**: 1行修正でビルド復旧（最優先・5分）。
2. **R7-03 / R7-05 / R7-04**: 今回の改修が新設した3つの退行（ロック順・ログイン404・請求一覧20件）を先に潰す。設計判断はいずれも本書に記載済み。
3. **R7-02 + R7-06**: テスト追随とキャッシュのテスト分離をまとめて行い、`mvn test` を緑化。**緑化を確認してからコミットする**（未コミットのworktree変更は、退行修正込みで論理単位に分けてコミットすること）。
4. R7-07〜R7-09: freee refresh・ガント実効化・メッセージキー。
5. R7-10 / R7-11: 文書と残置物の仕上げ。

## 6. 次回の完了条件

1. 納品状態のworktreeで `mvn test` が 0 failures / 0 errors（**仮修正なしで**）。
2. 要員ログイン→`/my/timesheet` 着地・パスワード変更200の統合テストが存在し緑（A7-01の固定）。
3. dashboard権限なしロール（engineer/invoice等のみ許可）のログインが404/403にならない画面遷移テストが緑（R7-05）。
4. 請求一覧でページャが表示され、21件目以降に到達できる（R7-04）。
5. 改定×approve と saveDaily×approve の並行MySQL試験がデッドロックなく成功（R7-03）。
6. BP支払の層CRUD含む全更新経路が締め済み月で4xx（R7-11-4）。
7. 新設3メッセージキーが全ロケールに存在（R7-09）。
8. CLAUDE.md と AGENTS.md の記述が実装と一致（R7-10）。
