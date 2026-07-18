# Review Fixes — P1〜P7 実装レビュー指摘と修正ガイド（customer-feature-proposals）

対象: ブランチ `claude/customer-feature-proposals-review-xyo84v` の P1〜P7 実装
（`06b2989`〜`11ec68a`、V28〜V34）に対するコードレビュー（2026-07-18、effort=high）。
**本ドキュメントは指摘の記録のみで、修正は未実施**。対応セッションは各節の修正ガイダンスに従い、
修正とテストを同一コミットに含め、完了した節見出しへ `[対応済み]` を追記すること。

## レビュー時の確認済み事項（対応不要）

- **採番**: V28〜V34 が実装全体計画の Wave 順予約どおり。各マイグレーションの内容・メニュー/config
  シード・H2 同期3点（engineer-schema-h2 / `sql/schema-*-h2.sql` / application-test.yml）を確認。
- **テスト**: `mvn test` を本環境で再実行し **569 tests / failures 0 / errors 0 / skipped 2** を確認
  （README の主張と一致。skip は Docker 必須の smoke test 等）。
- **P2**: 消込判定 `recalcPaymentStatus`/`resolvePaymentStatus` の一元化・過入金拒否・手数料込み消込・
  入金行削除での巻き戻り・手動遷移の 未送付⇄送付済 限定（旧「入金済」手動ボタンの撤去含む）。
- **P4**: `buildAndSaveDraft(DraftSource)` への共通化（既定値規約・主担当フォールバック一箇所化）・
  `quotation_id` 冪等・既存 `createDraftFromProposal` テスト全緑維持。
- **P1**: `confirmMonth` の対象が 入力中+提出済（**差戻し除外**、spec どおり）・`dailyManaged` 排他・
  `error.my.notLinked` 本人スコープ・`LoginSuccessHandler` の要員分岐・`/my/**` の `hasRole("要員")`。
- **P6**: リゾルバの saveHours/resolveBatch 適用・`revisePrice` の現在単価再計算が「当月時点で有効な履歴」
  （将来予約に引きずられない）で行われること・初期履歴の自動補完。
- **P3**: 締め実行のロール検査（管理者/マネージャーのみ）・5項目の既存クエリ共有。
- **P5**: forecast がチャート対象月ループ（年度表示含む）に沿って `assumedStart` 以降のみ加算。
- **P7 の Engineer/Customer 一覧**: クエリレベルの IN 注入＋空集合の空ページ即返し（設計どおりの正しい実装）。

指摘は 5 件。**Q1・Q2 はマージ前修正を強く推奨（P7 の中核が部分的に未達）**、Q3・Q4 は次サイクル可、Q5 は任意。

---

## Q1.【高・P7】[対応済み] 契約一覧のスコープがページング後のメモリフィルタになっている

- **場所**: `controller/api/ContractApiController.java` の `page`（`isScoped` 分岐）
- **内容**: Engineer/Customer が正しくクエリへ `IN` を注入しているのに対し、契約一覧だけ
  `selectPageWithNames` の**結果ページを Java 側で filter** している。これにより:
  1. `total`/`pages` がスコープ前の全件数のまま返る（**担当外契約の存在・件数が営業に漏れる**）
  2. 各ページの行数が `size` 未満に欠け、担当外だけのページは**空ページ**になる（ページャが壊れる）
  3. requirements R3-2「件数・ページングもスコープ適用後の値」の明示的な違反
- **原因の推定**: `selectPageWithNames` が注釈 `@Select` の動的 SQL のため、wrapper 経路の
  Engineer/Customer と違い IN 注入に一手間かかることによるショートカット。
- **修正ガイダンス**: `selectPageWithNames` に `allowedIds`（`List<Long>`、null=無制限）パラメータを
  追加し、`<if test="allowedIds != null">AND c.id IN <foreach ...></if>` を WHERE へ追加
  （既存の `salesUnassigned` と同じ `<if>` パターン）。コントローラは
  `isScoped() ? new ArrayList<>(allowedContractIds()) : null` を渡し、空集合は
  Engineer と同様に**クエリ前に空ページ即返し**。メモリフィルタは削除。
- **テスト**: `DataScopeIntegrationTest` に「担当2件+他人8件で total=2・1ページ」
  「2ページ目が空にならない」を追加。ガント（`/api/contracts` 共用）も同経路で自動的に直ることを確認。

## Q2.【高・P7】[対応済み] スコープ未適用の読み経路が残っている（tasks 3/4 のチェックと実装が乖離）

- **場所と欠落内容**（`dataScopeService` 参照が存在するのは Engineer/Contract/Customer の3コントローラのみ）:
  1. **`ProposalApiController`（カンバン/一覧）** — design の「コア4領域」の1つなのに未適用。
     営業は他営業の提案（顧客名・提示単価つき）を全件見られる
  2. **`ExportApiController`（契約/要員 Excel）** — 未適用。**「画面だけ絞ってエクスポートで
     全件漏れる」という requirements R3-3 が明示的に禁止した穴がそのまま存在する**（最重要）
  3. **`QuotationApiController`** — 未適用（tasks タスク4は適用済みと主張）
  4. **`SkillSheetApiController`** — 未適用（要員詳細は404秘匿されるがスキルシートは直接取れる）
  5. **営業成績の自分行ハイライト** — `sales-performance.js` に実装なし（機能欠落のみ、情報漏えいではない）
- **修正ガイダンス**: 適用は既存の2パターンを踏襲する:
  - Proposal: カンバン DTO クエリへ `allowedProposalIds` の IN（または Java 側フィルタでも
    カンバンは非ページングのため可）。
  - Export: **画面と同じ allowed 集合を再利用**（契約=Q1 の `allowedIds` パラメータを export 側の
    `selectList` にも適用、要員=`queryWrapper.in`）。別実装禁止（requirements R3-3）。
  - Quotation: `allowedCustomerIds` ∪ `allowedEngineerIds` 由来（design 3章の表どおり）で
    一覧IN＋詳細404。
  - SkillSheet: 対象 engineerId を `allowedEngineerIds` で詳細404 パターン。
  - ハイライト: DTO に `self` フラグ（`salesUserId == currentUserId`）か JS 側で現在ユーザー ID 比較。
- **テスト**: 各経路につき「営業Aで他人の分が含まれない/404」＋既定 false の後方互換。
  **tasks.md のタスク3/4のチェックは実装完了後に付け直すこと**（チェック済み表記と実態の乖離は
  後続 AI の前提を壊すため、本指摘の対応時に tasks へ注記を残す）。

## Q3.【中・P2】[対応済み] エイジングに「未送付」列がなく、未請求が売掛の期日区分に混入する

- **場所**: `InvoiceMapper.selectOutstandingBalances`（`status <> '入金済'` で未送付も取得）＋
  `InvoiceServiceImpl.aging`（`classifyBucket` は due_date だけで区分）
- **内容**: requirements R2-2 は「対象は送付済・一部入金。**未送付は『未請求』列として別掲**」と
  規定したが、実装は未送付請求書も期日区分（期限内/1-30日…）へ混ぜて集計する。
  まだ送っていない請求書が「超過31-60日」等に現れ、回収督促の判断を誤らせる。
- **修正ガイダンス**: `AgingReportDto.Row` に `unsent` を追加し、`aging()` のループ冒頭で
  `"未送付".equals(b.getStatus())` なら `unsent` へ加算して continue（`classifyBucket` は不変）。
  画面・Excel（`exportAging`）にも「未請求」列を追加（design 5章のヘッダー案に既に含まれている）。
- **テスト**: 未送付・期限超過の請求書が d31to60 でなく unsent に入ること1ケース＋Excel ヘッダー更新。

## Q4.【中低・P4】[対応済み] 提案カンバンに「見積作成」の入口がない

- **場所**: `static/js/modules/proposal-kanban.js`（変更なし）
- **内容**: `quotation.js` は `?fromProposal={id}` のプリセット受け側を実装済みだが、
  カンバンカード側に導線が追加されておらず、URL を手打ちしない限り到達できない
  （requirements R4-1 未達。tasks A1 はチェック済み）。
- **修正ガイダンス**: カンバンカードのメニュー（既存の操作ボタン列）に「見積作成」を追加し
  `location.href = '/quotation?fromProposal=' + item.id`。i18n 1キー×4言語。
- **テスト**: JS のため Demo 検証（カンバン→見積モーダルに単価・顧客・要員がプリセットされる）。

## Q5.【低・任意・P6】[対応済み] 単価リゾルバが `@Autowired(required = false)` の任意依存

- **場所**: `WorkRecordServiceImpl` / `MonthlyRevenueCalcServiceImpl` のリゾルバフィールド
- **内容**: 本番では `ContractPriceResolverImpl` が `@Service` のため常に配線されるが、
  必須の業務依存が「未配線なら黙って現在単価へフォールバック」する構造は、将来の
  Bean 定義変更事故時に**改定前月の精算が新単価で走っても誰も気づかない**リスクを持つ。
  （採用理由は既存 `@InjectMocks` テストの全緑維持と推測——それ自体は妥当なトレードオフ）
- **修正ガイダンス**（いずれか）: (a) コンストラクタ必須化し、既存テストへはモックを追加する
  （件数は多くない）/ (b) 現状維持の場合、フォールバック経路に `log.warn`（起動時1回）を追加し
  「テスト専用の緩和である」旨を Javadoc に明記する。
- **テスト**: (a) なら既存テストのコンパイル修正のみ。(b) なら不要。

---

## 残存する検証課題（コード修正ではなく実行環境の問題・従来からの継続）

1. **V28〜V34 は実 MySQL 未実行**: smoke test は Docker 不在で skip。特に V32 の
   `sys_user.role`/`t_work_record.status` の **ENUM MODIFY は H2(VARCHAR) では検証されない**ため、
   マージ前に Docker あり環境で `FlywayMigrationSmokeTest` を必ず1回実行すること。
2. **ブラウザ Demo 未実施**: 各 tasks の Demo（特に P1 の要員ログイン一気通貫・P2 の入金→
   エイジング・P7 の ON/OFF 切替）はヘッドレス環境では未検証。Q1/Q2 修正後にまとめて実施する。

## 進め方

- 推奨順序: **Q2（Export が最優先）→ Q1 → Q3 → Q4 → Q5（任意）**。
  Q1 と Q2-2（契約 Export）は同じ `allowedIds` パラメータ追加で一緒に直るため同一コミット推奨。
- すべて本ブランチ（`claude/customer-feature-proposals-review-xyo84v`）上で修正し、
  完了後に `mvn test` 全緑を再確認、本ドキュメントへ `[対応済み]` を追記すること。

---

## 対応記録（2026-07-18）

Q1〜Q5 をすべて対応し `mvn test` 全緑（570件、Docker/フォント無し環境で smoke test・PDF正常系のみ skip）を再確認。

- **Q1**: `ContractMapper.selectPageWithNames` に `allowedIds`(null=無制限) を追加し WHERE へ `AND c.id IN (...)` を注入。
  コントローラは `isScoped()` 時に `allowedContractIds()` を渡し、空集合はクエリ前に空ページ即返し。メモリフィルタは削除。
- **Q2**: Proposal(カンバンJavaフィルタ)・Export(契約/要員は画面と同じ allowed 集合を再利用・R3-3)・
  Quotation(顧客∪要員由来の一覧IN＋詳細404)・SkillSheet(engineerId 詳細404)・営業成績(`self` フラグ)を追加。
- **Q3**: `AgingReportDto.Row.unsent` を追加し、未送付は期日区分でなく「未請求」列へ別掲（画面・Excel・i18n 4言語）。
- **Q4**: 提案カンバンカードに「見積作成」ボタン（`/quotation?fromProposal=` へ遷移）を追加。
- **Q5**: リゾルバ2箇所に `@PostConstruct` の未配線 warn と Javadoc を追記（任意依存の意図を明文化）。

残存の検証課題（実 MySQL smoke・ブラウザ Demo）は実行環境依存のため本対応の対象外（従来どおり）。
