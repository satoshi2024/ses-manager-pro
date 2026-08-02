# Requirements — 200名規模 UI・同時実行回帰ハードニング

## 0. 用語と前提

- **全社scope**: 管理者、または設定上全件参照するロールの可視範囲。
- **限定scope**: 組織scopeまたは営業担当scopeにより絞られた可視範囲。
- **setup error**: 負荷workerが業務request開始前に発生したCSRF取得/login/session作成の失敗。
- **到達可能**: filter値を事前に知らなくても、UIのpagination/追加読込操作だけで対象recordを表示・操作できること。
- **200名fixture**: `test-baseline.md`と同等の件数・分布を持つ再生成可能なテストデータ。

## Requirement 1: 同時loginと永続session整合性（R3-001）

### Acceptance Criteria

1. WHEN 異なる25個の有効アカウントがbarrierから同時にform loginする THEN THE システム SHALL 全25件を認証成功させ、HTTP 500、DB deadlock、session INSERT失敗を0件にする。
2. WHEN 同一アカウントで6件以上のsessionを順次または並行発行する THEN THE システム SHALL `app.security.session.max-concurrent-sessions`の上限を維持し、規約どおり古いsessionを失効する。
3. THE システム SHALL 同一ユーザーのquery→revoke→insertを直列化しつつ、異なるユーザー同士を不要に直列化またはgap lock競合させない。
4. WHEN session登録transactionが失敗する THEN THE システム SHALL login成功としてredirect/監査記録せず、秘密を含まない一貫したerror response/logを残す。
5. THE 修正 SHALL MFA、OIDC、break-glass、session一覧/失効、role変更時失効を退行させない。
6. THE 実装 SHALL H2単体テストだけで完了とせず、MySQL 8 Testcontainersで同時loginを再現する回帰テストを持つ。

## Requirement 2: 容量試験の正確性（R3-002〜R3-004）

### Acceptance Criteria

1. THE `capacity-baseline.ps1` SHALL setupとrequestを別々かつ合計でも集計する。
2. THE summary SHALL 少なくとも`RequestedUsers`、`AuthenticatedUsers`、`SetupErrors`、`Requests`、`RequestErrors`、`TotalErrors`、P50/P95/P99、ReqPerSecを出力する。
3. WHEN setupまたはrequestに予期しない失敗が1件以上ある THEN THE script SHALL 非0終了する。
4. WHEN 誤passwordを指定する THEN THE script SHALL `login-failed`をsummaryへ含め、`Errors=0`と表示しない。
5. THE script SHALL workerごとに異なるcredentialを安全に割り当てられるCSVまたはJSON入力を提供する。
6. WHEN 単一credentialかつstageがsession上限を超える THEN THE script SHALL 実行前に停止し、複数credentialを用意するか明示的session eviction modeを選ぶよう案内する。
7. THE script SHALL password、XSRF token、JSESSIONIDをconsole、CSV、JSONへ出力しない。
8. THE monitor SHALL Actuatorを認証付きで取得するか、取得不能を`Available=false`として明示する。401を取得成功として扱わない。
9. THE script SHALL 25user同時login testと、loginを錯峰した25session steady-state testを別scenarioとして実行・比較できる。

## Requirement 3: BP取込review画面（R3-005）

### Acceptance Criteria

1. WHEN 権限を持つユーザーが有効なjob IDのreview URLを開く THEN THE システム SHALL HTTP 200で画面を描画する。
2. THE template SHALL Thymeleaf 3.1で禁止された`#request`等のutility objectを参照しない。
3. THE page controller SHALL path variableのjob IDをmodel attributeとして明示的に渡す。
4. WHEN jobが存在しない、削除済み、またはscope外である THEN THE システム SHALL 統一404/403を返し、空のreview画面を表示しない。
5. THE 画面 SHALL review行、pagination、確定/取消操作を従来どおり利用できる。

## Requirement 4: 契約一覧の完全到達性（R3-006）

### Acceptance Criteria

1. THE 契約一覧 SHALL server-side paginationを使用し、既定20件、選択肢10/20/50、最大100件/ページとする。
2. THE 画面 SHALL total、現在範囲、current page、total pages、前後/ページ番号操作を表示する。
3. WHEN filter条件を変更する THEN current page SHALL 1へ戻る。
4. WHEN 147件存在する THEN 1件目、100件目、147件目 SHALL filter値を知らなくてもUIから到達できる。
5. WHEN managerの可視契約が37件である THEN API/UI total SHALL 37となり、scope外110件を件数にも含めない。
6. THE CRUD/状態変更後 SHALL 現在filterを維持し、対象pageが消滅した場合だけ直前の有効pageへ補正する。
7. THE API SHALL `PageUtils.safePage`を用い、負数、0、上限超過sizeを正規化する。

## Requirement 5: 要員statusとdetail error state（R3-007、R3-008）

### Acceptance Criteria

1. THE 待機filter SHALL 表示ラベルをlocale化し、送信値を正規DB値`Bench`にする。
2. WHEN `status=Bench` queryで要員一覧を開く THEN 画面 SHALL filterを選択済みにし、32件のBench fixtureを取得する。
3. THE template SHALL 実在人物に見えるhard-coded name/initial/profileを初期表示しない。
4. WHEN detail APIが403、404、または`ApiResult.code != 200`を返す THEN 画面 SHALL data領域と更新actionを非表示/disabledにし、権限/不存在error stateを表示する。
5. THE error state SHALL 他人の氏名、単価、skill、career、担当営業等を表示しない。
6. WHEN scope内の要員を開く THEN 従来のdetail、skill、career、follow-up、担当営業操作 SHALL 正常に利用できる。

## Requirement 6: CRM参照整合性（R3-009）

### Acceptance Criteria

1. WHEN 商機create/updateへ存在しないcustomerIdを送る THEN THE API SHALL 400または404の`ApiResult`を返し、DB exception/500を返さない。
2. WHEN customerIdがscope外である THEN THE API SHALL 既存の非漏洩404規約を維持する。
3. THE service SHALL DB update/insert前にcustomer存在確認とscope確認を行う。
4. THE DB foreign key SHALL 削除せず最終防衛として維持する。
5. THE create、update、convertの全write path SHALL 同じ参照検証規約を共有する。

## Requirement 7: ロールに一致するnavigation（R3-010、R3-011）

### Acceptance Criteria

1. THE `マイ勤怠`menu SHALL `要員`roleだけに表示する。
2. THE 管理者、営業、HR、マネージャー SHALL `マイ勤怠`dead linkを表示しない。
3. THE 要員 SHALL 横断検索button、modal、Ctrl/Cmd+K shortcutを表示・登録しない。
4. THE 要員へ`/api/search`権限を新規付与してUI問題を回避してはならない。
5. THE server SHALL `/my/**`を要員限定、`/api/search`を既存管理role限定として防御を維持する。
6. THE 5role permission matrix SHALL sidebar/header visibilityと直接URL/API responseの両方を自動テストする。

## Requirement 8: 大量一覧の段階取得（R3-012〜R3-016）

### Acceptance Criteria

1. THE 勤怠grid SHALL server-side paginationを使い、既定50件、最大100件/ページとする。
2. THE 勤怠月次確定 SHALL 表示pageに限定せず、対象scope・対象月全体を確定する既存意味を維持する。
3. THE 提案Kanban SHALL columnごとにtotalを表示し、初回20件、追加操作ごとに次の20件を取得する。
4. THE 提案Kanban SHALL 既存`/api/proposals/kanban`利用者を壊さず、paged endpointまたは後方互換responseを提供する。
5. THE リード一覧 SHALL server-side paginationを使い、既定20件、最大100件/ページとする。
6. THE ToDo task一覧 SHALL server-side paginationを使い、担当者名を表示し、status/priority/assignee/期限超過/keywordで絞り込める。
7. THE Dashboard SHALL 退場/rolloff候補を終了日順Top 10だけ表示し、scope後totalと`すべて見る`導線を提供する。
8. ALL paged endpoints SHALL filterとscopeをSQL query段階で適用し、page取得後にJavaで除外してtotalを壊さない。
9. ALL paged UI SHALL 0件、1件、最終page、filter後0件、削除後page補正を処理する。
10. ALL user-provided text SHALL `SES.escapeHtml`等の既存XSS対策を維持する。

## Requirement 9: scopeを正しく説明する文言（R3-017）

### Acceptance Criteria

1. WHEN dashboardが全社scopeである THEN KPI label SHALL 従来どおり「全社」を表示する。
2. WHEN dashboardが限定scopeである THEN KPI label/subtitle SHALL 「対象範囲」または対象組織名を表示する。
3. THE 稼動率、Bench、売上、粗利、chart、rolloff list SHALL 同じscope説明を使用する。
4. THE 変更 SHALL `messages.properties`、`messages_en.properties`、`messages_zh_CN.properties`、`messages_ko.properties`へ同じkey集合を追加する。

## Requirement 10: 見積pagination i18n（R3-018）

### Acceptance Criteria

1. THE 見積一覧 SHALL `common.page.info`へ位置引数を正しく渡し、array文字列、fallback文字列、未置換`{n}`を表示しない。
2. WHEN total=41/current=1/size=10 THEN 日本語 SHALL `41件中 1～10件目を表示`相当を表示する。
3. THE 4locale SHALL `{0}`、`{1}`、`{2}`をすべて置換する。
4. THE 共通`SES.i18n.t`の既存呼出し互換性 SHALL 維持する。

## Requirement 11: 候補者CRUDと給与accessibility（R3-019、R3-020）

### Acceptance Criteria

1. THE 候補者一覧 SHALL 各rowに編集actionを表示する。
2. WHEN 編集actionを実行する THEN form SHALL 現在値を取得・表示し、PUT成功後に同じpage/filterへ戻る。
3. THE 通常編集 SHALL candidate stage履歴/state machineを迂回しない。
4. THE terminal/converted candidateの編集可否 SHALL service側business ruleとUIの両方で一致する。
5. THE 給与画面 SHALL document内にmain landmarkを1つだけ持つ。
6. THE 給与画面 SHALL heading順序、label-control関連、keyboard操作を維持する。

## Requirement 12: Windows実行互換と検証結果の信頼性（R3-021）

### Acceptance Criteria

1. THE `verify-like-ci.ps1` SHALL Windows PowerShell 5.1とPowerShell 7の双方でparse・実行できる。
2. THE repository SHALL PowerShell file encoding方針を`.editorconfig`または運用文書へ明示する。
3. WHEN Maven build/testが失敗する THEN helper SHALL skip 0を成功メッセージとして先に表示せず、build failureを明確に報告する。
4. WHEN Dockerが無くTestcontainers testがskipされる THEN helper SHALL 非0終了し、対象classを列挙する既存CI契約を維持する。
5. THE 実装AI SHALL アプリ停止後に`verify-like-ci.ps1`を実行し、Windows jar lockをproduct bugと混同しない。

## Requirement 13: 回帰、性能、証跡

### Acceptance Criteria

1. THE 実装 SHALL 既存1,277テスト相当を退行させない。
2. THE 新規テスト SHALL 各R3 IDと対応づけ、test class/method名を`review-ledger.md`へ記録する。
3. THE browser Demo SHALL 管理者、営業、HR、マネージャー、要員の5roleを使用する。
4. THE 200名fixture SHALL 何度実行しても重複破損せず、test環境以外では実行されない。
5. THE 実装 SHALL 25session steady-stateでrequest error 0、P95 500ms未満を最低gateとする。baselineの41.65msから著しく悪化した場合は理由を記録する。
6. THE 同時login SHALL 25/25成功、deadlock 0を必須gateとする。
7. THE Review SHALL P0/P1/P2未解決0件、P3はユーザー明示承認がある場合のみ残置可能とする。

## Out of Scope

- freee実API接続、CloudSign実API接続、AI providerの実接続。
- role権限モデルそのものの再設計。
- 一覧全体のSPA化、React/Vue導入、build system導入。
- 200名fixtureをproduction seed/migrationへ追加すること。
- Windows上で起動中jarを`mvn clean`可能にすること。

