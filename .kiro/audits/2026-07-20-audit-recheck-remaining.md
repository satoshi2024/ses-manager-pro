# 2026-07-19 監査（LOGIC-01〜22／UI-01〜15）対応状況の再点検と残指摘

- 再点検日: 2026-07-20
- 再点検対象: `main@1da4850`（修正コミット `95cbf6a "fix: resolve 37 bugs from module logic and UI audits"` およびそれ以降のround3是正を含む）
- 元資料:
  - [業務ロジック・モジュール間監査](./2026-07-19-module-logic-bug-audit.md)
  - [UI・画面間監査](./2026-07-19-ui-bug-audit.md)
  - [監査サマリー](./2026-07-19-audit-summary.md)
- 再点検方法: 各指摘の対象メソッド・ファイルを現行コードと突合（静的照合）。`JsSyntaxCheckTest` の実行を試行（後述の制約あり）。
- 状態: **37件中 22件は対応完了を確認。1件は未対応、13件は部分対応（残タスクあり）、1件はテスト基盤の実効性に問題**。以下の残指摘 R-01〜R-17 を別途対応すること。

---

## 1. 判定サマリー（元指摘37件）

| 元ID | 判定 | 備考 |
|---|---|---|
| LOGIC-01 | 完了 | `ProjectServiceImpl.updateProjectWithSkills` が契約/未終了提案ありの顧客変更を409で拒否 |
| LOGIC-02 | 完了 | `ContractServiceImpl.saveWithBusinessRules` が `status="準備中"` を強制。更新APIは `status` を無視し `/status` 専用APIへ誘導 |
| LOGIC-03 | **部分** | → R-05 |
| LOGIC-04 | **部分** | → R-06 |
| LOGIC-05 | 完了 | `confirmMonth` が確定対象 `records` 基準でBP生成・同期（`generateOrSyncBpFor`）。グリッド再検索依存を解消 |
| LOGIC-06 | **部分** | → R-07 |
| LOGIC-07 | 完了 | 案件本体＋スキルを `ProjectSaveDto` で受け、単一 `@Transactional` で保存 |
| LOGIC-08 | **ほぼ完了** | 親404・スキル存在400を削除前に検証。残: null `skillId` → R-16 |
| LOGIC-09 | 完了 | `ProposalServiceImpl.save` が `DuplicateKeyException`（`uk_proposal_active_engineer_project`）を409へ変換 |
| LOGIC-10 | 完了 | コミッション率0〜100、精算幅非負・大小関係をDTO＋`ContractServiceImpl.validate` の両方で検証 |
| LOGIC-11 | **部分** | 主要経路は `DateUtils.parseYearMonth`（400）へ統一済み。残: → R-08 |
| LOGIC-12 | **部分** | → R-09 |
| LOGIC-13 | 完了 | `FreeeIntegrationServiceImpl.link` が `confirmedBy = SecurityUtils.currentUserId()` を保存 |
| LOGIC-14 | **部分** | → R-10 |
| LOGIC-15 | 完了 | `/api/candidates/overdue` が入社・不採用・内定辞退を `notIn` で除外 |
| LOGIC-16 | 完了 | `BusinessException` の既定コードを400へ変更。403/404/409の明示指定も主要経路に導入済み |
| LOGIC-17 | **部分** | → R-11 |
| LOGIC-18 | 完了 | タイムラインの契約取得へ共通の期間重複条件（`startDate<=to` かつ `endDate IS NULL or >=from`）を適用 |
| LOGIC-19 | **ほぼ完了** | `startDate<=today` の契約のみ基準化し将来契約は除外。残: → R-14 |
| LOGIC-20 | **ほぼ完了** | ロール固定リスト・メニュー存在を事前検証。残: → R-15 |
| LOGIC-21 | 完了 | 主要リソースのGET/PUT/DELETE未存在が404へ統一（Engineer/Customer/Project/Contract/Candidate/User確認済み） |
| LOGIC-22 | 完了 | 請求明細 `work_record_id` 競合の `DuplicateKeyException` を409 `error.invoice.alreadyGenerated` へ変換 |
| UI-01 | 完了 | `engineer-detail.js` の余分な `}` を削除（95cbf6a diffで確認）。ただしテスト実効性 → R-17 |
| UI-02 | **未対応** | → R-01（最優先） |
| UI-03 | 完了 | `loadProjects` が `customerName` を送信し、`ProjectMapper.selectPageWithNames` が顧客JOINで絞り込み |
| UI-04 | **部分** | contract.js／proposal-kanban.js は `size=1000` 化済み。残: → R-02 |
| UI-05 | 完了 | 保存が単一API化され、失敗時は `finishSave()` を呼ばずモーダル・入力を保持 |
| UI-06 | 完了 | 指摘5ファイルすべて `${SES.i18n.t(...)}` またはDOM `.text()` へ修正済みを確認 |
| UI-07 | 完了 | `SES.util.getLocalDateString()`（ローカル日付）へ共通化。`toISOString()` の残存なし（コメント1件のみ） |
| UI-08 | **部分** | 日本語リソース・ai.js・案件モーダルは円へ統一。残: → R-03 |
| UI-09 | 完了 | `ProjectListDto.customerName` を返し、`SES.escapeHtml` で表示 |
| UI-10 | **部分** | 入力欄・保存payloadは追加済み。残: → R-12 |
| UI-11 | **部分** | 比較はDB固定列挙値へ修正済み（誤「不可」は解消）。残: → R-13 |
| UI-12 | 完了 | `contract-document.js`（契約選択・テンプレート・作成・送信・同期・DL・テンプレート管理）と画面を実装 |
| UI-13 | **部分** | 紐付け・解除・給与明細テーブルは実装。残: → R-04（氏名列が常に空になるバグ含む） |
| UI-14 | 完了 | 「freeeに接続」ボタンを `th:if="hasRole('管理者')"` で管理者のみ表示 |
| UI-15 | 完了 | `renderCsvResult` がDOM生成＋`.text()` へ変更。XSSシンク解消 |

---

## 2. 残指摘詳細

対応時は各項目の「期待結果」と「追加すべきテスト」を満たすこと。優先度は元監査の定義（P1: データ誤帰属・金額誤認・主要業務不能、P2: 特定条件の整合性破壊・誤表示、P3: 契約不統一・運用混乱）に従う。

### R-01 [P1・UI-02未対応] 候補者→エンジニア紐付けが依然として氏名再検索で誤紐付けし得る

- 元指摘: UI-02（監査サマリーの先行対処2番目。UI監査で最も優先度の高い部類）
- 対象ファイル／行:
  - `src/main/resources/static/js/modules/engineer.js:473-492` — `linkConvertedEngineer(candidateId)`
  - `src/main/resources/static/js/modules/engineer.js:442-471` — `saveEngineer()` の成功コールバック
  - `src/main/java/com/ses/controller/api/EngineerApiController.java:116-119` — `save()` が `ApiResult<Boolean>` を返す
- 現状の証拠: `engineer.js:473-474` に「新規登録された要員のIDをレスポンスから直接得られないため(POST /api/engineersはBoolean応答)、直近登録分を氏名で再取得して候補者へ紐付ける」というコメントごと監査時の実装がそのまま残っている。`GET /api/engineers?current=1&size=1&fullName=...` の先頭1件を `PUT /api/candidates/{id}/converted-engineer` へ渡す。
- 発生条件・影響: 同姓同名の要員が既に存在する、または保存直後に別ユーザーが同名要員を登録すると、候補者が**無関係の要員**へ紐付く。`convertedEngineerId` は一度設定すると `error.candidate.alreadyLinked` で付け替え不能（R-05参照）のため、誤紐付けの修復手段もない。
- 対応方法:
  1. `EngineerApiController.save` の応答を `ApiResult<Engineer>`（または `{id: ...}` を含むDTO）へ変更し、MyBatis-Plusが `save()` 後にエンティティへ書き戻す自動採番IDを返す。既存呼び出し元（`engineer.js` の通常保存、CSV取込等）は `res.code === 200` 判定のみでdataを見ていないため互換性は保てるが、全呼び出し箇所を確認すること。
  2. `engineer.js` の `saveEngineer()` 成功時に `res.data.id` を直接 `PUT /api/candidates/{id}/converted-engineer` へ渡し、氏名再検索（`linkConvertedEngineer` の `GET` 部分）を削除する。
  3. 望ましくは `POST /api/candidates/{id}/create-engineer` のような単一トランザクションのユースケースAPI（エンジニア作成＋候補者紐付け）を新設し、フロントの2段呼び出し自体をなくす。
- 期待結果: 候補者は「そのPOSTで作成された要員のID」にのみ紐付く。同姓同名・並行登録でも誤紐付けが発生しない。
- 追加すべきテスト: 同姓同名2名を事前登録した状態で新規作成→紐付けを実行し、候補者の `convertedEngineerId` が新規作成IDと一致することをAPI統合テストで検証。並行登録（2スレッド同名保存）でも各候補者が自分の作成IDへ紐付くこと。

### R-02 [P1・UI-04部分] 案件モーダルの顧客セレクトが既定10件のままで11件目以降を選択できない

- 元指摘: UI-04
- 対象ファイル／行:
  - `src/main/resources/static/js/modules/project.js:97-113` — `loadCustomersForSelect()` が `GET /api/customers` を**sizeパラメータなし**で呼ぶ
  - `src/main/java/com/ses/controller/api/CustomerApiController.java:47` — `size` の既定値は `10`
- 現状の証拠: contract.js:60-79 と proposal-kanban.js:140,147 は `size=1000` へ修正済みだが、project.js のこの関数だけ取り残されている。
- 発生条件・影響: 顧客が11件以上あると、案件の新規登録・編集モーダルで11件目以降の顧客を選択できない。さらに `editProject()`（project.js:204）は `$('#proj-customerId').val(proj.customerId || '')` とするため、対象顧客がセレクトに存在しないと**選択が空欄表示**になり、利用者が誤って別顧客を選び直す誘発要因になる。
- 対応方法: 最低限 `'/api/customers?size=1000'` へ揃える（他ファイルと同一パターン）。恒久対応としては監査の期待どおり検索可能なoption API（例: `/api/customers/options?keyword=`）またはserver-side検索Selectを導入し、contract.js／proposal-kanban.js の `size=1000` も同時に置き換える。編集時は現在値の顧客をリスト外でも必ずoptionとして注入する。
- 期待結果: 顧客・要員・案件が何件あっても、新規／編集モーダルで全件を選択でき、編集時の現在値が常に表示される。
- 追加すべきテスト: 顧客11件以上を投入したブラウザテストで、案件モーダルのセレクトに全顧客が並ぶこと、11件目の顧客で保存→再編集時に選択が維持されることを検証。

### R-03 [P1・UI-08部分] 英語・中国語・韓国語リソースの金額ラベルが依然「万円系」表記のまま

- 元指摘: UI-08（金額の最大1万倍誤認）
- 対象ファイル／行:
  - `src/main/resources/messages_en.properties:494-495` — `dashboard.chart.sales_label=Revenue (10k JPY)` / `profit_label=Profit (10k JPY)`
  - `src/main/resources/messages_en.properties:676-679` — `project.modal.price_min=Min Price (10k JPY)` / `price_max=Max Price (10k JPY)`（placeholderは `600000`/`800000` の円生値）
  - `src/main/resources/messages_zh_CN.properties:487-488, 669-672` — `销售额 (万日元)` 等
  - `src/main/resources/messages_ko.properties:487-488, 669-672` — `매출 (만 엔)` 等
- 現状の証拠: 日本語 `messages.properties:498-499, 680-683` は「(円)」へ修正済み。チャート・保存値は円の生値のため、en/zh/ko ロケールでは監査時と同じ1万倍誤読がそのまま残る。
- 対応方法: 3ロケールのラベルを円建て表記（`JPY` / `日元` / `엔`）へ修正する。単位換算はしない（値は円のまま）。同時に、`grep -rn "10k\|万日元\|만 엔\|만엔" src/main/resources/messages_*.properties` で他の金額キー（契約・見積・ダッシュボード関連）に同種の残存がないか横断確認する。
- 期待結果: 全ロケールで 800,000円 が「円建て単位の800,000」として表示され、単位ラベルと値の単位が一致する。
- 追加すべきテスト: ja/en/zh-CN/ko の4ロケールでダッシュボードチャートラベルと案件モーダルラベルをDOM検証するテスト（既存の i18n リソース整合テストがあればキー単位のアサーションを追加）。

### R-04 [P1・UI-13部分] freee給与画面: 従業員氏名列が常に空欄（DTOフィールド名不一致）ほかUX未完

- 元指摘: UI-13
- 対象ファイル／行:
  - `src/main/resources/templates/payroll/index.html:45` — `esc(e.name)` を表示するが、APIのDTOは `FreeePayrollApiController` → `FreeeEmployeeDto`（`src/main/java/com/ses/dto/payroll/FreeeEmployeeDto.java`）で、フィールドは `id` / `displayName` / `employmentType`。**`name` は存在しない**。
  - `src/main/resources/templates/payroll/index.html:16-21` — 紐付けフォームはSES要員IDとfreee従業員IDを**手入力のnumber/text欄**で受ける。
- 現状の証拠: 紐付け（`PUT /api/payroll/links/{engineerId}`）・解除・給与明細テーブル（従業員ID/年/月/種別/総支給/控除/差引）自体は実装された。しかし従業員一覧の氏名セルは `e.name` が常に `undefined` のため**全行空欄**になり、利用者はfreee IDを氏名で特定できない。また、(1) どのエンジニアがどの従業員に紐付いているかの表示がない、(2) エンジニアはID手打ちでプルダウン選択がない、(3) 金額の桁区切り書式・0件時の空状態表示がない。
- 対応方法:
  1. `index.html:45` を `esc(e.displayName)` へ修正（最優先・1行）。
  2. 既存リンク一覧を返すAPI（例: `GET /api/payroll/links`）を追加するか `employees` 応答へ `linkedEngineerId/linkedEngineerName` を含め、従業員テーブルに紐付け状態列を表示する。
  3. SES要員はセレクト（`/api/engineers?size=1000` またはoption API。BP除外条件付き）で選択させる。
  4. 明細テーブルの金額列へ `toLocaleString` 相当の書式と、0件時の「データがありません」表示を追加する。
- 期待結果: 接続済み状態で従業員氏名が表示され、紐付け済み/未紐付けが一覧上で判別でき、要員選択がID手打ち不要になる。
- 追加すべきテスト: providerモックで `displayName` を含む従業員2件を返し、氏名セルが空でないことをDOM検証。紐付け→一覧再表示→解除のブラウザテスト。明細0件／複数件の表示検証。

### R-05 [P2・LOGIC-03部分] 候補者紐付けAPIの検証が不完全（要員存在・冪等・重複・同時実行）

- 元指摘: LOGIC-03（入社ステージ検証・既紐付け拒否は実装済み）
- 対象ファイル／行: `src/main/java/com/ses/service/impl/CandidateServiceImpl.java:121-138` — `linkConvertedEngineer`
- 現状の証拠と残穴:
  1. **要員の存在検証がない**: 存在しない `engineerId` を送っても `updateById` は成功し、宙に浮いた参照が保存される（FKがなければ検知されない）。
  2. **`engineerId=null` がno-op成功**: `update.setConvertedEngineerId(null)` は MyBatis-Plus の `update-strategy: not_null` によりSET句から除外され、何も変更せず200が返る。
  3. **同一IDの再送が冪等でない**: 既紐付け時は同一IDでも `error.candidate.alreadyLinked`（400）になる。監査の期待は「同一IDなら冪等成功、異なるIDなら409」。
  4. **別候補者との重複検証がない**: 同じ要員を複数候補者へ紐付けられる。
  5. **同時実行**: 2リクエストが同時に既紐付けチェックを通過すると後勝ちで上書きされる（check-then-update間に排他がない）。
- 対応方法: (a) `engineerId` の必須・存在チェック（`engineerMapper.selectById`、なければ404）。(b) 既紐付け時、同一IDなら200成功・異なるIDなら409。(c) 他候補者の `convertedEngineerId` 重複を事前確認し409。恒久対応にはDBユニーク索引（`t_candidate.converted_engineer_id`、NULL許容のユニーク）を追加し `DuplicateKeyException` を409へ変換。(d) 更新を `UPDATE ... WHERE id=? AND converted_engineer_id IS NULL` の条件付きUPDATEにして更新件数で競合検知する。
- 期待結果: 「入社」候補者を、存在する未使用の要員へ一度だけ紐付けられる。再送は冪等、競合・重複は409、不正入力は400/404。
- 追加すべきテスト: 未入社／存在しない要員ID／null／同一ID再送／別ID再送／他候補者と同一要員／2スレッド同時紐付け、の7ケース。

### R-06 [P2・LOGIC-04部分] 候補者の新規登録（POST）で `convertedEngineerId` を注入できる

- 元指摘: LOGIC-04（更新側は `currentStage`/`convertedEngineerId` をnull化済み）
- 対象ファイル／行:
  - `src/main/java/com/ses/controller/api/CandidateApiController.java:89-94` — `save()` は `BeanUtils.copyProperties(dto, candidate)` 後そのまま保存
  - `src/main/java/com/ses/dto/candidate/CandidateSaveDto.java:22` — DTOに `convertedEngineerId` フィールドが残っている
- 発生条件・影響: `POST /api/candidates` のJSONへ `convertedEngineerId` を含めると、R-05の専用API検証（入社ステージ・重複等）を一切通らずに紐付け済み候補者を作成できる。
- 対応方法: `save()` で `candidate.setConvertedEngineerId(null)` を明示するか、DTOを Create/Update で分離して Create から当該フィールドを除去する（後者が監査の期待）。`id` もPOSTでは無視（null化）すること（現状 `id` を含むPOSTは任意IDでのINSERTになり得る）。
- 期待結果: 新規登録経路から `convertedEngineerId`・`id`・`currentStage`（検証なし値）を設定できない。
- 追加すべきテスト: `convertedEngineerId`/`id` を含むPOST後、DB値がnull/自動採番であることを検証。

### R-07 [P2・LOGIC-06部分] エンティティ直接 `@RequestBody` が7コントローラーに残存

- 元指摘: LOGIC-06（Contract/Candidate/Project はDTO化済み）
- 対象（現行で永続化エンティティを直接受けるもの）:
  - `EngineerApiController.java:117,125` — `Engineer`
  - `CustomerApiController.java:96,104` — `Customer`
  - `UserApiController.java:80,103` — `SysUser`
  - `EmailTemplateApiController.java:43,51` — `EmailTemplate`
  - `ProposalApiController.java:102` — `Proposal`
  - `SkillTagApiController.java:52,60` — `SkillTag`
  - `EngineerCareerApiController.java:37,46` — `EngineerCareer`
- 現状の証拠: 監査後の緩和は「個別フィールドのnull化」（例: `UserApiController.update` の `setStatus(null)`）に留まり、`createdBy`・`deletedFlag`・`id`（POST時）などの系統的な遮断はない。`MetaObjectHandler` によるcreated_by自動設定はあるが、payloadの値を確実に上書きするかはフィールドごとに未保証。
- 対応方法: 監査の期待どおりCreate DTO／Update DTOを段階的に導入する。優先順位は (1) `SysUser`（権限・状態を持つ）、(2) `Proposal`（`proposedBy`・状態）、(3) `Engineer`／`Customer`、(4) その他。DTO化までの暫定として、各 `save()` で `id`/`deletedFlag`/監査列を明示null化する共通ヘルパーを設ける。URLの `{id}` を唯一の更新対象IDとし、body内 `id` は無視する。
- 期待結果: 全主要APIで、監査列・論理削除・状態・関連ID・採番値はサービスのみが設定し、禁止フィールドを含むJSONは無視または400になる。
- 追加すべきテスト: 主要APIを対象に「禁止フィールドを含むJSONを送ってもDB値が変わらない」ことを共通のパラメータ化テストで検証（監査の指定どおり）。

### R-08 [P2・LOGIC-11部分] 工数の月次確定・再オープンが不正年月をno-op成功にする

- 元指摘: LOGIC-11（`monthlyGrid`/`saveHours`/営業成績/請求/タイムラインは `DateUtils.parseYearMonth`→400へ統一済み）
- 対象ファイル／行:
  - `src/main/java/com/ses/service/impl/WorkRecordServiceImpl.java:206-215` — `confirmMonth`：`checkClosing` → 対象行検索 → **空なら return（200成功）**。年月形式検証がない
  - `src/main/java/com/ses/service/impl/WorkRecordServiceImpl.java:286-294` — `reopenMonth`：同一パターン
  - 参考: `MonthlyClosingServiceImpl.assertOpenForUpdate:208-216` も `month` の形式検証をしない（存在しない月文字列は「未締め」扱いで素通り）
- 発生条件・影響: `POST /api/work-records/confirm?workMonth=2026-99`（等の不正値）が200成功で返り、クライアントは確定が行われたと誤認する。APIごとに不正年月の意味が異なるという監査指摘の残存。
- 対応方法: `confirmMonth`／`reopenMonth` の先頭で `DateUtils.parseYearMonth(workMonth)` を呼ぶ（1行ずつ）。`MonthlyClosingServiceImpl.assertOpenForUpdate` にも `validateMonth(month)` を追加する。
- 期待結果: 不正な年月はすべて400で拒否され、no-op成功が発生しない。
- 追加すべきテスト: `2026-99`／`2026-1`／空文字／`abc` を confirm・reopen 両APIへ送り、全て400になることを検証（既存の同種データセットテストがあれば対象APIを追加）。

### R-09 [P2・LOGIC-12部分] システム設定の検証が2キーのみで、列挙値・未知キーが未検証

- 元指摘: LOGIC-12（`billing.tax-rate`≥0 と `commission.rate`0〜100 は実装済み）
- 対象ファイル／行:
  - `src/main/java/com/ses/service/impl/SystemConfigServiceImpl.java:79-99` — `put()` のキー別検証
  - `src/main/java/com/ses/controller/api/SystemConfigApiController.java:47-72` — システム管理キー拒否・受注確率の数値検証あり
- 残穴:
  1. `commission.base-type` が任意文字列を受ける（許可値は「粗利」「売上」。`SalesPerformanceServiceImpl` は不明値の扱いが実装依存になる）。
  2. `billing.tax-rate` に上限がない（`999` 等の異常値を保存できる）。
  3. `notice.contract-end-days` 等の日数キーが負数・非数値でも保存できる（読取側 `getInt` は警告ログ＋既定値へ黙ってフォールバックし、画面表示値と実際の計算値が乖離する — 監査指摘そのまま）。
  4. 未知キーの新規作成が無制限（タイプミスしたキーが黙って保存され、実際の設定が変わらない）。
- 対応方法: 許可キーのスキーマ（型・範囲・許可値・説明）を1箇所（enumまたはMap定義）に集約し、`put()` で検証する。未知キーは400。税率は運用上限（例: 0〜100%）を設ける。列挙キー（`commission.base-type` 等）は許可値リスト照合。
- 期待結果: 不正値・未知キーは400でDB・キャッシュとも変更されず、設定画面の表示値と計算に使われる値が常に一致する。
- 追加すべきテスト: キーごとの正常・境界・不正値のパラメータ化テスト。不正時にDB値とキャッシュ値が不変であること。

### R-10 [P2・LOGIC-14部分] freee従業員の重複紐付けがDB例外500になり、provider障害と入力不正を区別しない

- 元指摘: LOGIC-14（非空・provider存在チェックは実装済み）
- 対象ファイル／行: `src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:67-83` — `link()`
- 残穴:
  1. **重複検証がない**: 同じ `employeeId` を別エンジニアへ紐付ける操作を事前確認しない。`t_freee_employee_link` にユニーク制約があれば `DuplicateKeyException` が未変換の500になり、なければ重複データが黙って作られる。
  2. **provider障害と入力不正の混同**: `employees()` はHTTP失敗時に空リストを返す実装のため、freee側障害時にすべての `employeeId` が「`error.payroll.invalidEmployeeId`（400）」となり、利用者へ誤った原因を提示する。
- 対応方法: (a) `linkMapper` で `freee_employee_id` の既存行（別エンジニア）を事前確認し409。ユニーク索引を追加し `DuplicateKeyException` も409へ変換。(b) `employees()` の取得失敗を例外として伝播（またはOptional化）し、`link()` では「provider未接続/障害=503系の業務エラー」と「一覧に存在しない=400」を分岐する。
- 期待結果: 重複は409、provider障害は「接続を確認してください」系のエラーになり、入力不正の400と区別できる。
- 追加すべきテスト: providerモックで 存在／不存在／重複（別エンジニア既紐付け）／obtain失敗 の4ケースのHTTPコードとメッセージを検証。

### R-11 [P2・LOGIC-17部分] 論理削除された待機（契約なし）要員は依然として過去の稼働率から消える

- 元指摘: LOGIC-17
- 対象ファイル／行:
  - `src/main/java/com/ses/service/impl/AnalyticsServiceImpl.java:70-73` — 分母の算入条件
  - `src/main/java/com/ses/mapper/EngineerMapper.java:18` — `SELECT id, created_at, deleted_flag FROM t_engineer`（論理削除も取得するようになった）
- 現状の証拠: 削除済み要員でも「当該月に稼働契約があった」場合は分母に残る改善は入った。しかし削除済みかつ当該月に契約がない要員（＝当時のベンチ要員）は `activeEngineerIds.contains` を満たさず分母から除外され、**過去月の稼働率が遡及的に上振れ、待機数が過少になる**。退職日時（deleted_at相当）を持たないため「削除時点より前の月」を判別できない、という監査指摘の根本は未解消。
- 対応方法: 監査の期待どおり、(a) 月次スナップショット（確定済みKPIの保存）を導入するか、(b) `t_engineer` へ在籍終了日（または `deleted_at`）を追加し「対象月末時点で在籍していたか」で分母を構成する。(b) の場合、論理削除時に `deleted_at` を自動設定する `MetaObjectHandler` 拡張または明示更新を行い、既存削除済みデータには移行時の補完ルールを定義する。
- 期待結果: 過去在籍要員を削除しても、確定済み過去月の稼働率・待機数が変わらない。
- 追加すべきテスト: 「前月ベンチのみ・当月削除」の要員を用意し、削除前後で前月の `utilizationTrend` 結果が不変であることを検証。

### R-12 [P2・UI-10部分] 必要人数の表示が未設定・0を「1名」へ改変したまま

- 元指摘: UI-10（モーダル入力欄・保存payloadは追加済み）
- 対象ファイル／行:
  - `src/main/resources/static/js/modules/project.js:150` — `${proj.requiredCount || 1}名`
  - `src/main/resources/static/js/modules/project.js:243` — 空入力を `1` として保存（`$('#proj-requiredCount').val() ? parseInt(...) : 1`）
- 発生条件・影響: DB値がnull／0の案件一覧で「1名」と表示され、実データと画面が一致しない。空欄保存が黙って1名へ変わるのも監査の期待（未設定は「未設定」表示、0を許可しないなら保存時に明示拒否）と不一致。
- 対応方法: 表示は `proj.requiredCount != null ? proj.requiredCount + '名' : '未設定'`（i18nキー化）。保存は空欄をnullで送るか、必須とするならフロント・`ProjectSaveDto`（`@Min(1)`）の両方で検証してエラー表示する。
- 期待結果: null／0／1／複数の各値が改変されずに表示・保存される。
- 追加すべきテスト: null・0・1・5 の表示文字列と、空欄保存時の挙動（null保存 or 400）をDOM/APIテストで検証。

### R-13 [P2・UI-11部分] リモート種別・空状態などのラベルがハードコード日本語で多言語化されていない

- 元指摘: UI-11（ローカライズ文言との比較による誤「不可」表示は解消済み）
- 対象ファイル／行: `src/main/resources/static/js/modules/project.js:126-129` — `'フル'`/`'一部'`/`'不可'` とtitle属性、`project.js:120` — `'データがありません'`、`project.js:106` — `'顧客を選択してください...'`、`project.js:306,331-333,339` — スキル行の `選択してください`/`初級`/`中級`/`上級`/`必須`
- 発生条件・影響: en/zh-CN/ko ロケールでも案件一覧・モーダルの当該文言が日本語のまま表示される（値の正しさは維持されるため誤認は小さいが、i18n方針＝`SES.i18n.t`/`SES.i18n.e` 経由に反する）。
- 対応方法: 比較はDB固定列挙値のまま維持し、表示ラベルだけ `SES.i18n.e('remoteType', proj.remoteType)` 等の既存enum翻訳へ置き換える。空状態・placeholder系は既存キー（`common.noData` 等）を再利用する。
- 期待結果: 全ロケール×全リモート種別で正しい訳語が表示され、ソース中のUI文言ハードコードが解消される。
- 追加すべきテスト: 監査指定の「全ロケール×全リモート種別の表示対応表」DOMテスト。

### R-14 [P3・LOGIC-19残] 待機日数の基準日に解約・開始済み準備中契約の終了日が混入し得る

- 元指摘: LOGIC-19（`startDate <= today` フィルタで将来契約の除外は実装済み。主要ケースは解消）
- 対象ファイル／行: `src/main/java/com/ses/service/impl/AnalyticsServiceImpl.java:125-130`
- 残穴: 基準日算出が契約ステータスを見ないため、(a) 開始日が過去の「準備中」契約（開始遅延中）の将来終了日が基準になり待機日数0になる、(b) 「解約」契約の終了日を実稼働終了として扱う（解約日と契約上の終了日が乖離しているデータでは待機期間を過少計上する）。
- 対応方法: 基準日の対象を `status IN ('稼動中','終了')`（実際に稼働が発生した契約）へ限定し、該当がなければ現行どおり `availableDate`／`createdAt` へフォールバックする。解約契約は解約日（`cancelDate` 相当のカラムがあればそれ）を優先する。
- 期待結果: 準備中ドラフト・解約・終了・契約なしのどの組合せでも、待機日数が実際に稼働が終了した日からの経過日数になる。
- 追加すべきテスト: 監査指定の組合せ（準備中更新ドラフト／解約／終了／契約なし）ごとの待機日数検証。

### R-15 [P3・LOGIC-20残] 管理者ロールのメニュー設定編集とフィルターバイパスの不一致が未解消

- 元指摘: LOGIC-20（ロール固定リスト検証・メニュー存在検証・トランザクションは実装済み）
- 対象ファイル／行: `src/main/java/com/ses/controller/api/RoleMenuApiController.java:48-78`、`src/main/java/com/ses/config/MenuPermissionFilter.java`（管理者常時バイパス）
- 残穴: (a) 管理者ロールのメニュー割当を空にする編集が可能だが、`MenuPermissionFilter` は管理者を常にバイパスするため**画面設定と実効権限が一致しない**（監査の「管理者設定を不変にするか、バイパスとの関係をUI・APIで明示する」が未対応）。(b) `menuIds` に重複IDがあると存在チェックの `count < size` に引っかかり「存在しないメニューが含まれています」という誤解を招くメッセージで400になる。
- 対応方法: (a) 管理者ロールへの `PUT /api/role-menus?role=管理者` を400/403で拒否する（推奨）か、UI側で管理者タブを読み取り専用にして「管理者は常に全メニューへアクセス可能」と注記する。(b) `menuIds` を `distinct` してから検証・保存し、重複起因の誤メッセージを解消する。
- 期待結果: 権限画面の表示と実際のアクセス可否が全ロールで一致し、重複入力が誤ったエラー文言にならない。
- 追加すべきテスト: 管理者ロール変更の拒否（または読み取り専用UI）、重複ID送信時の挙動。

### R-16 [P3・LOGIC-08残] スキル置換で `skillId` がnullの要素が事前検証を通過し500になり得る

- 元指摘: LOGIC-08（親存在404・スキル存在400の事前検証は実装済み）
- 対象ファイル／行:
  - `src/main/java/com/ses/service/impl/EngineerSkillServiceImpl.java:43-52`
  - `src/main/java/com/ses/service/impl/ProjectSkillServiceImpl.java:40-50`（同一パターン）
- 残穴: `skills` の要素に `skillId: null` が含まれると、`skillIds` にnullが入ったまま `skillTagMapper.selectBatchIds(skillIds)` へ渡り、SQL生成エラーまたは件数不一致の誤判定になる（監査の「`null` はNPE」ケースの残存）。
- 対応方法: 検証の先頭で `skills.stream().anyMatch(s -> s.getSkillId() == null)` を確認し、`BusinessException.of(400, "error.skill.notFound")`（または専用キー）で拒否する。両ServiceImplへ同一実装を入れる。
- 期待結果: null `skillId` を含むリクエストは400で、既存関連は変更されない。
- 追加すべきテスト: null含み・空配列・重複・不存在の各入力でHTTPコードと既存関連不変を検証（両エンティティ）。

### R-17 [P2・テスト基盤] JS構文検査がNode.js非搭載環境で黙ってスキップされ、実効化されていない

- 元指摘: UI-01の「追加すべきテスト」（CIで26ファイルへ `node --check` を必須化）および監査サマリー3.2
- 対象ファイル／行: `src/test/java/com/ses/web/JsSyntaxCheckTest.java:31` — `assumeTrue(nodeAvailable(), ...)` により `node` がPATHに無いとテストは**skip扱いで成功**する
- 現状の証拠: 本再点検で `mvn test -Dtest=JsSyntaxCheckTest` は exit 0 だったが、この開発機にNode.jsが存在しないため実際には1ファイルも検査されていない。監査が使った「同梱Node.js」も現リポジトリには存在しない。つまり UI-01（engineer-detail.js）と同種の構文エラーの再発を、現在のローカル `mvn test` は検出できない。
- 対応方法: (a) CIワークフローへNode.jsセットアップ（`actions/setup-node` 等）を追加し、このテストが実行されることをCI上で確認する。可能なら「CI環境変数が立っている場合はskipではなくfailさせる」分岐（`Assumptions` → CI時は `fail`）を入れ、CIでのサイレントスキップを防ぐ。(b) 開発機向けにREADMEまたはCLAUDE.mdへ「JS検査にはNode.jsが必要」と明記する。
- 期待結果: JS構文エラーを含む変更はCIで必ず失敗し、skipによる見逃しが発生しない。
- 追加すべきテスト: CI上でこのテストが「skipではなく実行」されたことをジョブログで確認（意図的な構文エラーを一時投入してfailすることの確認を推奨）。

---

## 3. 補足事項（優先度低・任意）

- `ProjectServiceImpl.saveProjectWithSkills/updateProjectWithSkills`（`src/main/java/com/ses/service/impl/ProjectServiceImpl.java:49-92`）は `ProjectSkillService` を `RequestContextHolder`＋`WebApplicationContextUtils` 経由で取得している。Webリクエスト外（バッチ・テスト・非同期）から呼ぶと `IllegalStateException` になる。循環依存回避が目的なら `ObjectProvider<ProjectSkillService>` のコンストラクタ注入へ置き換えるのが安全（機能バグではないが、テスト追加時に踏みやすい）。
- `V1__create_tables.sql:95,185-186,242` のカラムコメントは「万円」のままだが、`V27__money_flow_consistency.sql` が円へ修正するため最終スキーマは正しい。V1を直接読む開発者の誤解防止として、V1側コメントも円へ揃えることを推奨（既適用環境ではchecksum不一致になるため、`flyway repair` 前提か新規補正migrationで対応判断）。
- `ProposalServiceImpl` の409メッセージ（`"この要員はすでに同じ案件に提案中です。"`）と `SystemConfigServiceImpl`/`RoleMenuApiController`/`DateUtils` の400メッセージが**メッセージキーではなく日本語直書き**になっている。`GlobalExceptionHandler` のi18n解決に乗らないため、他APIとの多言語一貫性が必要ならキー化する。

## 4. 再点検の検証記録と制約

- 静的照合: 上記表の全37件について、元監査が挙げた対象メソッド・ファイルの現行実装を読み、修正の有無・内容を確認した。
- 自動テスト: 本再点検では全量 `mvn test` は実行していない（round3是正時に全緑の記録あり）。`JsSyntaxCheckTest` は実行したが、Node.js不在によりskipで成功しており（R-17）、JS構文の機械検証は未実施。JSはRead/差分確認による目視検証（UI-01該当箇所、95cbf6a差分）で代替した。
- ブラウザ実機確認: 未実施。R-01〜R-04の修正時は該当画面のブラウザ確認を行うこと。
- 完了判定: 各残指摘は「期待結果」と「追加すべきテスト」を満たした時点で完了とする（元監査サマリー第6節の方針を踏襲）。
