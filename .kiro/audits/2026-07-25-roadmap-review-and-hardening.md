# 2026-07-25 ロードマップ完了後レビューと全体堅牢化

- 対象: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md` の FR-01〜FR-11 完了後のレビューと、それを起点に全工程へ広げた不具合調査
- ブランチ: `claude/money-calc-audit-p0p1`（起点 `a1bf337`、コミット `ee24e04`〜`2ae9402` の8本）
- 規模: 99ファイル / +2,406 / -2,007
- 検証: `mvn clean test` = **725件 成功0失敗**（skip 3件はいずれも `@Testcontainers(disabledWithoutDocker = true)` の `FlywayMigrationSmokeTest`・`FlywayRepairRunbookTest`・`ConcurrentUpdateTest`。**CIにDockerが無いとこの3件は走らない**）
- 検証方法: 静的レビューに加え、**H2でアプリを実起動**し負荷試験・全画面巡回・業務フロー通し実行・Playwrightでの実描画確認を行った。後述のとおり、最も重い不具合のいくつかは起動しないと出なかった。

---

## 0. 総括

FR-01〜FR-11 の実装自体は設計意図に沿っていたが、**並行ブランチのマージに起因する起動不能が1件**あり、その周辺を精査する過程でデータ消失・認可漏れ・金額計算の系統的誤りが見つかった。以降、金額計算 → 全工程 → 実起動 → 性能 → UI/モバイル と対象を広げ、下表の分類で修正した。

| 分類 | 件数 | 代表例 |
|---|---|---|
| 起動不能 | 1 | Flyway 採番衝突（V49 二重） |
| データ消失・整合性 | 4 | 更新判断の設定で担当営業が NULL 上書き |
| 認可 | 3 | 取込原本DLが権限素通り、HRに労務findingsが露出 |
| 金額・数値の誤り | 3 | マッチスコアの単位不整合（万円 vs 円） |
| 可用性（DoS級） | 2 | ページサイズ無制限でメモリ枯渇 |
| 実起動でのみ判明 | 3 | contract_type が NULL の契約で月次締めが500 |
| 性能 | 5 | 要員一覧のN+1、ダッシュボードのキャッシュ化 |
| フロント／UI／i18n | 6 | CDN依存の排除、messages_ja の二重定義 |

**特筆すべき教訓が3つある。**

1. **テストが緑でも起動しない／壊れている状態が成立する。** 採番衝突は `FlywayMigrationSmokeTest` が Docker 無しで自動スキップされるため素通りし、NPE は H2 のテストスキーマに FK も NULL 実データも無いため再現しなかった。Docker非依存の防護テストを増やし、それでも足りない分は実起動で確認する運用が要る。
2. **「無言のフォールバック」は不具合として最も見つけにくく、被害が大きい。** AIマッチングが失敗時にダミー案件を「AIの判定結果」として表示し、そこから実提案が登録できる状態だった。落ちるより悪い。
3. **既定値が危険側に倒れる設計は、登録漏れが必ず起きる。** ファイル系の2レジストリはどちらも未登録時に「削除される」「全員に公開される」へ倒れ、実際に FR-01/FR-08 が漏れていた。

---

## 1. 起動不能

### 1-1 【最優先】Flyway 採番衝突でアプリが全環境で起動不能（`ee24e04`）

- **現象**: `V49` が2本存在し、Flyway が DB に触れる前の解決段階で `FlywayException: Found more than one migration with version 49` を投げる。適用状況に関係なく **dev/prod すべてが起動不能**。
- **原因**: 並行ブランチが各々「main 基準で次の空き番号」を取り、マージで衝突した。本リポジトリで最も起きやすいマージ事故。
- **対応**: 後発を **上方向へ採番** し直す（`V49`→`V55`）。空き番号を埋めてはならない。より高い版を適用済みのDBが `FlywayValidateException: Detected resolved migration not applied to database` で起動不能になるため。
- **再発防止**: `MigrationScriptIntegrityTest` を新設。DB も Docker も不要で、採番重複と空スクリプトを検出する。**クラスパスから読むため、リネーム後は `mvn clean test`** でないと `target/classes` の旧コピーを見てしまう。

---

## 2. データ消失・整合性

### 2-1 【P1】更新判断を設定するたび担当営業とインセンティブ設定が消える（`ee24e04`）

- **対象**: `ContractServiceImpl.updateRenewalDecision`
- **原因**: `renewalDecision` だけを詰めた空の `Contract` を `updateById` へ渡していた。`Contract` は `salesUserId` / `commissionBaseType` / `commissionRate` を **`@TableField(updateStrategy = ALWAYS)`** で定義しているため、`null` のままの3項目も `SET` 句に載り NULL 上書きされる。
- **影響**: 更新カレンダーで更新判断を設定するたびに契約が担当営業から切り離され、インセンティブ集計から欠落する。
- **対応**: カラムを明示する `UpdateWrapper` へ変更。
- **一般則**: `ALWAYS` はフィールド単位で効き、**部分更新を破壊的にする**。`updateById(entity)` は全項目を送る経路だけに使い、単一カラム更新は必ず `UpdateWrapper` を使う。

### 2-2 【P1】誤って自動消込された入金を取り消せない（`77aec7e`）

- **対象**: `InvoiceServiceImpl.deletePayment`
- **原因**: `t_bank_deposit.matched_payment_id` が `t_invoice_payment` を **ON DELETE RESTRICT** で参照しているが、`t_invoice_payment` は論理削除を持たない物理行。消込で作られた入金を AR 画面から削除すると FK 違反で500になり、消込を戻すAPIも無かった。自動消込は「金額一致＋名義一致がちょうど1件」で走るが名義は正規化後の部分一致のため、同額・似名義の取り違えは起こりうる。
- **対応**: 入金削除の**前に**該当する銀行入金明細の消込を解除して未消込へ戻す（FK の RESTRICT があるため順序が本質）。循環依存を避けるため `BankDepositMapper` を直接注入。
- **注記**: **H2 のテストスキーマには FK が無く、この不具合はテストでは再現しない。**

### 2-3 【P1】AIマッチングが架空の案件で実提案を登録できた（`2362fcc`）

- **対象**: `ai-matching.js`
- **原因**: API 失敗時・非200時に `getMockMatchData()` のハードコード3件へ**無言で**フォールバックし、チャットで「マッチ」「案件」と入力する経路に至っては API を呼ばず常にダミーを表示していた。カードには「提案」ボタンが付いており、実在しない案件IDに対し `aiMatchScore=95` という架空スコア付きで `POST /api/proposals` が通る。
- **対応**: ダミーフォールバックを全廃し実データのみ表示。0件・失敗はそれぞれ明示メッセージ（`ai.msg.matchEmpty` / `ai.msg.matchFailed`、4言語）。

### 2-4 【P2】スキル要約・提案文ドラフトが固定文を出力していた（`10ba71f`）

要員によらず「10年以上のJava/Spring Boot開発経験」等の固定文を返しており、**実在しない経歴が提案メール文面としてコピーされうる**状態だった。登録済みの実データのみで組み立て、未登録ならその旨を表示する方式へ変更。

---

## 3. 認可

### 3-1 【P2】取込原本のダウンロードが権限チェックを素通り（`2362fcc`）

`FileScopeValidationService` は最終フォールスルーが **「どのテーブルにも該当しなければ許可」** で終わる。FR-01（`t_project_ingestion`）と FR-08（`t_bp_availability_ingestion`）の `stored_file_name` が未登録で、氏名・連絡先を含むメール／経歴書が全認証ユーザーに読める状態だった。対応メニューの権限で判定するよう追加し回帰テストを新設。

> **ファイルを保存する機能は2箇所への登録が必須**で、どちらも漏れると危険側へ倒れる。
> ① `FileReferenceProvider` — 未登録なら `FileCleanupScheduler` が実ファイルを削除する（`cleanup-safety-hours` 経過後なので試験では気付かない）。
> ② `FileScopeValidationService` — 未登録なら全員に公開される。
> なお清理スキャンが**非再帰**であることだけが、`uploads/contracts/{id}/` 配下の契約書PDFが provider 無しで生き残っている理由。`Files.list` を `Files.walk` に変えるなら provider の追加が必須。

### 3-2 【P2】労務コンプライアンスfindingsがHRに見えていた（`f797362`）

`/api/compliance` は管理者/マネージャー限定だが、同じ findings を埋め込む月次締めサマリは HR にも開放されている。`MonthlyClosingServiceImpl.canViewCompliance()` で compliance メニュー権限を再確認してから充填する。**画面自身のメニュー権限だけを根拠にしない**こと。

### 3-3 ダッシュボードの口径不一致（`10ba71f`）

稼働率・要員数はデータスコープに従う一方、売上・粗利は全社値を返しており、担当範囲を限定されたロールの画面に全社の財務数値が並んでいた。契約取得を `scopedContracts()` に集約し、KPI・チャート・粗利分析を同じ母集団へ揃えた。

---

## 4. 金額・数値の誤り

### 4-1 【P1】マッチスコアの単価採点が単位不整合で常に0点（`77aec7e`）

- **原因**: `MatchScoreCalculator` は単価を**万円**前提（上限未設定時の番兵 `99999`、乖離1あたり2点減点）で実装されていたが、`t_project.unit_price_min/max`・`t_engineer.expected_unit_price` の格納単位は**円**で、`RuleMatchingServiceImpl`・`GeminiMatchingServiceImpl` の計6箇所は円のまま渡していた。
- **影響**: 上限未設定の案件では希望単価 > 99,999円 で必ず単価0点。レンジ外なら10円差でも減点が振り切れて0点。一方 `ProposalDraftServiceImpl` だけが万円へ丸めて渡しており、**同じ要員×案件でも経路によって異なるスコア**が出ていた。スコアは `Proposal.ai_match_score` に永続化される。
- **対応**: 採点を円基準に統一（未設定側は制約なし＝減点しない、乖離1万円につき2点減点）し、`ProposalDraftServiceImpl` の事前換算を削除。

### 4-2 【P2】AIマッチング経由の提案単価が必ず未設定になる（`77aec7e`）

`ai-matching.js` が希望単価を表示文字列から復元していたが、表示は `"¥600,000 / 月"` 形式のため `parseInt` が必ず NaN となり、提案単価が黙って null で登録されていた（解析できたとしても `*10000` で二重換算）。要員詳細が保持する生値（円）を渡すよう修正。

### 4-3 単価カラムのコメント修正（`V57`）

上記の混乱の温床だったカラムコメントを実体（円）に合わせた。

---

## 5. 可用性（DoS級）

### 5-1 【P1】1リクエストでメモリを枯渇させられる（`10ba71f`）

`/api/notifications` と `/api/audit-logs` にページサイズの正規化が無かった。特に通知は `LIMIT/OFFSET` を自前で組むため **`PaginationInnerInterceptor.setMaxLimit(1000)` が効かず**、`?size=999999999` でテーブル全件をロードできた。`/api/notifications` は要員を含む全ロールが到達できる。`int` キャストによるオフセット桁溢れも同時に解消。他22コントローラで使われている `PageUtils.safePage` に統一。

> 手書きの `LIMIT/OFFSET` は MyBatis-Plus の上限保護を**完全に迂回する**。一覧APIを追加するときは必ず `PageUtils.safePage` を通すこと。

### 5-2 バッチの多重起動防止（ShedLock、`V58`）

複数インスタンス構成では6つの `@Scheduled` が全インスタンスで同時発火する。通知系は `dedupe_key` で救われるが、契約更新ドラフト生成は「有無を確認してからINSERT」のため一意制約違反となり、毎日「作成エラー」通知が出ていた。DB共有ロックで1インスタンスのみ実行させる。単一インスタンス運用でも挙動は変わらない。

---

## 6. 実起動でのみ判明した不具合

**この節の3件は静的レビューでも既存テストでも出なかった。** 実際にアプリを起動して画面を開いた時点で再現している。

### 6-1 【P1】契約形態が未設定の契約が1件でもあると月次締めが500（`bf7317b`）

- **原因**: `LaborComplianceServiceImpl.evaluate` が `List.of("準委任","請負").contains(contractType)` を呼んでいたが、`t_contract.contract_type` は ENUM だが **NOT NULL ではない**。`List.of(...)` は不変リストのため `contains(null)` が `false` ではなく **NPE** を投げる。
- **影響**: 3経路が同時に500 —— 月次締め画面が開けない / `GET /api/compliance/findings` / 契約の登録・更新（`saveWithBusinessRules → check`）。
- **なぜテストで出なかったか**: H2 のテストデータに `contract_type` が NULL の行が無かった。

### 6-2 不正なリクエストボディが500「システムエラー」になる（`bf7317b`）

`GlobalExceptionHandler` に `HttpMessageNotReadableException` のハンドラが無く、JSONの型不一致やボディ欠落といった**クライアント起因**の誤りが汎用 `Exception` ハンドラに落ちて500になっていた。利用者に原因不明のエラーが出るうえ、監視でも本物の障害と区別できない。400「リクエスト内容が不正です」を返すよう修正。

### 6-3 モーダル内テーブルの横溢れ（`bf7317b`）

契約の単価改定履歴・請求の入金履歴・督促履歴の3テーブルが `.table-responsive` で包まれておらず、390px幅ではモーダル幅を超えて内容が欠ける。いずれも金額を確認する画面で外出先から見る頻度が高い。

---

## 7. 性能

### 7-1 ダッシュボード集計のキャッシュ（Caffeine、`0b7efd1`）

**実測（H2・要員303/契約181/実績900、50並発）**

| 指標 | 前 | 後 |
|---|---|---|
| スループット | 356 req/s | 614〜660 req/s |
| 全体 p50 | 102ms | 61〜64ms |
| 全体 p95 | 358ms | 170〜185ms |
| ダッシュボード p50 | 289ms | 22〜33ms |
| ダッシュボード p95 | 550ms | 72〜122ms |

ダッシュボードは最遅から最速の部類になり、ボトルネックは一覧系へ移った。

設計上の要点は2つあり、どちらも外すと正しく動かない。

- **キャッシュキーにデータスコープを含める**（`CacheConfig#dashboardScopeKeyGenerator`）。集計は `DataScopeService` により閲覧者ごとに母集団が変わるため、引数だけをキーにすると**担当限定ユーザーの結果を別ユーザーへ配ってしまう**。スコープ非適用ユーザーは `"ALL"` で共有し、適用中のユーザーのみ個別キーとする。
- **`sync = true`**。無しで計測したところ冷キャッシュ時に同時アクセス分の重い集計が並走し（キャッシュ・スタンピード）**p50 が 1041ms まで悪化**した。
- TTL は `app.cache.dashboard-ttl-seconds`（既定60秒、0以下で無効）。

### 7-2 N+1・全走査の解消

| 箇所 | 内容 |
|---|---|
| FR-11 要員一覧 | 1要員ごとに `RetentionRiskService.score()` を呼び **要員数×3クエリ**（`riskLevel=high` では最大1000件分）。`scoreBatch()` で定数回に（`f797362`） |
| FR-11 同上 | 一覧APIは要員エンティティを既に保持しているのに `scoreBatch(ids)` が `selectBatchIds` で同じ行を読み直していた。`scoreBatchFor(Collection<Engineer>)` を追加し1クエリ削減（`2ae9402`） |
| FR-10 | `findCurrentRisks` が全契約（終了・解約含む）を読んでいた。「現在の」リスクなので稼動中・準備中に限定（`f797362`） |
| FR-05 | 資金繰り予測が月ループ内で全入金を走査（月数×請求書数×入金数）。請求書ごとに事前集計（`f797362`） |

### 7-3 一覧APIは「遅い」のではなく「並んでいる」

レビュー中に一覧系をボトルネックと表現したが、**実測すると誤りだった**ので記録しておく。SQLログを有効にした warm 状態の単一リクエストは以下のとおりで、ページングは期待どおり効いている。

| | SQL本数 | 所要 |
|---|---|---|
| 要員一覧 size=10 | 6 | 27ms |
| 要員一覧 size=100 | — | 55ms |
| 契約一覧 size=10 | 2 | 15ms |
| 契約一覧 size=100 | — | 27ms |

50並発時に見える約140msは**接続プール待ちの行列**であって、クエリが遅くなったわけではない。容量の天井は CLAUDE.md「Capacity and concurrency」の順序（①プール ②Tomcatスレッド ③インメモリセッション ④無制限エクスポート ⑤ページサイズ）で考えること。**プールを増やすのは解ではない**（`DBコア数×2` を超えると遅くなる）。

### 7-4 その他のロードマップ機能の是正（`f797362`）

| FR | 内容 |
|---|---|
| FR-04 | Excel出力で、匿名化により最寄駅が落ちると客先A様式の「備考」列まで消え、**PDFと内容が食い違って**いた。見出しと値を同順で組み立てる方式へ変更。PDFとExcelは常に同期させること |
| FR-06 | 終了日を大幅に超過したまま放置された契約へ毎月エスカレーションが飛び続けノイズ化していた。打ち切り日数 `renewal.escalation-max-overdue-days`（既定90日）を追加 |
| FR-05 | 資金ショート警告を `forecast()` 内（＝ダッシュボード参照時）で発行していた。**GETが書き込む副作用**であり、かつ誰も画面を開かない日には警告が出ない抜けがあった。他の通知と同じく `NotificationGenerateService.cashflowAlert()` の日次バッチへ移動 |
| 設定 | 上記2つの設定キーを `/system-config` から編集できるよう `V56` でシード |
| 取込 | 取込解析の `@Async` が `AbortPolicy` のままだとキュー満杯時にジョブが「取込待ち」で放置されるため `CallerRunsPolicy` へ変更し、シャットダウン時の完了待ちも設定 |
| 整理 | `src/main/java/GenHash.java` を削除。デフォルトパッケージに置かれた「admin123のBCryptハッシュを標準出力するmain」で、成果物jarにも同梱されていた |

---

## 8. フロントエンド・UI・i18n

### 8-1 外部CDN依存の全廃（`2ae9402`）

- **問題**: Bootstrap / jQuery / Chart.js 等を CDN から読み込んでいた。日本企業の閉域網ではこれらのドメインが遮断されていることがあり、その場合 **「表示崩れ」ではなくシステム全体が使用不能**（一覧が真っ白、モーダルが開かない）になる。CDN障害でも同じ。
- **対応**: 15ファイル（計1.4MB）を `static/lib/` へ同梱し、7テンプレート17箇所を `/lib/...` へ変更。`SecurityConfig` は `/lib/**` を既に `permitAll()` 済み。
- **Webフォントは同梱せず廃止した**。Noto Sans JP/SC/KR × 4ウェイトは約15MBで、ビルド無しの本リポジトリには重すぎる。加えて `common.css` の `@import` は**スタイル解決を待って初回描画をブロック**するため、閉域網では全ページが接続タイムアウトまで白画面だった（`<link>` より重い障害）。各OS標準のCJKフォントを `--ses-font-sans` として定義し、フォント指定はこの変数を使う。全ページから Google への外部リクエストが消えた副次効果もある。
- **検証**: Playwright で**外部ホストを全 abort** して19画面を巡回し、外部リクエスト **0本**・JSエラー0・横溢れ0を確認。アイコンも同梱 woff から実描画されている。
- **再発防止**: `StaticAssetLocalityTest`。テンプレートの外部 `src`/`href` に加え、**自前CSSの `@import` / `url()` も検査**する。後者はテンプレート走査では見えず、実際にこの穴から Google Fonts がすり抜けていた。

### 8-2 一覧画面の絞り込みパネルをスマホで折りたたみ（`2ae9402`）

検索項目が最大7つあり、スマホでは縦積みでデータ本体が2画面近く下に押し出されていた。`SES.filterPanel`（`common.js`）が全一覧画面共通のマークアップ `.card > .card-body > form#searchForm` を見つけて開閉バーを注入するため、**テンプレートは無変更**。一覧画面を追加するときはこの構造を保つこと。

設計上の要点:

- **表示制御はCSSの `max-width: 768px` に一本化**し、JSは `data-filter-collapsed` 属性を書くだけ。広い画面では属性が無視されるためリサイズ監視が不要で、デスクトップの表示は一切変わらない（実測 `toggleVisible:false, bodyVisible:true`）。
- **適用中の条件数バッジは必須**。折りたたむと絞り込み条件が不可視の状態になり、絞ったことを忘れた利用者は「データが足りない」と報告してくる。
- 検索実行時はモバイルでのみ自動で閉じる。

実測（390×844）: 要員一覧のテーブル開始位置 374px、顧客/案件/候補者 236px でいずれも1画面目に収まる。

### 8-3 【重要】`messages_ja.properties` が基底バンドルを上書きしていた（`2ae9402`）

- **現象**: 案件一覧で、絞り込みの選択肢が「終了」なのに同じ画面の状態バッジは「クローズ」。提案／提案中 も同様。**同じキーがサーバレンダリングとクライアントレンダリングで別の文字列**になっていた。
- **原因**: Spring の `MessageSource` は日本語ロケールに対し `messages_ja` → `messages` の順で解決するため `messages_ja` が優先される。一方 `I18nMessagesLoader`（`window.SES_MESSAGES` を構築）は `lang.equals("ja")` のとき locale 別ファイルを**読まない**。結果、Thymeleaf の `#{...}` は `_ja` の値、`SES.i18n.t()` は基底の値を返す。実際に4キーが乖離していた。
- **対応**: **基底バンドルそのものが日本語である以上 `messages_ja` は存在してはならない**ため削除（全23キーが基底に存在済み）。言語切替に無い `messages_vi`（3キーのみ・キー重複あり）も削除。
- **再発防止**: `testNoUnexpectedBundleFiles` を追加。既存の検査は対象ファイル名をハードコードしていたため、**リスト外のバンドルは完全に検査の外に居た**のが根本原因。

### 8-4 その他の i18n（`ee24e04` / `f797362`）

- `sidebar.html` が参照する `menu.bpAvailability` / `menu.bpAvailabilityIngestion` が全バンドルに欠落し、メニュー名がキー文字列のまま表示されていた（`use-code-as-default-message: true` のため無例外）。→ `testTemplateMessageKeysExist` を追加。
- 一括同期スクリプトが**日本語原文をそのまま各言語へ複製**していた28キーを en/ko/zh_CN で翻訳。原因の `sync_keys.ps1` は削除した（キー整合テストだけ通り未翻訳のまま出荷される元凶）。
- 参照されない死蔵ファイル `messages_zh.properties` を削除（`zh_CN` が全キーを持つ）。

### 8-5 サイドバーにロールの内部表現が出ていた（`0b7efd1`）

`sec:authentication="principal.authorities"` をそのまま表示していたため利用者に `[ROLE_管理者]` と見えていた。`GlobalControllerAdvice` が接頭辞を外した表示用ロール名を提供する。**`principal.sysUser.role` の直参照にしてはいけない** —— principal が素の `UserDetails` になるテストスライスで描画が落ちる（実際に27件のテストが落ちた）。

---

## 9. 誤検知として退けたもの（再修正しないこと）

調査中に「不具合に見えたが実際は違った」ものを記録する。同じ箇所を再び"修正"しないため。

| 事象 | 判定 |
|---|---|
| ブラウザ巡回で出た63/94件の指摘 | **サンドボックスのプロキシがCDNを遮断していただけ**で製品の問題ではない。（この観測が 8-1 の動機にはなった） |
| カンバンの「案件未定／顧客未定」 | 孤児テストデータ。engineer id=2 は404を返す |
| `findCurrentRisks` の2つ目のNPE | 自作テストが検出したが、`engineer_id`/`project_id`/`full_name`/`project_name` はいずれも NOT NULL で**到達不能**。製品ではなくテスト側を修正した |
| `/audit-log/list`・`/todo/list` が404 | 巡回スクリプトのURL誤り。正しくは `/audit-log`・`/todo` で、サイドバーのリンクは元から正しい |
| 「bootstrap-icons が適用されていない」 | 検査スクリプトの誤り。`font-family` は `::before` 疑似要素側にあり、要素自身を見ていた |
| 要員一覧の操作ボタンが1行1個 | ≤576px での**意図的な既存仕様**（タップ領域確保）。不具合ではない |

---

## 10. 未対応・判断待ち

- **要員一覧ヘッダの操作ボタン**: ≤576px で4つが縦積みになり約184pxを占める。Excel出力／CSV出力／CSVインポートを「⋯」ドロップダウンへ畳めば約138px削減できる（テーブル開始位置 374px → 約236px）が、これは2つ目の交互作用設計変更にあたるため未実施。
- **数十人を超える同時接続**: CLAUDE.md「Capacity and concurrency」のとおり、設定調整では届かない。ダッシュボード以外の集計キャッシュ、セッションの外部化（現状インメモリのため再起動で全員ログアウト・スケールアウトにはスティッキーが必要）、リードレプリカまたはクエリの絞り込みが要る。**設定チューニングではなく設計変更**として扱うこと。
- **無制限エクスポート**: `/api/engineers/export`・`/api/contracts/export` は全件をメモリに構築する。大規模テナントでの同時実行はOOMリスクとして残っている。

---

## 11. 追加した回帰テスト

いずれも「なぜ既存の仕組みで防げなかったか」に対応している。

| テスト | 塞いだ穴 |
|---|---|
| `MigrationScriptIntegrityTest` | 採番重複・空スクリプト。`FlywayMigrationSmokeTest` が Docker 無しで自動スキップされるため素通りしていた |
| `StaticAssetLocalityTest` | テンプレートの外部アセット参照と、**CSSの `@import`/`url()`**。後者はテンプレート走査では見えない |
| `MessageBundleConsistencyTest#testNoUnexpectedBundleFiles` | 検査対象ファイル名がハードコードで、リスト外のバンドルが不可視だった |
| `MessageBundleConsistencyTest#testTemplateMessageKeysExist` | `use-code-as-default-message: true` によりキー欠落が例外にならず画面にキー名が出るだけだった |
| `FileScopeValidationServiceTest` | 最終フォールスルーが「許可」のため、未登録テーブルが黙って全公開になる |
| `NotificationPagingTest` | 手書き `LIMIT/OFFSET` が `PaginationInnerInterceptor` の上限を迂回する |
| `ContractServiceImplTest` 追加分 | `updateStrategy=ALWAYS` の部分更新破壊 |
| `LaborComplianceServiceImplTest` 追加分 | `contains(null)` の NPE |
| `MatchScoreCalculatorTest` 追加分 | 単価の単位不整合 |

---

## 12. 検証環境の再現手順

本レビューで使った実起動環境は以下の構成。`.kiro` にはスクリプトを含めていないため、再実施する場合はこの手順で組み直す。

1. **アプリ起動**: H2（MySQL互換モード）+ Flyway無効 + 実データ相当のシード（要員303/契約181/実績900）。シードは `spring.sql.init.data-locations` が読まれない事象があったため、確実に読まれる `schema.sql` の末尾へ ID オフセット1000で追記した。
2. **負荷試験**: 50並発で主要画面を叩き、スループット・p50・p95 を採取。
3. **業務フロー通し**: API レベルで要員登録→提案→契約→実績→請求→入金消込 を実行。**CSRF は `CookieCsrfTokenRepository` のため、`XSRF-TOKEN` クッキーを `X-XSRF-TOKEN` ヘッダへ複写**しないと全て403になる。
4. **UI巡回**: Playwright で19画面をデスクトップ（1440×900）とモバイル（390×844）で巡回。外部ホストは全 abort し、横溢れ・JSエラー・未翻訳キー・空ページ・アイコン描画を機械的に判定。

> 実行時の注意: `pkill -f ses-manager-pro-1.0.0` は**自分自身のコマンドラインにマッチしてシェルごと落とす**。`[s]es-manager-pro-1.0.0-SNAPSHOT.jar` のブラケット記法を使うこと。またアプリ起動中の `mvn clean` は jar を消すため全画面が500になる。
