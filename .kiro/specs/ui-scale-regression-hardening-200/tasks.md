# Tasks — 200名規模 UI・同時実行回帰ハードニング

## 実行規約

- 上から順に実行する。`S1`完了前に`S2`の最終判定をしない。
- 各taskは実装、定向test、Demo、`review-ledger.md`更新まで終わった時だけ`- [x]`にする。
- 既存specの完了checkboxは変更しない。
- P1/P2を「手動で見えなくなった」だけで完了にしない。失敗を固定する自動testを追加する。
- Docker無しでMySQL testがskipされた場合、そのtaskは実装済みにできるが、最終`M2`は未完了のままにする。

- [x] 0. Baseline固定と変更範囲inventory
  - **対象ID**: R3-001〜R3-021
  - **Objective**: 修正前の失敗と既存利用箇所を固定し、互換endpointやscopeを誤って壊さない。
  - **実装ガイダンス**:
    - `test-baseline.md`の件数と現コードを照合する。
    - `/api/proposals/kanban`、`/api/tasks`、lead/grid endpointの全consumerを`rg`で列挙する。
    - session register、role menu、scope、candidate/quotation state machineの既存testを列挙する。
    - 変更予定fileを`review-ledger.md`へ登録する。
  - **テスト要件**: P1/P2各件について、既存testで未検出の理由と新規test名を記録する。
  - **Demo**: 修正前にR3-001、005、006、007、008、018の再現証跡を提示する。

- [x] S1. 永続session同時login deadlock修正
  - **対象ID**: R3-001
  - **Objective**: 異なる25ユーザーの同時loginをdeadlock/500なしで完了させる。
  - **実装ガイダンス**:
    - `sys_user`行lockを同一user mutexとして維持する。
    - active session取得の不要な`t_user_session FOR UPDATE`を除去する。
    - max concurrent 5、revoke reason、MFA/OIDC/break-glassを維持する。
    - retryだけを主修正にしない。
  - **テスト要件**:
    - H2: 同一user上限、expired/revoked除外、disabled user。
    - MySQL Testcontainers: 異なる25user同時register/loginが全成功、同一user 6sessionでactive 5。
    - login監査の成功/失敗整合。
  - **Demo**: `login-spike` 25/25成功、server logのdeadlock/ERROR 0。

- [x] S2. 容量summaryと複数credential対応
  - **対象ID**: R3-002、R3-003
  - **Objective**: login失敗を隠さず、実際の25ユーザーを試験できるharnessにする。
  - **実装ガイダンス**:
    - `CredentialFile`とworker別credential割当を追加する。
    - setup/request/total errorを分離・合算する。
    - single credentialがsession上限を超える場合はpreflightで拒否する。
    - secretを成果物へ出さない。
  - **テスト要件**: wrong password、credential不足、single credential超過、10 unique credentials、summary/exit code一致。
  - **Demo**: 意図的login失敗が`SetupErrors=1`かつ非0終了し、正常25userが`TotalErrors=0`になる。

- [x] S3. Capacity monitorとPowerShell互換
  - **対象ID**: R3-004、R3-021
  - **Objective**: 負荷中のmetricsを信頼でき、Windows標準shellでもhelperを実行できる。
  - **実装ガイダンス**:
    - Actuatorは認証付き取得または明示Unavailableにする。permitAll禁止。
    - `-RequireMetrics`を追加する。
    - `.ps1` encodingをPS5.1/PS7互換にする。
    - Maven failure時のmessage順とexit codeを修正する。
  - **テスト要件**:
    - `powershell.exe -NoProfile -File scripts/verify-like-ci.ps1`のparse/preflight。
    - `pwsh -NoProfile ...`同等確認。
    - Actuator 401時`Available=false`、RequireMetrics非0。
  - **Demo**: 両shellで起動し、認証済みmetricsまたは正しいUnavailable判定を表示する。

- [x] A1. BP availability ingestion review 500修正
  - **対象ID**: R3-005
  - **Objective**: review画面をThymeleaf 3.1で正常描画する。
  - **実装ガイダンス**: page controllerから`jobId`をmodelへ渡し、templateの`#request`を除去する。
  - **テスト要件**: MockMvcで有効job 200、jobId埋込、不存在404、権限外403/404、responseにstacktraceなし。
  - **Demo**: browserでjob list→review→pagination→確定/取消の主要動線を通す。

- [x] A2. 契約一覧server-side pagination
  - **対象ID**: R3-006
  - **Objective**: 147件すべてへUIから到達でき、scope後totalを正しく表示する。
  - **実装ガイダンス**:
    - backend default 20、UI size 10/20/50、max 100。
    - frontendへcurrent/size/pagination stateを追加する。
    - filter/CRUD後のpage補正を実装する。
  - **テスト要件**: 0/1/100/101/147件、負数/0/過大size、manager37件scope、filter後total、削除後最終page。
  - **Demo**: adminで8ページ、managerで2ページを操作し、147件目/37件目を表示する。

- [x] A3. 商機customer参照の事前validation
  - **対象ID**: R3-009
  - **Objective**: invalid/stale customerIdを業務errorへ正規化しDB FK 500を防ぐ。
  - **実装ガイダンス**: service共通methodで存在/scopeを確認し、create/update/convertへ適用する。FKは残す。
  - **テスト要件**: admin invalid ID、sales scope外、deleted customer、正常customer、update version conflictとの優先順位。
  - **Demo**: browser/APIでinvalid IDが400/404 JSONとなりserver ERROR 0。

- [x] B1. 要員Bench filterの正規化
  - **対象ID**: R3-007
  - **Objective**: 待機32件を一覧filterとdashboard linkの両方から取得できる。
  - **実装ガイダンス**: option valueを`Bench`へ修正し、URL query初期化と旧`待機`normalizeを実装する。
  - **テスト要件**: template/API contract、Bench32件、他3status、unknown status。
  - **Demo**: dashboard「リストを見る」と手動filterの両方が32件を表示する。

- [ ] B2. 要員detail loading/error state
  - **対象ID**: R3-008
  - **Objective**: API拒否/不存在時にdummy人物や操作buttonを残さない。
  - **実装ガイダンス**: hard-coded人物値を削除し、action初期disable、成功後enable、共通error rendererを追加する。
  - **テスト要件**: scope内200、scope外404、不存在404、network failure、ApiResult非200。error stateに「田中 太郎」が無い。
  - **Demo**: managerでscope外/内IDを順に開き、誤表示なしと正常detailを確認する。

- [ ] B3. 5role navigation可視性修正
  - **対象ID**: R3-010、R3-011
  - **Objective**: 操作して必ず403になるmenu/searchを該当roleへ表示しない。
  - **実装ガイダンス**:
    - マイ勤怠を要員role限定表示。
    - 横断検索を管理4role限定表示。
    - server permissionは変更しない。
  - **テスト要件**: 5role×sidebar/header×直接URL/API matrix。要員にCtrl+K handler/search requestなし。
  - **Demo**: 5roleでheader/sidebarを目視し、禁止routeの直接403も確認する。

- [ ] B4. Dashboard scope表記
  - **対象ID**: R3-017
  - **Objective**: KPIの値と「全社/対象範囲」表記を一致させる。
  - **実装ガイダンス**: scope flag/display nameをmodel/DTOへ渡し、4locale message keyを追加する。
  - **テスト要件**: admin全社、manager限定組織、sales scope on/off、4locale key parity。
  - **Demo**: adminと`r3_manager01`を比較し、managerに「全社」が残らないことを確認する。

- [ ] C1. 勤怠grid pagination
  - **対象ID**: R3-012
  - **Objective**: 147契約を段階取得しつつ月全体確定の意味を保つ。
  - **実装ガイダンス**: paged grid API、既定50/max100、filter/total/pagination、save後state維持。
  - **テスト要件**: 147件3page、月変更、scope、row save、approve/reject、月次確定が全page対象。
  - **Demo**: 3pageを移動し、別pageのrowを保存後も位置を維持する。月次確定件数をDB照合する。

- [ ] C2. 提案Kanban column paging / load more
  - **対象ID**: R3-013
  - **Objective**: 83件を初期全描画せず、全cardへ到達できる。
  - **実装ガイダンス**: 互換List endpointを残し、paged endpointを追加。column別20件とtotal、load more、filterを実装する。
  - **テスト要件**: status別0/1/21/83、scope後total、load more重複なし、drag/drop count/state、XSS escape。
  - **Demo**: 初期DOM card数が全83未満で、追加操作により83件すべてへ到達する。

- [ ] C3. CRM lead pagination
  - **対象ID**: R3-014
  - **Objective**: 41リードを20件単位で参照・変換できる。
  - **実装ガイダンス**: paged API、filter、scope後total、変換後page補正。
  - **テスト要件**: 41件3page、owner/status/source/keyword、sales scope、conversion後total。
  - **Demo**: sales roleで3ページを操作し、最終leadを表示する。

- [ ] C4. ToDo task paginationと担当者可視化
  - **対象ID**: R3-015
  - **Objective**: 81taskをpaged表示し、担当者ベースで運用できる。
  - **実装ガイダンス**: `/api/tasks/page`、TaskListDto、assignee batch取得、task専用pagination/filter関数。
  - **テスト要件**: 81件5page、未割当、assignee/status/priority/overdue/keyword、通知tab回帰、N+1 query抑止。
  - **Demo**: 担当者列、filter、5page、更新後page維持を確認する。

- [ ] C5. Dashboard rolloff Top-Nと一覧導線
  - **対象ID**: R3-016
  - **Objective**: dashboardを要約画面に保ち、全候補へ別一覧から到達させる。
  - **実装ガイダンス**: scope後total、終了日順Top10、`すべて見る`期間filter link。
  - **テスト要件**: 0/10/11/15候補、同日tie-break、manager scope、link queryと契約一覧filter一致。
  - **Demo**: dashboardは10行、total15、一覧導線先は15件を表示する。

- [ ] D1. 見積pagination i18n修正
  - **対象ID**: R3-018
  - **Objective**: 4localeですべてのplaceholderを正しく置換する。
  - **実装ガイダンス**: `SES.i18n.t`を配列1引数で呼ぶ。共通関数signatureは変更しない。
  - **テスト要件**: total 0/1/41、first/last page、4locale、未置換`{n}`なし。
  - **Demo**: 41件の1/5ページで自然な範囲文言を表示する。

- [ ] D2. 候補者編集動線
  - **対象ID**: R3-019
  - **Objective**: candidate CRUDのupdateを一覧から完結できる。
  - **実装ガイダンス**: row edit、modal populate、PUT、stage endpoint分離、terminal/converted rule一致。
  - **テスト要件**: update正常、validation、terminal/converted、stage不変、page/filter維持、監査log。
  - **Demo**: 既存候補者の連絡先/次actionを編集し、stage履歴を増やさず反映する。

- [ ] D3. 給与画面landmark修正
  - **対象ID**: R3-020
  - **Objective**: document内のmain landmarkを1つにする。
  - **実装ガイダンス**: inner mainをsection/divへ変更し、heading/label/keyboardを維持する。
  - **テスト要件**: rendered HTMLの`main`数1、主要form control label、既存responsive test。
  - **Demo**: HRで給与画面を開き、accessibility treeと操作を確認する。

- [ ] M1. 200名fixture・5role browser総合回帰
  - **対象ID**: R3-005〜R3-020
  - **Objective**: 個別修正を実際の200名運用動線として再検証する。
  - **実装ガイダンス**: production migrationへfixtureを追加せず、test-only seed/生成scriptをidempotentにする。
  - **テスト要件**: `test-baseline.md`の全件数、全role、先頭/中間/最終page、scope、403、CRUD、0件状態。
  - **Demo**: 管理者→営業→HR→マネージャー→要員の順にbrowser evidenceをledgerへ記録する。

- [ ] M2. 全量test・MySQL・容量gate
  - **対象ID**: R3-001〜R3-021
  - **Objective**: CI相当と実MySQL容量試験を通し、偽のgreenを残さない。
  - **実装ガイダンス**:
    - アプリを停止して`verify-like-ci.ps1`を実行する。
    - Docker有効環境でskip 0を確認する。
    - 修正アプリを再起動しlogin-spike/steady 25を実行する。
  - **テスト要件**: 全test failure/error/skip 0、25login success、steady request error 0、P95<500ms、server ERROR 0。
  - **Demo**: report path、summary.csv、server log grep、test件数をledgerへ記録する。

- [ ] M3. 実装handoffとReview ready
  - **対象ID**: 全件
  - **Objective**: 独立Review AIが説明を信用せず再現・照合できる状態にする。
  - **実装ガイダンス**:
    - `review-ledger.md`の全21行を埋める。
    - 未検証/環境制約を空欄にせず明記する。
    - diff、migration、test、Demo、known riskを要約する。
  - **テスト要件**: requirements ID→task→file→test→Demoのtraceability欠落0。
  - **Demo**: Review conversationへ実装commit/branch、ledger、test結果を渡す。
