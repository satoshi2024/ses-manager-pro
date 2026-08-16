# SES Manager Pro UI 実操作シミュレーション（真人操作レイヤー）実行仕様

本レイヤーは、実ユーザーがブラウザ上で「ページを開き、クリックし、入力し、遷移し、失敗して復帰する」操作を忠実に再現し、UX 品質（描画、エラー復帰、入力保持、二重送信防止、back/forward、再読込、IME、モバイル）を検証する実行仕様である。ITa/ITb/E2E の API・DB oracle とは証跡と判定を分離し、**操作・表示・復帰**の合格を独立に測る。

## 1. 位置づけと密度の定義

- `module-test-matrix.md` の U 次元は「適用可能な全次元を最低 1 回」という条件で、全 17 モジュールの U 相当ケースは一桁件にとどまる。この密度では、二重送信、入力消失、back/forward 破綻、IME 確定問題、モバイル操作不能、ネットワーク切断からの復帰不能といった実運用の欠陥を検出できない。
- 本レイヤーは **操作プリミティブ inventory（§3）** を分母とし、frozen page inventory と actor に対して適用可能な組合せを全て mapping する。論理ケース ID は `UI-*` とし、機械集計の正本とする。
- ブラウザ操作の忠実度は本レイヤーが担い、`e2e-business-scenarios.md` は業務・DB oracle に専念する。同一導線を両文書で実行しても証跡・判定は別 run とし、合算しない。`E2E-07` の「代表 UI trace 1 本」制限は本書の instance 展開に適用しない。
- 論理ケース × browser engine × viewport × network profile を実行 instance として展開し、ケース数と instance 数を分けて報告する。

## 2. 実行基盤と固定環境

### 2.1 harness

- 既存 `ops/e2e/scale-300/run-e2e.mjs`（Playwright、desktop/mobile、role/page スモーク）を拡張し、`ops/e2e/ui-sim/` を新設する。共通部品: ログインヘルパ、fixture 解決（natural key + `RUN_ID`）、evidence 出力、network shaping、console/pageerror 収集、video/trace。
- 失敗注入は QA profile の failpoint とブラウザ network interception（offline、timeout、503、遅延）で行う。本番 profile へテスト用 header や bypass を公開しない。

### 2.2 browser matrix と固定値

| 項目 | 固定値 |
|---|---|
| browser engine | Chromium / Firefox / WebKit（論理 ID ごとに 1 つ以上を指定。代表 ID は全 engine） |
| viewport | desktop 1440×900、tablet 768×1024、mobile 390×844（touch、`isMobile`） |
| locale / timezone / clock | `ja-JP`、`Asia/Tokyo`、`AS_OF=2026-08-17T09:00:00+09:00`、`TEST_MONTH=2026-07` |
| actor | 他文書と同じ `ADMIN=s300.admin01` / `HR=s300.hr01` / `SALES_A=s300.sales01` / `SALES_B=s300.sales02` / `MANAGER=s300.mgr01` / `MEMBER=s300.member001` |
| RUN_ID | `UI-YYYYMMDD-NNN`（新規データの自然キー・メール・備考へ埋め込む） |
| DB | `E2E-BASE-300` の case clone。ケース終了時に破棄し、mailbox/cache/mock state を reset |

### 2.3 必須証跡

- 全ケースで `evidence/{BUILD_SHA}/{RUN_ID}/{UI_ID}/` に次を保存する。
  - `meta.json`: UI ID、build SHA、seed/migration/fixture checksum、actor、browser engine/version、viewport、network profile。
  - `video.webm`、`trace.zip`、step 毎 screenshot、`console.json`（console/pageerror 全行）、`network.har`。
  - `db-before.json` / `db-after.json` と表示値の `assertions.json`（UI 表示の oracle はここに持つ。API/DB oracle は ITa/ITb/E2E に任せ、本書で再検証しない）。
  - 失敗・復帰ケースは failpoint 発火証跡と復帰操作の前後 UI を必ず含める。
- ブラウザの自動翻訳・拡張機能・サービスワーカーは無効化し、HTTP cache は標準を固定する。測定対象の外乱を manifest に記録する。

## 3. 操作プリミティブ inventory（`I` 分母）

frozen page inventory の各 route に対して、適用可能なプリミティブを mapping し `I` inventory として freeze する。適用しない組合せは理由を記録して分母から外し、黙って除外しない。mapping 済み率がそのまま §5 の Interaction gate になる。

| 記号 | プリミティブ | 検証内容 | 対象例 |
|---|---|---|---|
| `CLK` | クリック/タップ | ボタン・行・リンク・checkbox/radio、disabled 時の無反応、誤クリック | 全更新系モーダル、一覧行選択 |
| `DBL` | ダブルクリック/連打 | 二重送信防止、debounce、modal 重複 open、連打時の toast/エラー重複 | 保存/提出/承認ボタン |
| `HOV` | hover | tooltip、行アクション表示、Kanban card 操作 | 一覧、Kanban、グラフ |
| `DRG` | ドラッグ&ドロップ | Kanban 列移動、ファイル drop、選択範囲 | proposal Kanban、upload |
| `KEY` | キーボード | Tab 順、Enter/Esc、矢印、ショートカット、focus 可視 | 全フォーム、modal |
| `IME` | 日本語入力 | 変換確定タイミング、未確定中 Enter/submit、composition 文字 | timesheet、検索、コメント |
| `PST` | ペースト | 改行・巨大文字列・HTML・特殊文字の貼付け | 備考、メール、CSV 系入力 |
| `SCL` | スクロール | 遅延読み込み、sticky header、ページ位置保持 | 一覧、Kanban、gantt |
| `BKB` | back/forward | 検索条件・ページ位置・モーダル状態の復元、復帰後に二重 submit されない | 一覧→詳細、モーダル |
| `RLD` | 再読込 | 進行中送信、draft 保持、再読込後の二重反映なし | 提出/保存中 |
| `TAB` | タブ/別 window | 同時セッション、focus 喪失後の入力 | 2 タブ同時操作 |
| `RSZ` | リサイズ | breakpoint、table/menu 崩れ、modal のはみ出し | 全画面 |
| `NWS` | ネットワーク変調 | offline、timeout、503、遅延応答、途中切断からの復帰 | 全 AJAX 操作 |
| `SES` | セッション変動 | 期限切れ、二重ログイン、ログアウト中の継続操作 | 全画面 |
| `FLU` | ファイル操作 | upload キャンセル、0 byte、拡張子、download | resume/PDF/CSV |
| `A11` | アクセシビリティ | aria、focus 可視、コントラスト、読み上げ順 | 主要導線 |

## 4. ケース台帳

### 4.0 全 route ブラウザスモーク `UI-00-*`

- frozen page route 全件 × 許可ロールをブラウザで 1 回ずつ開き、200 描画（非許可は 403 画面）、console error 0、pageerror 0、代表 screenshot を保存する。route 1 件につき 1 ID（`UI-00-SMK-<seq>`、件数は route inventory 参照）。既存 `ops/e2e/scale-300/role-pages.tsv` をこの分母へ同期する。
- desktop は全 route。mobile は主要 20 route を別 ID として指定する。
- Page route gate（README §6）の「navigation 済み」は、このスモークをブラウザで合格した場合のみカウントする。

### 4.1 モジュール別実操作 `UI-<MOD>-nn`

以下に列挙する ID が current scope（現行実装）である。MOD-12/13（S14/S17）は `BLOCKED(M-PASS)` として ID のみ定義する。各 ID は browser engine × viewport を instance として 1 組以上実行する。

| ID | 画面と実操作の流れ | 期待される表示・UX 断言 | プリミティブ | instance 方針 |
|---|---|---|---|---|
| `UI-01-01` | `/login` → `/`。誤パスワード→エラー表示→正しい値でログイン。要員は `/my/timesheet` へ転送、再読込後もセッション維持 | エラー文言が画面上に表示され input が残る。ログイン後に 200 画面、console 0 | CLK/KEY/NWS/SES | 3 engine×desktop |
| `UI-01-02` | `/user/list` の検索・ページング、新規/編集モーダルを実クリック。バリデーションエラー後に入力が保持され再編集可能。保存ボタン連打 | 二重登録 0。エラー後も入力値保持。モーダルが focus を維持 | CLK/DBL/KEY/PST | 3 engine |
| `UI-01-03` | `/user/list` の role-menu タブで営業の invoice メニューを外す→再ログイン→サイドバーと直 URL を確認。管理者は常に表示 | 営業はサイドバー非表示かつ直 URL 403 画面。管理者は表示のまま | CLK/RLD/SES | desktop |
| `UI-01-04` | `/mfa/setup` → `/mfa/challenge`。TOTP 設定→ログイン復帰→code 入力、recovery code 導線。誤 code の表示 | 誤 code は 4xx 表示で input 残存。recovery code 使用後は再使用不可表示 | CLK/KEY/PST | desktop |
| `UI-01-05` | `/audit-log` の filter・ページング・詳細モーダル。営業での直アクセス | 管理者は操作可能、営業は 403 画面。ページングで表示件数が崩れない | CLK/SCL | desktop |
| `UI-02-01` | `/candidate/list` 新規登録モーダル（必須欠落→エラー保持→補完保存）、一覧反映、検索・ページング | 保存後一覧先頭に表示。エラー後入力保持。連打で重複登録 0 | CLK/DBL/KEY | desktop+mobile |
| `UI-02-02` | `/candidate/detail?id=...` の stage 遷移（1 段ずつ）、履歴表示。飛越し操作を試す | 飛越しはエラー表示で元 stage に復帰し、履歴に不正遷移が残らない | CLK/KEY | desktop+mobile |
| `UI-02-03` | 入社後、初期値取得→エンジニア作成モーダル→link の手動導線。同一エンジニアの再送 | 二重 link 0。遷移が切れず詳細へ戻れる | CLK/DBL | desktop |
| `UI-02-04` | `/resume-ingestion` の job 一覧→`/resume-ingestion/review/{id}` で差分・error 行をスクロール、行補正、confirm | 差分が比較でき、error 行が判別できる。confirm 後一覧の状態が更新 | SCL/CLK | desktop+mobile |
| `UI-03-01` | `/engineer/list` の複合 filter・ソート・ページング、255 件の移動。狭幅での table 表示 | ページ間で重複/欠落なし。狭幅で崩れず操作可能 | CLK/SCL/RSZ | desktop+mobile |
| `UI-03-02` | `/engineer/detail?id=...` の職歴/スキル/担当営業タブ CRUD モーダル、エラー時保持 | タブ切替で入力が消えない。エラー後保持 | CLK/KEY/PST | desktop |
| `UI-03-03` | 主担当変更モーダル（主/副表示）、主担当解除拒否の toast、変更後の一覧反映 | 変更後は一覧の担当列が更新される。拒否は toast で理由表示 | CLK/DBL | desktop |
| `UI-04-01` | `/customer/list` → `/customer/{id}`。登録/更新、連絡先モーダル、PII mask 表示切替 | PII は権限に応じ mask/表示が一貫。更新後詳細へ反映 | CLK/KEY | desktop |
| `UI-04-02` | `/crm/leads`、`/crm/opportunities`、`/crm/opportunities/kpi` の切替と stage 更新 | 各画面が同一 lead/商談を同じ値で表示。KPI が再読込後も一致 | CLK | desktop+mobile |
| `UI-04-03` | `/customer/list` 検索の連打（race）。遅い応答を挟んで古い結果が新結果を上書きしない | 表示は最後の入力の結果。途中応答で一覧が一時的に消えない | CLK/KEY/NWS | desktop |
| `UI-05-01` | `/project/list` の検索・ページング、`/project/detail?id=...` の skill 編集モーダル | 保存後詳細に反映。エラー後保持 | CLK/KEY | desktop |
| `UI-05-02` | `/ai/matching` の結果モーダル（score/理由表示）、要員選択の反映 | score と不足 skill が読める。選択が反映され再読込後も同値 | CLK/SCL | desktop |
| `UI-05-03` | `/ai/chat` で IME による日本語質問、エラー時の fallback 表示と再試行 | 未確定中の送信で文字化けしない。エラー時に手動入力へ fallback | IME/CLK/NWS | desktop |
| `UI-05-04` | `/project-ingestion` の job 一覧→`/project-ingestion/review/{id}` で差分・error 行操作、confirm | confirm 後一覧へ反映。再読込で状態保持 | SCL/CLK | desktop+mobile |
| `UI-05-05` | `/project/detail?id=...` の position board タブ（実装済み）で作成/遷移/削除モーダル | カード状態が遷移し、二重操作で重複 0 | CLK/DRG | desktop |
| `UI-06-01` | `/proposal/kanban` のカードをドラッグで列移動。不正移動も試す | 許可辺のみ移動し、不正移動は元の列へ復元。列件数が同期 | DRG/CLK | desktop+mobile |
| `UI-06-02` | カード モーダルの編集、保存ボタン連打 | 二重カード 0。連打でエラー連発せず toast は 1 件 | DBL/CLK | desktop |
| `UI-06-03` | `/email/template/list` の編集モーダル、変数プレビュー、未定義変数 | 未定義変数の表示が壊れない。保存後一覧へ反映 | CLK/KEY | desktop |
| `UI-06-04` | 成約→契約ドラフト確認導線、通知ベルの反映 | 確認画面から契約へ進み、通知ベルに未読が出る | CLK | desktop |
| `UI-07-01` | `/contract/list` と gantt/renewal の切替、filter、300 件のページング | 切替で状態保持、ページング崩れなし | CLK/SCL | desktop+mobile |
| `UI-07-02` | `/contract/detail/{id}` のタブ操作、単価改定モーダル | 保存後履歴に反映。エラー後保持 | CLK/KEY | desktop |
| `UI-07-03` | 契約 PDF の download、文書モーダルの操作 | download 後も一覧状態が維持され、download 失敗時はエラー表示と再試行 | CLK/FLU/NWS | desktop |
| `UI-07-04` | `/compliance-gate` の現行実装部分（capabilities/mappings 一覧）を管理者で操作し、非管理者は 403 画面 | 実装済み部分が表示され、G2 未受入部分は BLOCKED 表示または非表示 | CLK/SCL | desktop |
| `UI-08-01` | `/my/timesheet` の日次入力（IME・コピペ・キーボード移動）、月合計の即時反映、未保存で遷移 | 合計が即時更新。未保存のまま離脱しようとすると確認表示 | IME/KEY/PST/CLK | desktop+mobile |
| `UI-08-02` | 提出→read-only、差戻し→修正→再提出。提出後に再読込 | 提出後は編集不可表示。差戻し後だけ編集可能に戻る | CLK/RLD | desktop |
| `UI-08-03` | `/my/attendance`、`/my/leave` の grid 操作と休暇申請モーダル（残高表示） | 残高が表示され、申請後は残高が減る | CLK/KEY | desktop+mobile |
| `UI-08-04` | `/monthly-closing` の準備状況表示→申請モーダル、締め後の編集不可表示 | 締め後は編集不可表示。未解消の項目が一覧で判別できる | CLK | desktop |
| `UI-09-01` | `/invoice` の一覧 filter、請求生成導線、金額表示 | 生成後一覧へ追加され金額が PDF と一致 | CLK | desktop |
| `UI-09-02` | `/reconciliation` の消込候補選択→apply モーダル、残高更新、二重 apply | 二重消込 0。apply 後残高 0 表示 | CLK/DBL | desktop |
| `UI-09-03` | aging タブ、PDF download、督促モーダル | 境界日表示、download 失敗時の復帰 | CLK/FLU | desktop |
| `UI-09-04` | `/invoice/{id}/print` の印刷ページ表示、ブラウザ print ダイアログ前段階 | 印刷ページが壊れず、金額・税が一覧と一致 | CLK/RSZ | desktop |
| `UI-10-01` | `/bp-company`、`/bp-company/{id}` の登録/更新モーダル、contact タブ | 保存後 detail 反映。エラー後保持 | CLK/KEY | desktop |
| `UI-10-02` | `/bp-availability/list`、`/bp-availability-ingestion` の job 一覧と review 画面遷移 | 遷移で状態が保持され、job 状態が表示 | CLK | desktop |
| `UI-10-03` | review 画面の差分表示スクロール、行補正、confirm、エラー行表示 | 差分が比較でき、error 行が判別できる。confirm で一覧へ反映 | SCL/CLK | desktop+mobile |
| `UI-10-04` | `/bp-company/{id}` の価格交渉・リスクタブ操作、交渉依頼モーダル | 交渉ステータスが更新され、一覧へ反映 | CLK/SCL | desktop |
| `UI-11-01` | `/invoice` の BP 支払タブ、支払作成モーダル、layer 表示、締め後 read-only | 作成後タブへ反映。締め後は編集不可 | CLK | desktop |
| `UI-12-01` | **BLOCKED(M-PASS/S14)** profile/資格/経費申請の実操作（M-PASS 後に本表へ展開） | — | — | — |
| `UI-13-01` | **BLOCKED(M-PASS/S17)** preview/train/evaluate 画面操作（M-PASS 後に本表へ展開） | — | — | — |
| `UI-14-01` | `/`、`/dashboard/profit` の KPI 表示、対象月切替、グラフ hover/クリック、狭幅 | 月切替で全指標が再描画。グラフの tooltip が読める。狭幅で崩れない | CLK/HOV/RSZ | desktop+mobile |
| `UI-14-02` | `/sales-performance` の月切替、営業 drilldown、表操作 | drilldown の合計が親表と一致。ソートが効く | CLK/SCL | desktop |
| `UI-14-03` | `/organization` のツリー操作、`/system-config` の歩合設定編集と反映 | 編集保存後、sales-performance の計算に反映 | CLK/KEY | desktop |
| `UI-14-04` | `/analytics` の heatmap・availability-calendar・scenario-compare 切替と filter 操作 | 同一母集団で各表示が一致し、狭幅でも操作可能 | CLK/HOV/RSZ | desktop+mobile |
| `UI-15-01` | `/quotation` の作成モーダル→PDF preview/download、エラー時保持 | PDF が日本語で正常表示。エラー後入力保持 | CLK/FLU | desktop |
| `UI-15-02` | `/approval/inbox` の承認/却下、二重 approve、inbox 更新 | 二重 approve 0。操作後 inbox から消える | CLK/DBL | desktop+mobile |
| `UI-15-03` | `/sales-order`、`/acceptance` の注文/検収提出モーダル | 保存後一覧反映。エラー後保持 | CLK | desktop |
| `UI-15-04` | `/document/list`、`/document/detail/{id}` の登録→版追加→download、scan 未完了表示 | CLEAN 版だけ download 可。scan 中は状態表示 | CLK/FLU | desktop |
| `UI-15-05` | `/approval/routes` の一覧・詳細、`/approval/requests/{id}` の承認詳細画面、委任・責任者タブ | 経路・承認段階・委任が正しく表示され、詳細から承認操作へ遷移 | CLK/SCL | desktop |
| `UI-16-01` | `/payroll` の接続 status、従業員一覧、給与明細、link/unlink モーダル | 接続状態が表示され、明細が fixture と一致。非許可は 403 画面 | CLK/SCL | desktop |
| `UI-17-01` | `/todo` の一覧・作成・状態更新、通知ベルからのタスク作成導線 | タスク状態が更新され、通知→タスクが切れずに遷移 | CLK/KEY | desktop+mobile |

### 4.2 横断 UX パターン `UI-XX-nn`

| ID | パターン | 実操作 | 期待される表示・UX 断言 |
|---|---|---|---|
| `UI-XX-01` | 二重送信 | 全更新系モーダルの保存ボタンを 50ms 間隔で連打 | 業務レコード 1 件、toast 1 件、ボタンが処理中 disabled |
| `UI-XX-02` | back/forward | 一覧で filter・ページ位置を設定→詳細→戻る→進む | 検索条件・ページ位置・モーダル開閉が復元。二重 submit 0 |
| `UI-XX-03` | 進行中再読込 | 提出/保存の応答前に reload | 二重反映 0。再読込後は確定済み状態か未送信状態が判別可能 |
| `UI-XX-04` | IME 日本語入力 | timesheet・検索・コメントで変換確定前に Enter/submit | 未確定文字の送信・文字化け 0 |
| `UI-XX-05` | 検索 race | 遅い応答を挟んだ連続検索 | 最終入力の結果のみ表示。古い結果の上書き 0 |
| `UI-XX-06` | modal 制御 | 開閉・Esc・背景クリック・focus trap | 背景がロックされ focus が modal 内を巡回。Esc で入力が残る |
| `UI-XX-07` | 3 態表示 | 0 件検索、ローディング中、エラー応答の各一覧 | 空状態・スピナー・エラー復帰がそれぞれ表示され console 0 |
| `UI-XX-08` | 入力保持 | 全主要フォームでバリデーションエラー→再編集 | エラー後に入力値・選択が保持される |
| `UI-XX-09` | キーボードのみ | 主要導線（ログイン→一覧→詳細→保存→承認）を Tab/Enter のみで完遂 | フォーカス可視、順序破綻 0、操作完遂 |
| `UI-XX-10` | モバイル狭幅 | 主要一覧・Kanban・modal を 390×844 で操作 | タップ可能サイズ、横スクロール抑止、menu 展開 |
| `UI-XX-11` | オフライン復帰 | 操作中に offline→オンライン復帰→再操作 | エラー toast 後に復帰し、入力が失われず再送可能 |
| `UI-XX-12` | session 期限切れ | 操作中に session 失効→再ログイン | AJAX が安全にログイン画面へ誘導し、復帰後に未完了操作を続行可能 |
| `UI-XX-13` | ファイル操作 | upload 開始→キャンセル、0 byte、拡張子誤り、download 失敗 | 孤児アップロード 0、エラー表示と再試行 |
| `UI-XX-14` | 通知・toast | 通知ベル、未読数、toast スタック（連続 5 件） | 未読数が操作に連動。toast が重なり操作を妨げない |
| `UI-XX-15` | スクロール保持 | 長一覧でページング・戻る・リサイズ | スクロール位置・行選択が保持され、再描画で飛ばない |

### 4.3 E2E シナリオのブラウザ忠実リプレイ `UI-E2E-nn`

`e2e-business-scenarios.md` の 7 シナリオ正常系を、実クリック・think time・scroll・モーダル操作を含めてブラウザのみで再実行する。oracle は表示値・遷移・状態保持であり、DB oracle は E2E 側に委ねる（二重計上しない）。E2E-01/02/03/07 が current、E2E-04/05/06 は `BLOCKED(M-PASS)`。

| ID | リプレイ対象 | 追加の UX 断言 |
|---|---|---|
| `UI-E2E-01` | 採用→要員化→歩合（MOD-02/03/14） | 各画面が同一候補者名・金額を表示し、Kanban/一覧遷移で値が欠落しない |
| `UI-E2E-02` | CRM→AI→提案→承認→契約→署名（MOD-04/05/06/15/07） | 承認 inbox から次の承認者へ切替が正しく、ドラフト導線が戻れる |
| `UI-E2E-03` | 勤怠→締め→請求→消込（MOD-08/15/09） | 締め後 UI が read-only 化し、請求 PDF download が操作可能 |
| `UI-E2E-04` | **BLOCKED(M-PASS/S10/S12)** BP→S10→S12→KPI | — |
| `UI-E2E-05` | **BLOCKED(M-PASS/S13/S14/S15/S16)** portal・会計・文書 | — |
| `UI-E2E-06` | **BLOCKED(M-PASS/S17)** AI feedback・scope・監査 | — |
| `UI-E2E-07` | 排他・冪等・負荷時整合（代表 UI trace） | 競合 409 時に再読込を促す表示へ遷移し、後勝ち上書き画面が出ない |

## 5. 網羅 gate と判定

| Gate | 計算式 | 完了条件 |
|---|---|---|
| Route smoke | ブラウザ描画済み frozen route / in-scope frozen route | 100%（許可 200 と非許可 403 を別 assertion 化） |
| Interaction | mapping 済み ページ×プリミティブ / `I` inventory | 100% |
| UX 破綻 | 白画面・無限スピナー・console error・pageerror・focus 喪失・入力消失が 0 のケース / 実行ケース | 100% |
| A11 | 主要導線の自動検査（axe 相当）で critical/serious なし | 0 件 |
| Evidence | video/trace/screenshot/HAR/DB 前後が揃う instance / 実行 instance | 100% |

- 判定は「操作・表示・復帰」の合格であり、API/DB の合格へ読み替えない。API 応答の oracle が必要なケースは ITa/ITb/E2E に下位ケースを持ち、本レイヤーでは表示と復帰のみを断言する。
- モバイル viewport ではタッチ操作（タップ・スクロール・ドラッグ）を実操作で行い、hover 前提の操作が存在しないことを確認する。
- `UI-00` スモークは build ごとの回帰として CI 常設を目指す。時間的制約が CI を圧迫する場合は、代表 route のみを CI に置き、全 route は wave 実行時に走らせる（skip のまま放置しない）。

## 6. 完了条件

1. current scope の全 UI ID（スモーク・モジュール別・横断・E2E リプレイ）が PASS。
2. browser matrix の全 engine で代表 ID が 1 回以上実行され、viewport 別に主要導線が操作可能。
3. UX 破綻 0、A11 critical/serious 0、console/pageerror 0。
4. future（`UI-12-01`、`UI-13-01`、`UI-E2E-04/05/06`）は `BLOCKED(M-PASS)` として ID・依存 spec・owner を欠落なく報告し、M-PASS 後に full 分母として全 ID を PASS させる。
5. cleanup が全件成功し、case DB・mock state・mailbox・download 物の残存 0。
