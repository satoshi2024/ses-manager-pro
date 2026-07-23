# 第7回 全体監査 — 潜在バグ・設計課題・最適化（A7-01〜A7-27）

- 監査日: 2026-07-23
- 基準: `HEAD=bda593d`（worktree clean）
- 方針: 全モジュールを読み取り専用で再点検し、**未報告の新規問題**を中心に整理した。改修方法と期待効果のみ記述し、コードは書かない（実装は別担当）。
- 検証済みの前提: **全量テストは `mvn test` で BUILD SUCCESS（失敗0）**。第6回の残課題だった5件のテスト失敗（N-1）は解消済み。`MyBatisPlusConfig` の maxLimit も 1000 に復元済み。

## 0. 総括

| 区分 | 件数 | ID |
|---|---:|---|
| P1: 機能が実質使えない/即時修正 | 2 | A7-01, A7-02 |
| P2: 業務影響のあるバグ・設計欠陥 | 11 | A7-03〜A7-13 |
| P3: 最適化・堅牢性・衛生 | 14 | A7-14〜A7-27 |

特に重要な系統的問題は次の4つ。

1. **要員（エンジニア本人）ロールの動線が SecurityConfig で遮断されている**（A7-01）。ログイン直後に403になるため、セルフサービス勤怠機能全体が実質未リリース状態。
2. **電子契約（contract-document）モジュールの完成度が他モジュールより一段低い**。トースト誤呼び出しで画面更新が止まり（A7-02）、PDFに日本語フォントが無く（A7-03）、署名済みPDF保存は未実装の死コード（A7-04）、XSS未対策（A7-10）。このモジュールだけ品質ゲートを通っていない。
3. **BP支払が月次締め・並行制御の保護の外にある**（A7-05, A7-06）。締め済み月の原価データを自由に書き換えられる。
4. **「実装済みのはずの規約」からの漏れが散発**: データスコープの請求書だけ未適用（A7-07）、ページサイズガードの半数未適用（A7-11）、CLAUDE.md の記述が現実と乖離（A7-13）。規約は「全箇所へ適用したか」を横串で検査する仕組み（テスト/チェックリスト）が無いと再発する。

---

## 1. P1 — 機能停止級

### A7-01 【P1】要員ロールはログイン直後に403になり、パスワード変更もできない

- 対象: `config/SecurityConfig.java`（`anyRequest().hasAnyRole("管理者","営業","HR","マネージャー")`）、`config/LoginSuccessHandler.java`、`controller/page/LoginPageController.index`、`controller/api/ProfileApiController`
- 現象:
  1. `LoginSuccessHandler` は全ロールを `/` へ送るが、`/` はどの requestMatcher にも該当せず `anyRequest()`（4管理ロール限定）に落ちるため、**要員は認可層で403となり、`LoginPageController.index` 内の「要員→ `/my/timesheet` へリダイレクト」分岐には永遠に到達しない**。要員はログインするたびに403エラーページを見る。
  2. 同じ理由で `/api/profile/password`（パスワード変更）も要員には403。ヘッダーの「パスワード変更」メニューは `/my/timesheet` 画面にも表示されるのに、実行すると必ず失敗する。
- 改修方法:
  1. `/`（ルートのロール別振り分けルーター）と `/api/profile/**` を `authenticated()` に変更する（要員を含む全認証ユーザー許可）。
  2. 「要員がアクセスできるべき共通経路」（`/`、`/api/profile/**`、`/api/notifications/**`〔対応済〕、`/logout`〔対応済〕）を SecurityConfig 内に一覧コメントとして明文化し、以後共通機能を追加する際の確認箇所を一本化する。
  3. 統合テストを追加: 要員ユーザーでログイン→302→`/`→`/my/timesheet` へ到達すること、`/api/profile/password` が200を返すことを固定する。
- 期待効果: 要員セルフサービス（P1機能）が実際に使用可能になる。ログイン直後の403という最悪の第一印象を解消する。

### A7-02 【P1】契約書（電子契約）画面の全操作が「SES.toast is not a function」で途中停止する

- 対象: `static/js/modules/contract-document.js`（105, 112, 129, 140, 169, 180行）
- 現象: `SES.toast` はオブジェクト（`SES.toast.success(...)` が正しい）だが、このファイルだけ `SES.toast('success', '...')` と**関数として呼んでいる**。TypeError が投げられるため:
  - 作成/送信/同期の成功後、トーストが出ないだけでなく**後続の `loadDocuments()`（一覧再読込）が実行されず、画面が古いまま**になる（サーバー側は成功しているため二重操作を誘発）。
  - 入力不足時の警告トーストも表示されない。
- 改修方法: 6箇所を `SES.toast.success / SES.toast.warning` に修正する。あわせて本ファイルはメッセージが全て日本語ハードコードなので、他モジュール同様 `SES.i18n.t(...)` に統一する。「`SES.toast(` という関数呼び出しが存在しないこと」を静的チェック（grep ベースの軽量テストや ESLint ルール）で防止する。
- 期待効果: 電子契約画面の操作フィードバックと一覧更新が正常化し、二重送信を防ぐ。

---

## 2. P2 — 業務影響のあるバグ・設計欠陥

### A7-03 【P2】電子契約PDFはCJKフォント未埋め込みで、日本語が表示されないPDFを取引先へ送る

- 対象: `service/impl/ContractDocumentServiceImpl.create`
- 現象: 請求書/見積書/作業報告書のPDFは R2-08 対応で `fonts/ipaexg.ttf` を埋め込んだが、**電子契約の下書きPDF生成だけ素の `Document`+デフォルトフォント（Helvetica）のまま**。テンプレートは日本語HTMLなので、生成されるPDFは日本語グリフが描画されない（空白/化け）。このPDFがそのまま CloudSign 経由で相手方に送られる。また HTML→PDF 変換が「タグを空白置換した平文を1段落で流し込むだけ」なので、フォントを直しても整形は失われる。
- 改修方法:
  1. 他のPDFサービスと同じCJKフォント解決（A7-17の共通化と同時に）を適用する。
  2. HTML→PDFの変換方式を決める: 最低限は改行・見出しを保った平文整形、望ましくは既存 OpenPDF の HTMLWorker 相当か、レイアウトを諦めるなら「契約書はテンプレートの平文化で良い」ことを仕様として明記する。
  3. 生成PDFから日本語テキストが抽出できることを検証するテストを追加（InvoicePdfServiceImplTest と同型）。
- 期待効果: 相手方に読める契約書PDFが送られる。R2-08 の修正が「PDF生成4箇所すべて」に及ぶ。

### A7-04 【P2】署名済みPDF・締結証明書の保存機能は死コード（クライアントが常にnullを返す）

- 対象: `service/impl/CloudSignClientImpl.status`、`service/impl/ContractDocumentServiceImpl.sync`
- 現象: `sync` には「署名済みPDFと証明書をダウンロードして保存し、SHA-256を更新する」分岐があるが、`CloudSignClientImpl.send/status` は Result の `signedPdf`/`certificate` を**常に null で返す**ため、この分岐は到達不能。締結完了後も原本はどこにも保存されず、`download` は下書きPDF（しかもA7-03の壊れたPDF）を返し続ける。
- 改修方法: CloudSign API のファイル取得（documents/{id}/files/{file_id} 相当）を `status()` 実装に追加して分岐を活かすか、当面実装しないなら到達不能分岐を削除し「署名済み原本の保管は未実装」を spec (`.kiro/specs/contract-document-esign`) と画面上に明示する。どちらにするか設計判断を先に行うこと。
- 期待効果: 「締結済み原本が保存されている」という誤認を排除。電子帳簿保存の要件検討時に事故にならない。

### A7-05 【P2】BP支払は月次締めの保護外で、支払済ステータス変更は金額同期と競合する

- 対象: `service/impl/InvoiceServiceImpl.changeBpPaymentStatus`（458〜478行）、`service/impl/BpPaymentServiceImpl.addLayer/updateLayer/deleteLayer`
- 現象:
  1. 勤怠・請求の更新系は `monthlyClosingService.assertOpenForUpdate` で締め済み月を拒否するが、**BP支払のステータス変更・階層追加/金額変更/削除はどれも締めチェックを通らない**。締め確定後に未払BPを作る・支払済に倒す・金額を書き換えることが自由にでき、締め時の「(d) 未払BPゼロ」検証が事後に無意味化する。
  2. `changeBpPaymentStatus` の 支払済 分岐は read→全カラム `updateById` で、`未払` 分岐（列指定UPDATE）と非対称。並行して `syncRootBpAmount` が金額を更新した場合、**古い amount を持った全カラム上書きで金額同期を巻き戻す** lost update がある。`@Transactional` も無い。
  3. 458行直下に AI の未完了検討コメント（「これは少し面倒だが今回はスキップ。…あるいは...」）がそのまま残っており、このギャップが意図的に放置されたことを示している。
- 改修方法:
  1. BP支払の4つの更新経路すべてで、`work_record_id` → `t_work_record.work_month` を解決して `assertOpenForUpdate` を呼ぶ。
  2. 支払済への変更は「`status='未払'` を条件とする列指定UPDATE（status, paid_date のみ）」の CAS にし、0件なら409を返す。未払へ戻す分岐と対称にする。
  3. `syncRootBpAmount` の UPDATE にも `status='未払' AND amount=<読取値>` の条件を加え、0件時は再読込して warn（R6-04③の完了）。
  4. 検討コメントを削除し、正式な設計コメントに置き換える。
- 期待効果: 締め済み月の原価が不変になり、月次締めの保証が売上側と同水準になる。支払済み金額の後書き競合が消える。

### A7-06 【P2】approve はContractロック後も旧スナップショットを読み、旧金額でBP支払を生成しうる

- 対象: `service/impl/WorkRecordServiceImpl.approve`（573〜601行）、（同型の軽度問題: `submit`/`reject` の再取得、`ContractServiceImpl.deleteFuturePriceRevision`）
- 現象: `confirmMonth` は R6-04 対応でロック後の再取得を `selectByIdForUpdate`（current read）に改めたが、**`approve` の再取得は普通の `getById` のまま**。InnoDB REPEATABLE READ ではトランザクション最初の非ロック読取で確立したスナップショットを読み続けるため、Contract ロック待ちの間に commit された `revisePrice`（提出済レコードの金額も更新する）の新金額を読めず、`generateOrSyncBpFor` が旧 `payment_amount` でBPを生成する余地が残っている。状態遷移自体は CAS で守られているが、金額の鮮度が守られていない。あわせて `deleteFuturePriceRevision` は Contract ロックと削除件数確認が無いまま（R6-04残）。
- 改修方法: `approve`（および `submit`/`reject` の再取得も統一的に）で、Contract ロック後の WorkRecord 再取得を `selectByIdForUpdate` にする。`deleteFuturePriceRevision` は Contract を先にロックし、削除0件を成功扱いしない。改定×approve の並行を検証するMySQL試験（Docker）を R6 系試験に追加する。
- 期待効果: confirmMonth と approve が同じ読み取り規約になり、BP金額が常に最新の改定を反映する。

### A7-07 【P2】請求書モジュールだけデータスコープ未適用。無効化されたスコープ検証がコメントアウトで残存

- 対象: `controller/api/InvoiceApiController`（list/detail/pdf/status/void/payments/reminders/aging 全endpoint。151行に `// dataScopeService.assertAllowedCustomer(customerId);` のコメントアウト）
- 現象: `scope.sales-own-data-only` 有効時、営業ロールは顧客・案件・契約・見積・提案では担当分しか見えないのに、**請求書一覧・明細・PDF・入金情報は全顧客分を閲覧・操作できる**。`agingDetail` には検証を入れかけて無効化した痕跡が残っており、意図が不明瞭。BP支払は「スコープ対象外（R3R-35）」と明文化されているのに対し、請求書は判断の記録が無い。
- 改修方法: 設計判断をまず確定する。(案A) 請求は経理業務としてスコープ外 → コメントアウト行を削除し、`invoice` メニューを営業ロールへ配らない運用を V2/V42 系 seed と運用文書に明記。(案B) スコープ対象 → list はクエリレベルで `customer_id IN (allowedCustomerIds)` を注入し、detail/pdf/status/void/payments/reminder は取得後に `assertAllowedCustomer(invoice.customerId)` を通す。売掛金額は特に機微なので案Bを推奨。
- 期待効果: 「モジュールによってスコープが効いたり効かなかったりする」状態を解消し、越権閲覧経路（もしくは未記録の設計判断）を無くす。

### A7-08 【P2】契約自動更新バッチは1件の不良データで全件ロールバックし、以後毎日失敗し続ける

- 対象: `service/impl/ContractRenewalServiceImpl.generateRenewalDrafts`（`ContractRenewalScheduler` から日次実行、管理者APIからも実行）
- 現象:
  1. バッチ全体が単一 `@Transactional` で、ループ中の `saveWithBusinessRules` が1件でも BusinessException を投げる（典型: 元契約の `sales_user_id` の営業が退職/無効化済み → `validate` の在職チェックで失敗）と**全契約分がロールバック**。対象条件は翌日も同じなので、日次バッチは恒久的に0件のまま静かに失敗し続ける。
  2. `buildDraft` は `salesUserId` を無条件で引き継ぐ。提案/見積からのドラフト生成（`buildAndSaveDraft`）が「退職済みならNULLへフォールバック」するのと非対称で、上記失敗の直接原因。
  3. 生成済み判定 `hasExistingDraft` は論理削除を除外するため、**不要と判断して削除された更新ドラフトが翌日また再生成される**（通知は dedupe されるため気づきにくい）。
- 改修方法:
  1. 契約1件ごとに独立トランザクション（`REQUIRES_NEW` またはバッチメソッドから `@Transactional` を外し per-item にする）とし、失敗は warn ログ + 管理者向け通知にして続行する。
  2. `salesUserId` は `isActiveSalesUser` を確認し、不在なら NULL で生成（`buildAndSaveDraft` と同じ規約に統一）。
  3. 再生成抑止は「論理削除も含めて `renewed_from_contract_id` の存在を確認する」専用クエリにするか、削除＝更新不要の意思表示として `auto_renew` を0に落とす運用をUIで案内する。どちらかを仕様として明記。
- 期待効果: 更新漏れ（=契約切れの見逃し）という営業事故を防ぐ。バッチが不良データ1件に人質に取られない。

### A7-09 【P2】営業成績とダッシュボードで「対象月に有効な契約」の定義が分裂している

- 対象: `service/impl/SalesPerformanceServiceImpl.isActiveInMonth`（211〜217行）、`service/billing/MonthlyRevenueCalcServiceImpl.isTargetInMonth` / `calc`
- 現象: SalesPerformance はローカルの `isActiveInMonth` で集計対象を決めるが、共通口径サービスと2点で食い違う。(1) 「終了/解約なのに endDate=NULL の契約を除外する」ガードが無く、そのような契約は毎月無期限に計上され続ける。(2) 共通側は「確定実績があれば期間外でも計上」（確定済み過去売上の保護、MI-09）だが、SalesPerformance は期間外なら確定実績があっても落とす。結果、**ダッシュボードの全社売上と営業成績表の合計が月によって一致しない**。unattributed行まで足しても突合できない。
- 改修方法: `isActiveInMonth` を削除し、`MonthlyRevenueCalcService.isTargetInMonth` + 「確定実績優先」の判定に一本化する（`calc` が内部で行っている `hasConfirmedRecord || isTargetInMonth` と同じ条件を公開メソッド化して両者から使う）。ダッシュボード当月値と営業成績合計が一致する集計テストを追加する。
- 期待効果: 全社KPIと営業別内訳が常に突合可能になり、コミッション計算の信頼性が上がる。

### A7-10 【P2】契約書画面に保存型XSS（受信者名・テンプレート名を無エスケープ描画）+ sanitize がイベント属性を通す

- 対象: `static/js/modules/contract-document.js`（40〜45行のテンプレート一覧、71〜73行のドキュメント一覧）、`service/impl/ContractDocumentServiceImpl.sanitize`
- 現象:
  1. `recipientName` / `recipientEmail` / `status` / テンプレート `name` / `contractType` を **`SES.escapeHtml` なしで innerHTML へ挿入**。契約メニュー権限を持つ利用者が `<img src=x onerror=...>` 等を受信者名に保存すると、以後この画面を開いた全員のブラウザで実行される。
  2. サーバー側 `sanitize` は `<script>`/`<iframe>` の除去と外部URL資源の拒否のみで、`onerror`/`onclick` 等のイベント属性・`javascript:` href を素通しする。`rendered_html` を将来プレビュー表示する場合そのままXSSになる。
- 改修方法:
  1. 本ファイルの全補間点に `SES.escapeHtml` を適用（他モジュールの規約どおり）。
  2. `sanitize` は「タグ・属性の allowlist 方式」（許可: 段落/見出し/表/強調系のみ、属性は原則なし）に置き換えるか、プレビュー用途を平文に限定して sanitize の役割を「PDF生成前の防御」だけに縮小し、その旨をコメントで明記する。
- 期待効果: 契約書モジュールからの保存型XSS経路を遮断し、フロント規約（escapeHtml必須）の一貫性を回復する。

### A7-11 【P2】ページサイズ下限ガードが半数のAPIに未適用で、size=-1 の無制限全件取得が残っている

- 対象: `CandidateApiController` / `InvoiceApiController` / `SalesActivityApiController` / `UserApiController` / `QuotationApiController` の一覧API（`ContractApiController` 等4つは `size <= 0` ガード実装済み）
- 現象: MyBatis-Plus の `maxLimit(1000)` は「正の size が上限を超えた場合」しか丸めず、**負の size はページングなし＝全件取得**になる。ガードのある4コントローラーは自衛済みだが、残り5つは `size=-1` を渡すだけで全行を返す（N-2 の対策が全箇所へ波及していない）。
- 改修方法: 「current/size を正規化する共通ヘルパー（size<=0 → 既定値、上限は maxLimit と同値）」を1つ作り、全一覧APIで使用する。個別コントローラーに if 文をコピペしない（今回の漏れの原因）。全一覧APIに `size=-1` を投げて件数が制限されることを一括検証するテストを追加する。
- 期待効果: 大量データ時のメモリ枯渇/DoS経路を全APIで塞ぎ、ガードの適用漏れが構造的に起きなくなる。

### A7-12 【P2】発行済み請求書のない「未送付」に入金操作すると、削除時に「送付済」へ化ける ほか overdue 定義の分裂

- 対象: `service/impl/InvoiceServiceImpl.recalcPaymentStatus` / `InvoiceApiController.list`（overdue filter）
- 現象:
  1. `recalcPaymentStatus` は入金合計0のとき一律「送付済」へ戻す。未送付の請求書に入金を登録→削除すると、**一度も送付していないのに「送付済」になる**。
  2. 「期限超過」の定義が3種類ある: 一覧filterは `status != 入金済`（未送付を含む）、通知バッチと督促可否は `送付済/一部入金` のみ、月次締めサマリー(e)は status不問の残高ベース。一覧で「期限超過」に見えるのに督促ボタンが409になる組合せが生じる。
- 改修方法:
  1. `recalcPaymentStatus` に「入金前の送付状態」を保つ分岐を入れる（入金0件時は 未送付だったものは未送付へ戻す。実装は「現在statusが入金系のときだけ送付済へ戻す」で足りる）。そもそも未送付への入金登録を業務的に許すのかを決め、許さないなら `addPayment` で 未送付 を4xxにするのが最も簡単。
  2. 「督促対象/期限超過」の判定を1つの静的メソッド（またはSQL条件文字列定数）に集約し、一覧filter・通知・督促・締めサマリーの4箇所から共用する。
- 期待効果: 請求ステータスの意味が画面間で一致し、送付前の請求書が誤って督促対象に見えなくなる。

### A7-13 【P2】CLAUDE.md / AGENTS.md が現実と乖離（CSRF・ロール・マイグレーション数）— AI改修の前提を汚染する

- 対象: `CLAUDE.md`、`AGENTS.md`
- 現象: 本リポジトリはAIエージェントによる改修が前提なのに、その入力となる文書が古い。確認できた乖離: (1) 「CSRFは `/api/**` で無効化」→ 実際は CookieCsrfTokenRepository + X-XSRF-TOKEN 方式へ移行済み（JSは全経路でヘッダー付与している）。(2) 「ロールは固定ENUM 管理者/営業/HR/マネージャー」→ V32で「要員」追加済み。(3) 「Flyway V1〜V14」→ 実際は V42 まで（V19/V23/V41 は欠番）。(4) アーキテクチャ説明にデータスコープ・監査ログ・電子契約・freee連携・月次締め等の主要モジュールの記載が無い。
- 改修方法: CLAUDE.md の Security/ロール/マイグレーション/主要モジュール一覧を現状へ更新し、「マイグレーションの最新番号・欠番」「CSRFの方式（JSでの実装箇所）」「要員ロールの動線」を明記する。以後、仕組みを変えた改修は CLAUDE.md 追随を完了条件に含める（レビューチェックリスト化）。
- 期待効果: 以後のAI改修が誤った前提（例: 「APIはCSRF不要」）でコードを書く事故を防ぐ。

---

## 3. P3 — 最適化・堅牢性・衛生

### A7-14 【P3】メニュー権限判定が毎リクエスト最大3回のDBアクセス

- 対象: `config/MenuPermissionFilter`（`menuMapper.selectList(null)` + `getMenuKeysByRole` を毎リクエスト実行）、`config/GlobalControllerAdvice.allowedMenus`（ページ描画ごとに再取得）
- 改修方法: メニュー一覧と role→menuKeys を短TTLのインメモリキャッシュ（Caffeine等）に載せ、`RoleMenuApiController` の保存時に無効化する。フィルターとアドバイスで同じキャッシュを共有する。
- 期待効果: 全画面・全APIの一律オーバーヘッド削減（権限系はアクセスの度に3クエリ→ほぼ0）。

### A7-15 【P3】MenuPermissionFilter の fail-open（例外時に素通し）が権限制御の空白を作る

- 対象: `config/MenuPermissionFilter`（DB例外時 warn ログのみで許可）
- 改修方法: テストスライス対応（Bean不在時の素通し）はそのままでよいが、**Beanが存在するのにクエリが失敗した場合**は 503 で fail-closed にするか、少なくとも設計判断（可用性優先で fail-open とする）を README/コメントで明文化して監視アラート（warnログの監視）を前提にする。
- 期待効果: DB障害中に権限制御だけ静かに消える、という説明不能な状態を無くす。

### A7-16 【P3】非管理者のログイン後フォールバック先が6メニュー固定で、それ以外のみ許可のロールは403へ落ちる

- 対象: `controller/page/LoginPageController.index`（engineer/project/customer/proposal/contract/user の固定チェーン）
- 改修方法: `m_menu` は `path_prefix` を持っているので、「allowedMenus の先頭（sort_order順）のメニューの path_prefix へリダイレクト」というデータ駆動に置き換える。どのメニューも無いロールは専用の案内ページ（権限が付与されていない旨）へ。
- 期待効果: ロール×メニューのどんな設定でもログイン直後に403にならない（MI-15 の恒久解）。

### A7-17 【P3】PDFのCJKフォント解決が4箇所に重複し、毎回TTFを読み直す

- 対象: `InvoicePdfServiceImpl` / `QuotationPdfServiceImpl` / `TimesheetPdfServiceImpl` の各 `resolveCjkFont`、`skillsheet/SkillSheetGenerator`（+A7-03で追加される電子契約）
- 改修方法: `PdfFontProvider`（@Component）へ集約し、BaseFont を初回生成後キャッシュする。フォント未検出時の例外もここで一元化する。
- 期待効果: 生成のたびに数MBのTTFをロードする無駄を排除し、フォント方針の変更が1箇所で済む。

### A7-18 【P3】freee連携の堅牢性: refresh並行競合・type未検証・共有3秒タイムアウト・圧縮スタイル

- 対象: `service/impl/FreeeIntegrationServiceImpl`、`config/AppConfig.restTemplate`
- 現象と改修方法:
  1. `refresh` に並行ガードが無い。freee はリフレッシュトークンをローテーションするため、並行refreshの lost update で**保存済みリフレッシュトークンが使用済みになり連携が恒久破損**しうる → `t_freee_connection` 行を FOR UPDATE してから refresh し、更新後トークンを必ず保存する。
  2. `statements` の `type` パラメータを allowlist（salary/bonus）検証せず URL 連結している → 検証を追加。
  3. Webhook 用の3秒タイムアウト RestTemplate を freee/CloudSign にも共用している。給与明細取得やOAuthは3秒で切れると誤失敗する → 用途別に RestTemplate（外部SaaS用は接続5s/読取15s程度）を分ける。
  4. `FreeeIntegrationServiceImpl`/`FreeeOAuthController`/`FreeePayrollApiController`/`ContractDocumentServiceImpl`/`CloudSignClientImpl` は1行に複数文を詰め込む圧縮スタイルで、リポジトリ規約（Javadoc+通常整形）から逸脱 → 通常の整形へ書き直す（挙動変更なしのリフォーマット）。
- 期待効果: 本番での連携切断事故を防ぎ、当該5ファイルが人間にもAIにもレビュー可能になる。

### A7-19 【P3】MailService はコメントと実装が矛盾（「@Asyncで非同期」だが同期送信）

- 対象: `service/impl/MailServiceImpl`
- 現象: クラスコメントは非同期送信を謳うが `executeSend` は同期呼び出し。一括督促は対象件数分のSMTP往復をリクエストスレッドで直列実行する。宛先不正・テンプレート不明は `IllegalArgumentException` のため API では500になる（業務エラーとして4xxにすべき）。
- 改修方法: 送信本体を `@Async`（AsyncConfig の既存プール）へ移し、`MailDispatchResult` は QUEUED を返して履歴で結果を追う設計にする（一括督促の行結果は deliveryId 参照で足りる）。すぐやらないならコメントを実態（同期）に直し、`IllegalArgumentException` は `BusinessException(400)` へ置き換える。
- 期待効果: 大量督促でもUIが固まらない。エラー分類が正しくなる。

### A7-20 【P3】ダッシュボードのモック残骸（静的な偽データ行と偽成功トースト）

- 対象: `templates/dashboard/index.html`（`retiring-table-body` 内の「田中 太郎」モック行、`onclick="matchAI(1)"`）、`static/js/common.js`（`window.matchAI` の偽成功トースト）
- 現象: 一覧はJSで置換されるが、**API失敗時はモック行が表示され続け、実在しない要員がダッシュボードに出る**。common.js の `window.matchAI` は2秒後に「マッチングが完了しました」と偽の成功を表示する関数で、dashboard.js が同名関数で上書きしないページでは今も生きている。
- 改修方法: モック行を削除し、読み込み失敗時はエラー行を描画する。`window.matchAI` を削除し、dashboard.js のローカル実装のみ残す（インライン onclick も削除してイベント委譲へ）。
- 期待効果: 本番画面に偽データ・偽成功が出る経路（MI-20系の最後の残り）を根絶する。

### A7-21 【P3】リポジトリ衛生: コミット済みの一次スクリプトとAI痕跡コメント

- 対象: `fix.py`（ルート直下にコミットされた一次置換スクリプト）、`InvoiceServiceImpl` 463〜465行の未完了検討コメント（A7-05で対応）、`ContractPriceSyncService` 73行の英語独白コメント（かつ `updateCount` は「更新件数」ではなく処理件数で、ログ文言が誤り）
- 改修方法: `fix.py` を削除。独白コメントは削除し、`updateCount` は実際に更新した契約数のみカウントするかログ文言を「処理N件」に直す。
- 期待効果: 成果物リポジトリから作業スカフォールドを排除し、ログの意味を正す。

### A7-22 【P3】一覧の固定サイズ取得による silent truncation（請求100件・ガント/契約候補1000件）

- 対象: `static/js/modules/invoice.js`（`size=100` 固定・ページャ無し）、`contract-gantt.js`（`size=1000`）、各モーダルの候補select（options API は全件だが、maxLimit=1000 の一覧系は上限で切れる）
- 改修方法: 請求一覧にページャ（既存の契約一覧と同じ規約）を付ける。ガントは期間フィルタ（表示範囲の契約だけ取得する期間パラメータ付きAPI）へ移行し、1000件上限で黙って欠けない設計にする。「上限到達時に『さらにある』ことをUIに表示する」ことを共通規約にする。
- 期待効果: データ増加時に「画面に出ないだけで存在する」事故を防ぐ（MI-21/R5-05の恒久解）。

### A7-23 【P3】ガントの日付フォールバックが架空の契約期間を描画する

- 対象: `static/js/modules/contract-gantt.js`（start/end 欠損時に '2026-04-01' / '2026-09-30' を固定使用）
- 改修方法: 日付欠損の契約はガントから除外して欄外に「期間未設定n件」と表示するか、終了日未定は「開始日+表示範囲末尾」までのオープンバーとして描画し凡例で明示する。ハードコード日付は撤去。
- 期待効果: 2027年以降も正しく動き、未設定契約が架空の期間で表示されない。

### A7-24 【P3】AIプロンプトの単価単位が再び1万倍ずれている（希望単価は「円」なのに「万円」を付与）

- 対象: `controller/api/AiRestController.chat`（希望単価に「万円」付与・案件単価下限は単位なし）
- 現象: V27 で `expected_unit_price` / `unit_price_min/max` の単位は「円」に統一され、UIも `¥` 表示している。しかしAIプロンプトは値へ「万円」を連結するため、80万円の要員が「800000万円」としてAIに渡る（MI-28 と同型の取り残し）。
- 改修方法: 金額→表示文字列の共通フォーマッタ（円単位、カンマ区切り、null時「未設定」）を1つ作り、AIプロンプト生成（AiRestController/AiApiController/skill-sheet系）はすべてそれを使う。単位の真実は「DBは円」であることをフォーマッタのコメントに固定する。あわせて `catch (Exception e) → 500 + e.getMessage()` の内部情報露出をやめ、汎用メッセージにする。
- 期待効果: AI機能を実接続した瞬間に誤った金額前提で提案が生成される事故を予防する。

### A7-25 【P3】セッション内に生パスワードハッシュが滞留する（LoginUser が SysUser を丸ごと保持）

- 対象: `config/LoginUser`、`config/CustomUserDetailsService`
- 改修方法: `LoginUser` に `CredentialsContainer` を実装し `eraseCredentials` で password を落とす（認証完了後 ProviderManager が呼ぶ）。SysUser 全体でなく必要フィールド（id/username/realName/role/status/lockedUntil）だけ保持する形も可。あわせてパスワード変更成功時に他セッションを無効化するか、少なくとも現セッションの再認証を検討（現状は変更後も旧セッションが全て生存）。
- 期待効果: セッションストア/ヒープダンプ経由のハッシュ露出面を縮小する。

### A7-26 【P3】月次締めのロック行が存在しない場合、直列化が静かに失われる

- 対象: `service/impl/MonthlyClosingServiceImpl.assertOpenForUpdate` / `confirmClosing`（`selectByIdForUpdate(CONFIG_KEY)` が null でもそのまま続行）
- 現象: `closing.confirmed-months` 行は V35 seed 前提。行が無い環境（seed漏れ・手動削除）では FOR UPDATE ロックが取れず、締めと工数更新の直列化保証が消える（現在は SystemConfigApiController で削除経路を塞いでいるが、防御が一段しかない）。
- 改修方法: 行が無ければ空JSONで INSERT してから改めて FOR UPDATE で取得する（insert-if-absent）。
- 期待効果: どの環境でも締めの排他が成立する。

### A7-27 【P3】GET /logout が有効（ログアウトCSRF）

- 対象: `config/SecurityConfig`（`logoutRequestMatcher(new AntPathRequestMatcher("/logout"))` はメソッド不問）
- 改修方法: CSRF有効化と揃えて logout を POST 限定（`AntPathRequestMatcher("/logout","POST")`）にし、ヘッダーのログアウトリンクをフォームPOST化する。
- 期待効果: 外部サイトからの強制ログアウト（いたずら級だがCSRF方針の一貫性）を防ぐ。

---

## 4. 既知残存（過去ラウンドからの持ち越し・再確認済み）

新規ではないが、今回のコード読取でまだ残っていることを確認したもの。二重報告を避けるためIDのみ再掲する。

| 旧ID | 現状 |
|---|---|
| R2-05 | `releaseIfIdle` の提案/契約カウントが非ロック読取（旧スナップショット）で、並行終了時に開いた提案を見落とし Bench 化しうる。afterCommit案は撤回されFP在庫のまま。counts を FOR UPDATE 化するか commit 後再導出+補償を再設計する。 |
| R6-02/R6-03 | Flyway repair 試験の allowlist 検証・実checksum fixture・`information_schema` でのshape比較は未了。Docker有効CIも未整備（ローカルでは両試験skip）。 |
| MI-26/MI-27 | User POST 経路の status allowlist / 各エンティティのDB ENUM・桁とDTO制約の完全同期は部分対応のまま（EngineerSaveDto に gender/employmentType/japaneseLevel の Pattern なし等）。 |
| MI-05/MI-06 | AI機能: APIキーをクライアントから受け取る設計、`ai.enabled` 系設定の多重定義は未統一（A7-24と同時に整理推奨）。 |

## 5. 推奨改修順

1. **A7-01 / A7-02**（P1。どちらも小さな修正で、要員機能と電子契約画面が使えるようになる）
2. **A7-05 / A7-06**（資金流の整合性。BP支払の締め保護とCAS、approve の current read をまとめて1件の改修に）
3. **A7-03 / A7-04 / A7-10**（電子契約モジュールの品質引き上げ3点セット。フォント・死コード判断・XSS）
4. **A7-07 / A7-11**（横串の権限・ガード漏れ。共通ヘルパー化して「適用漏れが起きない形」で直す）
5. **A7-08 / A7-09 / A7-12**（バッチと集計口径）
6. **A7-13 / A7-21**（文書とリポジトリ衛生。以後のAI改修の精度に直結するため早めに）
7. A7-14〜A7-27 は影響とコストを見て順次。

## 6. 完了条件（次回監査での検証項目）

1. 要員ユーザーで login→`/my/timesheet` 着地・パスワード変更成功の統合テストが緑。
2. 契約書画面で作成/送信/同期の後に一覧が自動更新され、`SES.toast(` という呼び出しがリポジトリに存在しない。
3. 電子契約PDFから日本語テキストが抽出できるテストが緑。署名済みPDF保存は「実装」か「明示的な未実装宣言+死コード削除」のどちらかに決着。
4. BP支払の全更新経路が締め済み月で4xxになるテスト、支払済への CAS 化テストが緑。
5. 改定×approve のMySQL並行試験で新金額のBPが生成される。
6. `size=-1` を全一覧APIへ投げるテストで全件返却が発生しない。
7. ダッシュボード当月売上と営業成績合計（未帰属行含む）が一致する集計テストが緑。
8. CLAUDE.md の CSRF/ロール/マイグレーション記述が実装と一致している。
9. `fix.py`・独白コメント・モック行・`window.matchAI` がリポジトリに存在しない。
