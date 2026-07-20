# モバイルUI（スマートフォン）バグ監査（第4回 / M-01〜M-18）

- 監査日: 2026-07-20
- 対象: `main@dd24526`
- 対象範囲: スマートフォン幅（375px級）・タブレット幅でのレイアウト／タッチ操作／表示崩れ、および全デバイス共通で画面表示に出るUI不具合
- 検証方法: `common.css` のブレークポイント・`MobileResponsiveLayoutTest` の検査範囲・各テンプレート/モジュールJSの静的照合（CSS計算は決定的なため机上で確定できるものを中心に採録）。M-07・M-08 は文言リソースとの突合で確定
- 既出指摘（2026-07-19 UI-01〜15、R系、N系）は対象外。すべて新規指摘
- 修正は行っていない（指摘のみ）

## 優先度定義

- **P1**: モバイルで主要操作が不能／誤操作でデータが変わる。
- **P2**: 表示崩れ・操作困難・誤表示が確実に再現する。
- **P3**: 表記・テーマ・軽微なUX。

---

## M-01 [P1] 提案カンバンがタッチ端末でスクロール不能＋スワイプが誤ドラッグになりステータス変更APIが飛ぶ

- 対象ファイル／行:
  - `src/main/resources/static/js/modules/proposal-kanban.js:15-44` — `new Sortable(column, {...})` に `delay` / `delayOnTouchOnly` / `touchStartThreshold` が**未指定**
  - `src/main/resources/static/css/kanban.css:142-164` — モバイルで列幅 82〜88vw（画面のほぼ全面がカード領域）
- 発生条件・影響: SortableJS は既定でタッチ開始と同時にドラッグを開始する。モバイルでは列がほぼ全幅のため、**縦スクロール・横スワイプのつもりの指の動きがほぼ必ずカードのドラッグになる**。結果、(1) ボードをスクロールできない、(2) 隣列へ落ちると `PUT /api/proposals/{id}/status` が発行され、**意図しない選考ステータス変更が実データに起きる**（許可遷移ならそのまま確定、不許可でもエラートースト連発）。
- 対応方法:
  1. Sortable オプションへ `delay: 200, delayOnTouchOnly: true, touchStartThreshold: 4` を追加する（タッチは長押しでドラッグ開始、マウスは従来どおり即時）。
  2. 併せて `fallbackTolerance: 3` を指定するとタップとの誤判定がさらに減る。
  3. ステータス変更確定前に SweetAlert2 で確認を挟むか検討（タッチ誤操作の最終防衛。デスクトップの操作感を損ねる場合は `window.matchMedia('(pointer: coarse)')` でタッチ時のみ）。
- 期待結果: モバイルで通常のスワイプはスクロールになり、長押し→ドラッグでのみカード移動できる。意図しないステータス変更が発生しない。
- 追加すべきテスト: Sortable 初期化オプションに `delayOnTouchOnly: true` が含まれることのJSアサーション（設定退行防止）。可能ならPlaywrightのタッチエミュレーションでスワイプ＝スクロールを検証。

## M-02 [P1] マイ勤怠（要員のスマホ利用が前提の画面）の日次入力フォームが1行6列に圧縮され入力不能

- 対象ファイル／行: `src/main/resources/static/js/modules/my-timesheet.js:94-104` — `dailyForm()` が `row g-1` 直下に**6個の `<div class="col">`**（日付/開始/終了/休憩/備考/追加ボタン）を横一列で生成
- 発生条件・影響: `.col` は等分割のため、375px幅では各カラムが約55pxになる。date/time input は値が見えず操作もほぼ不可能。**エンジニア本人がスマホで日次入力する engineer-self-service-timesheet の中核動線が実質使えない。**
- 対応方法: カラムを `col-6 col-md`（日付は `col-12 col-md`）などレスポンシブ指定へ変更し、スマホでは2列×3段に折り返す。追加ボタンは `col-12 col-md-auto` で全幅化。common.css の `min-height:44px / font-size:16px` は既に効くため、幅の確保のみで足りる。
- 期待結果: 375pxで全入力欄が実用サイズになり、片手で日次入力できる。
- 追加すべきテスト: ブラウザテスト（viewport 375px）で各inputの実効幅が最低120px以上であること。

## M-03 [P2] マイ勤怠の日次明細テーブル（7列）が `.table-responsive` 無しで、右端の削除ボタンが画面外へ切れる

- 対象ファイル／行: `src/main/resources/static/js/modules/my-timesheet.js:59-70` — `<table class="table table-sm table-bordered">` を `card-body` 直下に生成（横スクロールコンテナ無し）
- 発生条件・影響: 375px幅では7列（日付〜削除×）が収まらず、`body { overflow-x: hidden }`（common.css:176）のため**はみ出した右端列（備考・削除ボタン）が閲覧・操作不能**になる。
- 対応方法: テーブルを `<div class="table-responsive">` で包む（1行）。テンプレート側ではなくJS生成部（`card.innerHTML`）を修正する。work-record.js:224-229 の日次明細モーダル内テーブルも同様に包む。
- 期待結果: 明細がテーブル内横スクロールになり、削除ボタンへ到達できる。
- 追加すべきテスト: viewport 375px で削除ボタンが `elementFromPoint` で取得できること。

## M-04 [P2] 要員詳細のAIチャットモーダル: 固定250pxセレクトを含む非折返し行がスマホ幅で溢れる

- 対象ファイル／行: `src/main/resources/templates/engineer/detail.html:211-222` — `.d-flex.align-items-center.gap-2` 行に バッジ＋`style="width: 250px"` のセレクト（`:213`）＋「選択した雛形で作成」ボタン
- 発生条件・影響: 576px未満のモーダル幅（約340px）に対し合計約510pxで、行が右へはみ出しボタンが押せない／モーダル内に横スクロールが発生する。
- 対応方法: 行へ `flex-wrap` を付け、セレクトの固定幅を `style="min-width: 0"`＋`class="flex-grow-1 w-auto"` へ変更（または `@media (max-width: 576px)` で `#chat-template-select { width: 100% !important; }`）。ボタンは `ms-auto` を維持しつつ `flex-shrink-0`。
- 期待結果: スマホ幅でバッジ／セレクト／ボタンが折り返して全て操作できる。
- 追加すべきテスト: viewport 375px でモーダル本文に横スクロールが無いこと。

## M-05 [P2] 請求書生成モーダルが「顧客ID」の数値手入力（スマホで especially 致命的）

- 対象ファイル／行: `src/main/resources/templates/invoice/list.html:283-286` — `<input type="number" name="customerId" required>`（ラベル「顧客ID」）
- 発生条件・影響: 利用者はDB内部IDを知らないため、顧客一覧を別画面で開いてIDを調べる必要がある。スマホでは画面往復がさらに困難。R-04（payroll）で同種の「ID手打ち」を修正済みだが、この画面が取り残されている。
- 対応方法: `<select name="customerId">` へ変更し、`/api/customers?size=1000` で `companyName` を投入する（contract.js:79-90 と同一パターンをinvoice.jsの `DOMContentLoaded` へ追加）。X-05 の `?customerId=` パラメータが来ていれば初期選択する。
- 期待結果: 顧客名で選択でき、月次締めからの遷移では顧客が選択済みになる。
- 追加すべきテスト: モーダル表示時にセレクトへ全顧客が並ぶこと。

## M-06 [P2] ビューポート幅ちょうど992pxでハンバーガーもサイドバーも消え、ナビゲーション不能になる

- 対象ファイル／行:
  - `src/main/resources/static/css/common.css:494-509` — ドロワー化は `@media (max-width: 992px)`（**992pxを含む**）
  - `src/main/resources/templates/layout/header.html:10` — トグルは `d-lg-none`（Bootstrapは `min-width: 992px` で非表示 = **992pxを含む**）
  - `src/main/resources/static/js/common.js:239,248,255` — JS判定も `<= 992` / `> 992`
- 発生条件・影響: 幅ちょうど992px（分割ウィンドウ・一部タブレット横）で、サイドバーは `translateX(-100%)` で隠れ、かつトグルボタンも `d-lg-none` で消える。1pxの境界だが確実に再現し、メニュー操作が完全に不能。`MobileResponsiveLayoutTest` は `d-lg-none` の存在のみ検査しており境界一致は保証していない。
- 対応方法: common.css の2つのメディアクエリを Bootstrap 規約の `@media (max-width: 991.98px)` へ変更し、common.js の判定を `window.innerWidth < 992` / `>= 992` へ揃える（kanban.css等ページCSSに同種の `max-width: 992px` が無いことも確認する。現状は768/576/420pxのみで問題なし）。
- 期待結果: あらゆる幅で「サイドバー表示」または「ハンバーガー表示」のどちらかが必ず成立する。
- 追加すべきテスト: `MobileResponsiveLayoutTest` へ「common.css に `max-width: 992px` が存在しない（991.98pxである）」ことの静的アサーションを追加。

## M-07 [P2] 入金済み請求書の操作ボタンに生キー `invoice.btn.paymentHistory` が表示される

- 対象ファイル／行: `src/main/resources/static/js/modules/invoice.js:72` — `SES.i18n.t('invoice.btn.paymentHistory', '入金履歴')`。**キーが4ロケールの messages*.properties のどれにも未定義**（本監査でJS参照キー全300件と定義キーを突合し、静的キーの未定義はこの1件のみ）
- 発生条件・影響: `SES.i18n.t` は未定義キーのとき**キー文字列そのもの**を返す（第2引数はフォールバックではなく置換引数）。ステータス「入金済」の請求書の行に `invoice.btn.paymentHistory` という文字がボタンラベルとして表示される。
- 対応方法: `invoice.btn.paymentHistory` を messages.properties（入金履歴）/ _en（Payment history）/ _zh_CN（收款记录）/ _ko（입금 이력）へ追加する。`MessageBundleConsistencyTest` があるため1ロケール追加漏れはテストで検出される。
- 期待結果: 入金済行のボタンが各ロケールの訳語で表示される。
- 追加すべきテスト: 既存のキー突合テストに「JSが参照する静的キーが ja リソースに存在する」チェックを追加（本監査で使った抽出手順: `grep -rhoE "SES\.i18n\.t\('[^']+'" static/js` とプロパティキーの comm 突合）。

## M-08 [P2] カンバンのステータス更新トーストが常に「ステータスを「」に更新しました」になる

- 対象ファイル／行: `src/main/resources/static/js/modules/proposal-kanban.js:263` — `SES.i18n.e(newStatus)` と**引数を1つしか渡していない**（`SES.i18n.e(group, dbValue)` が正: common.js:37-42。dbValue undefined → 常に空文字を返す）
- 発生条件・影響: ドラッグでステータスを変えるたびに、状態名が空欄のトーストが出る（全ロケール・全デバイスで再現。モバイルではトーストが唯一のフィードバックのため特に不親切）。
- 対応方法: `SES.i18n.e('proposalStatus', newStatus)` へ修正する（EnumMappings に `proposalStatus` グループ定義済み）。
- 期待結果: 「ステータスを「一次面接」に更新しました」のように遷移先が表示される。
- 追加すべきテスト: `SES.i18n.e` を1引数で呼ぶ箇所が無いことのgrepベース検査（今回の横断grepでは本件1箇所のみ）。

## M-09 [P2] ToDo一覧の状態バッジ・既読ボタン・空状態がすべて「無文字」で描画される

- 対象ファイル／行: `src/main/resources/static/js/modules/todo.js:37`（空状態 `<td colspan="4"></td>` に文言なし）、`:55-57`（既読/未読バッジが `<span class="badge ...\"></span>` で**中身なし**）、`:72`（既読化ボタンが `<button class="btn btn-sm btn-outline-primary"></button>` で**アイコンもラベルもなし**）
- 発生条件・影響: 状態列は色だけの空バッジ、アクション列は約28pxの空ボタンになり、何のボタンか判別できない。モバイルではタップターゲットとしても小さすぎる（44px推奨）。0件時は完全な空白でロード失敗と区別がつかない。
- 対応方法:
  1. 空状態: `SES.i18n.t('common.msg.noData')` を挿入。
  2. バッジ: 未読=`todo.badge.unread`（未読）、既読=`todo.badge.read`（既読）のキーを新設し4ロケールへ追加、`th:text`相当で挿入。
  3. ボタン: `<i class="bi bi-check-lg me-1"></i>` ＋ `todo.btn.mark_read`（既読にする）ラベルを追加し `py-2 px-3` でタップ領域を確保。
- 期待結果: 一覧の全要素にラベルがあり、モバイルでも押しやすい。
- 追加すべきテスト: renderTable 出力に空の `<button></button>` が含まれないことのDOMテスト。

## M-10 [P3] ダッシュボードにモック行（田中 太郎）が初期表示され、API失敗時は残留する

- 対象ファイル／行:
  - `src/main/resources/templates/dashboard/index.html:174-198` — 退場予定リストの `<tbody>` にハードコードのモック行（田中 太郎/大手金融基盤刷新プロジェクト/`onclick="matchAI(1)"`）
  - `src/main/resources/static/js/modules/dashboard.js:244-246` — 0件時の空状態セルが**空文字**
  - `src/main/resources/static/js/common.js:498-503` — グローバル `window.matchAI` がデモ用の偽トースト（「マッチングが完了しました」）を出すモック実装のまま
- 発生条件・影響: 初回ロードの数百ms〜（低速回線では数秒）架空の要員が表示され、`/api/dashboard/summary` が失敗するとそのまま残る。モック行のボタンは存在しないID=1への導線。0件時は無言の空行。
- 対応方法: (1) テンプレートのモック行をスピナー行（`common.msg.loading`）へ差し替える。(2) dashboard.js の空状態セルへ `dashboard.list.empty`（新設キー）を挿入。(3) common.js の `window.matchAI` モックを削除する（dashboard.js が自前の `matchAI` を持つため、他ページからの参照が無いことを grep 確認済み）。
- 期待結果: どの状態でも架空データが表示されない。
- 追加すべきテスト: レンダリングHTMLに「田中 太郎」が含まれないこと（ページ共通テストへの1アサーション）。

## M-11 [P3] 契約モーダルの読込前プレースホルダーが `value="1"` で、セレクト読込完了前の保存が要員ID=1へ帰属する

- 対象ファイル／行: `src/main/resources/templates/contract/list.html:113,119` — `<option value="1" th:text="#{contract.engineer.select}">` / 案件側も同様
- 発生条件・影響: `loadSelectOptions()`（contract.js:59-128）が4本のGETを完了する前に「新規契約」を開いて保存すると、`required` は `value="1"` で満たされ、**契約が要員ID=1・案件ID=1へ誤帰属して保存される**。低速なモバイル回線で現実的に踏み得る。
- 対応方法: 両optionを `value=""` へ変更する（`required` が正しく機能し、未読込時は保存がブロックされる）。
- 期待結果: セレクト未読込のまま保存すると必須エラーになり、誤帰属が起きない。
- 追加すべきテスト: optionの `value=""` を検査する静的アサーション（テンプレート退行防止）。

## M-12 [P3] 月次締めドリルダウンの明細テーブルがハードコード日本語＋非レスポンシブ

- 対象ファイル／行: `src/main/resources/static/js/modules/monthly-closing.js:85,88,91,111,114`（ヘッダー配列 `['契約番号','要員','案件','操作']` 等）、`:86,89,103,112,115`（リンクラベル 修正画面/請求作成/支払画面/督促）、`:95,117`（`<table>` を `.table-responsive` 無しで挿入）、`:25,139,153`（`alert()` 使用）
- 影響: en/zh/ko でも明細ヘッダーが日本語のまま。4〜5列テーブルがスマホ幅で溢れて切れる。alert はモバイルのUX規約（Toast/Swal）から外れる。
- 対応方法: ヘッダー・リンクラベルを `closing.detail.*` キー群（4ロケール新設）へ置換。テーブル挿入時に `<div class="table-responsive">` で包む。`alert` を `SES.toast.error` へ置換。
- 期待結果: 全ロケールで翻訳され、スマホで横スクロール可能。
- 追加すべきテスト: ロケール en で明細ヘッダーに日本語が含まれないDOMテスト。

## M-13 [P3] 勤怠グリッドの表示詳細（0値・キャッシュ・日次モーダル）の小不具合群

- 対象ファイル／行: `src/main/resources/static/js/modules/work-record.js:49`（`value="${item.actualHours || ''}"` → **0h入力が空欄表示**）、`:73,76`（`billingAmount ? ... : '-'` → 0円が `-` 表示）、`:206-221`（`dailyCache` が承認・差戻し・再入力後も**無効化されない**）、`:223-238`（日次明細モーダル: ヘッダー日本語ハードコード＋`width: '600px'` 固定でスマホ幅を超える）、`:85`（「日次明細」ボタンラベルもハードコード）
- 対応方法: (1) `item.actualHours != null ? item.actualHours : ''` へ修正。(2) 金額も `!= null` 判定へ。(3) `approveWorkRecord`/`rejectWorkRecord`/`saveHours` 成功時に `delete dailyCache[id]`（または都度取得へ変更）。(4) モーダルは `width: 'min(600px, 92vw)'` とし、ヘッダー・タイトル・閉じるを `my.timesheet.*`/`common.*` キーへ置換、テーブルを `.table-responsive` で包む。
- 期待結果: 0時間・0円が正しく表示され、日次明細が常に最新、スマホで崩れない。
- 追加すべきテスト: actualHours=0 の行の input 値が "0" であること。

## M-14 [P3] マイ勤怠の素の fetch がセッション切れを握りつぶし、スマホ再訪時に無反応になる

- 対象ファイル／行: `src/main/resources/static/js/modules/my-timesheet.js:16-21,118-135,174-187` — `fetch(...).then(res => res.json())` のみで、401やHTML応答（ログインページへのリダイレクト）の分岐が無い。`common.js` のセッション切れ検知は jQuery ajax 専用のため適用されない
- 発生条件・影響: スマホでタブを長時間放置→再訪して「追加」や「提出」を押すと、レスポンスがログインHTMLになり `res.json()` が例外→**無反応**（コンソールにのみエラー）。要員はエラー理由を知る術がない。
- 対応方法: `SES.api`（common.js の fetch ラッパー。401→/login リダイレクト・ApiResult解包・CSRF付与を実装済み）へ全面的に置き換える。合わせて `alert`/`confirm` を Toast/Swal へ、`'対象の契約がありません'`・`'日付を入力してください'`・`'備考'` placeholder・未入力平日確認文（`:167-170`）を `my.timesheet.*` キーへ置換する。
- 期待結果: セッション切れ時は自動でログイン画面へ誘導され、文言が全ロケール対応になる。
- 追加すべきテスト: fetch モックが text/html を返した場合に /login へ遷移すること。

## M-15 [P3] 要員一覧のステータスバッジがハードコード日本語（多言語で翻訳されない）

- 対象ファイル／行: `src/main/resources/static/js/modules/engineer.js:296-300` — `'<span class="status-badge status-success">稼動中</span>'` 等の直書き（`SES.i18n.e('engineerStatus', ...)` 不使用）
- 影響: en/zh/ko ロケールでも一覧のステータスが日本語のまま（R-13 と同種の取り残し）。
- 対応方法: バッジclassの分岐はDB固定値のまま維持し、表示文字列のみ `SES.i18n.e('engineerStatus', eng.status)` へ置換する（engineer-detail.js:60 は対応済みなので同じ形へ揃える）。
- 期待結果: 全ロケールで訳語表示。
- 追加すべきテスト: ロケール en の一覧DOMに「稼動中」が現れないこと。

## M-16 [P3] エイジング明細モーダルがライトテーマでも固定ダーク背景＋ハードコード日本語、一括督促結果は請求書番号列にDB IDを表示

- 対象ファイル／行: `src/main/resources/static/js/modules/invoice.js:399`（`Swal.fire({ background: '#1e1e2d', color: '#fff' })` 固定）、`:384-390,398-399`（請求番号/残高/期限/経過日数/請求明細/対象の請求書がありません/日 のハードコード）、`:479`（一括督促結果テーブルで `r.invoiceId` を表示。テンプレート側ヘッダーは `invoice.table.invoiceNumber`＝請求書番号）
- 影響: ライトテーマ利用時に明細モーダルだけ暗色で浮く。多言語未対応。一括督促の結果画面では利用者が照合できない内部IDが「請求書番号」として並ぶ。
- 対応方法: (1) `background`/`color` 指定を削除し、Swal の外観は既存テーマ（common.css の `.modal-content` 相当のCSS変数）に任せるか、`document.documentElement.getAttribute('data-bs-theme')` で切り替える。(2) 文言を `invoice.aging.detail.*` キーへ（4ロケール）。(3) 一括督促APIの結果へ `invoiceNo` を含める（`InvoiceApiController`/DTOに追加）か、フロントで送信前に `selectedInvoiceIds` と行の請求書番号の対応Mapを持ち `invoiceNo` を表示する。
- 期待結果: テーマ整合・翻訳・実請求書番号の表示。
- 追加すべきテスト: 一括督促結果DOMに数値のみのセルではなく `INV-` 形式の番号が出ること（テストデータ準拠）。

## M-17 [P3] 細部のテーマ・初期表示の取り残し（顧客詳細タイムライン／BP支払タブ）

- 対象ファイル／行:
  - `src/main/resources/static/js/modules/customer-detail.js:83` — タイムライン境界線が `border-left: 2px solid #343a40` 固定（ライトテーマでほぼ不可視）
  - `src/main/resources/static/js/modules/invoice.js:35-36` — BP支払タブは検索ボタン/月変更でのみロード。タブを開いた初期状態は**無言の空テーブル**（エイジングタブは `shown.bs.tab` で自動ロードしており不統一: `:567-568`）
- 対応方法: (1) 境界線を `var(--border-color)` へ。(2) `bp-payment-tab` にも `shown.bs.tab` リスナーを追加し、`#bpWorkMonth` 未指定なら当月を設定してロードする。
- 期待結果: ライトテーマで境界線が見え、BP支払タブを開くと当月分が自動表示される。
- 追加すべきテスト: タブshownイベントでBP一覧APIが発行されること。

## M-18 [P3] ヘッダー「新規作成」メニューが一覧ページを開くだけで、作成モーダルは開かない

- 対象ファイル／行: `src/main/resources/templates/layout/header.html:83-88` — クイック作成の各項目が `/engineer/list` 等への単純リンク
- 影響: 「要員を登録」を選んでも一覧が表示されるだけで、利用者はもう一度「新規登録」ボタンを探して押す必要がある。モバイルではさらに一手間。機能欠陥ではないがラベルと挙動が不一致。
- 対応方法: リンクへ `?action=new` を付与し、各モジュールJSの初期化で `URLSearchParams` を見て作成モーダルを自動で開く（engineer.js は `applyCandidateConversionPrefill` で同型の仕組みを持つため同じ場所に追記。customer.js / project.js / proposal-kanban.js / contract.js も各1〜3行）。
- 期待結果: クイック作成から遷移すると該当の登録モーダルが開いた状態になる。
- 追加すべきテスト: `?action=new` 付きで一覧を開くとモーダルに `show` クラスが付くDOMテスト。

---

## 参考: 今回「問題なし」を確認した点（再調査の重複防止）

- 全一覧テーブルの `.table-responsive` 包み: my-timesheet（M-03）・monthly-closing（M-12）以外の主要20画面は適用済み。
- モーダルのスマホ対応（max-height・フッターのグリッド化・16px入力）: common.css 512-781 で網羅されており、個別画面での崩れは M-04 のみ検出。
- 更新系 fetch の CSRF ヘッダー付与: 全モジュールで `SES.csrf.header()` 適用済み（横断grepで漏れなし）。
- 通知ドロップダウン・ページヘッダー折返し・カンバンの横スクロール構造: `MobileResponsiveLayoutTest` の検査どおり実装されている。
- JSが参照する静的i18nキーの未定義は M-07 の1件のみ（300キーを4ロケール定義と突合）。
