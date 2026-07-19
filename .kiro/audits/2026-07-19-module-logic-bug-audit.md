# 業務ロジック・モジュール間 不具合監査

- 監査日: 2026-07-19
- 監査基準: 監査開始時の `main@fc37fd4`（監査中に切り替わったP1〜P7機能ブランチの新規機能は対象外）
- 状態: 調査済み、未修正
- 件数: 22件（P1: 6、P2: 14、P3: 2）
- 関連: [監査サマリー](./2026-07-19-audit-summary.md)、[UI監査](./2026-07-19-ui-bug-audit.md)

## LOGIC-01 [P1] 案件の顧客変更で既存契約との顧客帰属が分裂する

- 対象モジュール: 案件管理 → 契約管理 → 工数・請求・売上集計
- 対象メソッド:
  - `ProjectApiController.update(Project)`
  - MyBatis-Plus汎用 `ProjectService.updateById(Project)`
  - `ContractServiceImpl.validate(Contract, Contract)`
- 発生条件: 顧客Aの案件に契約が存在する状態で、案件の `customerId` を顧客Bへ変更する。
- 実際の結果: 案件は顧客B、既存契約は顧客Aのままになる。契約更新時だけ案件・契約・顧客の一致を検証しており、案件更新側には逆方向の検証がない。請求は契約の顧客A、案件一覧や案件起点の分析は顧客Bとなり得る。
- 期待結果: 契約または提案が存在する案件の顧客変更を409で拒否する、または影響する契約・提案・見積・請求可否を検証した専用トランザクションで一括変更する。単純な `updateById` では変更しない。
- 追加すべきテスト: 契約あり／提案のみ／参照なしの3ケースで案件顧客変更APIを実行し、不一致行が生成されないことをDBで検証する。

## LOGIC-02 [P1] 契約新規登録で状態機械を迂回できる

- 対象モジュール: 契約管理 → 要員ライフサイクル → 工数・請求
- 対象メソッド:
  - `ContractApiController.create(Contract)`
  - `ContractServiceImpl.saveWithBusinessRules(Contract)`
  - `ContractServiceImpl.changeStatus(Long, String, LocalDate)`
- 発生条件: `POST /api/contracts` のJSONへ `status: "稼動中"`、`"終了"`、`"解約"` 等を直接指定する。
- 実際の結果: 新規登録時の `status` を「準備中」に固定していない。`稼動中` は要員状態連動まで発火し、`終了`／`解約` も専用状態遷移APIを経ず保存できる。
- 期待結果: 通常の新規契約は必ず「準備中」で作成する。別の初期状態を許可する業務要件がある場合も、明示的なユースケースと遷移検証を通す。`status` はCreate DTOに含めない。
- 追加すべきテスト: 全契約ステータスをPOSTして「準備中」以外が拒否または無視されること、要員状態が不正に変わらないことを検証する。

## LOGIC-03 [P1] 候補者→エンジニア紐付けの業務条件が検証されない

- 対象モジュール: 候補者管理 → エンジニア管理
- 対象メソッド:
  - `CandidateApiController.linkConvertedEngineer(Long, Map<String, Long>)`
  - `CandidateServiceImpl.linkConvertedEngineer(Long, Long)`
  - `CandidateServiceImpl.getEngineerInitialDto(Long)`
- 発生条件: `PUT /api/candidates/{id}/converted-engineer` を直接呼び、任意の `engineerId` を送る。
- 実際の結果: 候補者の存在しか確認しない。「入社」ステージ、エンジニアの存在、既変換、別候補者との重複を確認せず、再紐付けも可能。存在しないIDはDB例外で500になり得る。
- 期待結果: 「入社」候補者だけを対象に、存在する未紐付けエンジニアへ一度だけ紐付ける。再送は同一IDなら冪等成功、異なるIDなら409。エンジニア作成と紐付けは可能なら単一ユースケース／トランザクションにする。
- 追加すべきテスト: 未入社、存在しないエンジニア、同一再送、別ID再紐付け、同時実行を検証する。

## LOGIC-04 [P2] 候補者基本情報更新から変換済みIDを書き換えられる

- 対象モジュール: 候補者管理
- 対象メソッド: `CandidateApiController.update(Long, Candidate)`
- 発生条件: 候補者更新JSONへ `convertedEngineerId`、`deletedFlag`、監査列等を含める。
- 実際の結果: `currentStage` だけを `null` にしており、`convertedEngineerId` は汎用更新で反映され得る。LOGIC-03の専用APIを迂回できる。
- 期待結果: 候補者基本情報Update DTOに編集可能項目だけを定義し、`currentStage`、`convertedEngineerId`、ID、論理削除・監査列を受け取らない。
- 追加すべきテスト: 禁止フィールドを含むJSONを送ってもDB値が変わらないことを検証する。

## LOGIC-05 [P1] 解約・期間短縮後の工数再確定でBP支払額が同期されない

- 対象モジュール: 契約管理 → 工数管理 → BP支払管理
- 対象メソッド:
  - `WorkRecordServiceImpl.saveHours(...)`
  - `WorkRecordServiceImpl.confirmMonth(String)`
  - `WorkRecordServiceImpl.syncRootBpAmount(WorkRecord)`
  - `WorkRecordMapper.selectMonthlyGrid(String, String)`
- 発生条件: BP契約の工数を作成後、契約を解約または対象月外へ期間短縮し、既存工数を再オープン・編集・確定する。
- 実際の結果: 既存工数の更新は意図的に契約期間／状態ガードを免除している。一方、確定時のBP生成・同期対象は `selectMonthlyGrid` を再検索し、`稼動中/終了` かつ契約期間内だけを返す。そのため編集した工数は確定されるが、対応するBP支払作成・`syncRootBpAmount` が実行されず金額が分裂する。
- 期待結果: BP同期対象は「今回確定した `records`」を基準に要員雇用形態を結合して決定し、画面用グリッドの絞り込みに依存しない。支払済みの場合は現行どおり更新せず警告・通知する。
- 追加すべきテスト: 解約後、期間短縮後、支払前、支払済みの各ケースで工数 `paymentAmount` とBP支払額の一致／通知を検証する。

## LOGIC-06 [P1] 主要APIが永続化エンティティを直接受け取り、システム管理列を送信できる

- 対象モジュール: エンジニア、顧客、案件、提案、契約、候補者、ユーザー、メールテンプレート、スキル、経歴ほか
- 代表メソッド:
  - `EngineerApiController.save/update(Engineer)`
  - `CustomerApiController.save/update(Customer)`
  - `ProjectApiController.save/update(Project)`
  - `ProposalApiController.save(Proposal)`
  - `ContractApiController.create/update(Contract)`
  - `CandidateApiController.save/update(Candidate)`
  - `UserApiController.save/update(SysUser)`
  - `EngineerCareerApiController.save/update(EngineerCareer)`
- 発生条件: UIを経ずAPIへエンティティの全フィールドを含むJSONを送る。
- 実際の結果: `BaseEntity` のID、作成・更新日時、`deletedFlag` に加え、`createdBy`、`proposalId`、`renewedFromContractId`、状態・関連ID等を系統的に除外していない。個別に一部フィールドを `null` にする実装はあるが、入力境界が機能ごとに不統一である。
- 期待結果: Create DTO／Update DTOを分離し、URLのIDを唯一の更新対象IDとする。監査列、論理削除、状態、変換・更新元ID、採番値はサービスだけが設定する。
- 追加すべきテスト: 各主要APIで禁止フィールドを送信し、無視または400になることを共通パラメータ化テストで検証する。

## LOGIC-07 [P2] 案件本体と必須スキルの保存が非原子的

- 対象モジュール: 案件管理 → 案件スキル
- 対象メソッド:
  - `ProjectApiController.save/update`
  - `ProjectSkillApiController.replaceSkills`
  - フロント `project.js` の `saveProject()`／`saveProjectSkills()`
- 発生条件: 案件本体のPOST/PUT成功後、スキル置換APIが入力不正・FK・通信エラーで失敗する。
- 実際の結果: 案件本体だけがコミットされ、ユーザーが一つの保存操作として選んだスキルが反映されない。更新時は旧スキルとの組合せによって部分状態になる。
- 期待結果: 案件本体とスキル一覧を一つのコマンドDTOで受け、単一トランザクションで保存する。分離APIを維持する場合は、UIに部分成功を明示し、再試行可能にする。
- 追加すべきテスト: スキル登録を意図的に失敗させ、案件本体もロールバックされることを検証する。

## LOGIC-08 [P2] スキル置換が親・スキル存在確認前に削除を実行する

- 対象モジュール: エンジニアスキル、案件スキル
- 対象メソッド:
  - `EngineerSkillServiceImpl.replaceSkills(Long, List<EngineerSkill>)`
  - `ProjectSkillServiceImpl.replaceSkills(Long, List<ProjectSkill>)`
- 発生条件: 存在しない親ID、存在しない `skillId`、`null` の `skillId`、重複IDを送る。
- 実際の結果: 既存関連を先に削除してから重複排除・保存する。トランザクションにより多くはロールバックされるが、空配列＋存在しない親は成功し、`null` はNPE、FK不正はDB例外となる。業務エラーとして扱われない。
- 期待結果: 親の存在、全スキルIDの存在、非null、重複、業務上の必須項目を削除前に一括検証し、400/404で返す。検証失敗時は既存関連を変更しない。
- 追加すべきテスト: 上記入力ごとにHTTPコードと既存関連不変を検証する。

## LOGIC-09 [P2] 同一エンジニア×案件の重複提案がDB例外500になる

- 対象モジュール: 提案管理 → 要員状態
- 対象メソッド: `ProposalServiceImpl.save(Proposal)`
- 関連DB制約: `V24__engineer_sales_active_unique_keys.sql` の `uk_proposal_active_engineer_project`
- 発生条件: 同じエンジニア・案件のアクティブ提案を連続または同時に作成する。
- 実際の結果: サービスは事前確認も `DuplicateKeyException` 変換も行わないため、DB制約違反が予期しない500として返る。
- 期待結果: 通常経路は事前確認し、競合時のDB制約違反も409「既に提案中」へ変換する。失敗時に要員状態を変えない。
- 追加すべきテスト: 逐次重複と2トランザクション同時登録の両方で409を検証する。

## LOGIC-10 [P1] 金額計算用の率・精算幅にバックエンド上限／下限がない

- 対象モジュール: 契約 → 営業成績・コミッション → 工数精算
- 対象フィールド／メソッド:
  - `Contract.commissionRate`（`@PositiveOrZero` のみ）
  - `Contract.settlementHoursMin/Max`（大小関係のみ）
  - `ContractServiceImpl.validate(...)`
  - `SalesPerformanceServiceImpl.calculateMonthlyPerformance(String)`
  - `SettlementCalculator.calc(...)`
- 発生条件: APIへ `commissionRate > 100`、または負の精算幅を送る。
- 実際の結果: UIの `max=100`／`min=0` は回避できる。100%超のコミッションを計算でき、負の精算幅は固定単価相当の分岐へ入り、入力不正を別の計算方式として処理する可能性がある。
- 期待結果: コミッション率は0～100、精算幅は0以上（業務定義により正数）をDTOとサービスの両方で検証する。不正値は400で保存しない。
- 追加すべきテスト: 境界値 `-0.1, 0, 100, 100.1` と、負／片側null／逆転した精算幅を検証する。

## LOGIC-11 [P2] `yearMonth` 不正値の結果がAPIごとに500または成功扱いになる

- 対象モジュール: 工数、営業成績、稼働予定、請求
- 対象メソッド:
  - `WorkRecordServiceImpl.monthEndOf/monthlyGrid/confirmMonth/reopenMonth`
  - `SalesPerformanceServiceImpl.calculateMonthlyPerformance`
  - `AnalyticsServiceImpl.getAvailabilityTimeline`
  - `InvoiceServiceImpl.calcDueDate`
- 発生条件: `2026-99`、`2026-1`、空文字等を送る。
- 実際の結果: `YearMonth.parse` を直接呼ぶ経路は `DateTimeParseException` が500になる。工数確定・再オープンは先に対象行を検索し、行がなければ不正月でも成功終了する経路がある。API間で意味が一致しない。
- 期待結果: 共通 `YearMonth` パーサーで形式と値を検証し、全APIが400を返す。無効な月をno-op成功にしない。
- 追加すべきテスト: 各APIへ同一の不正月データセットを送り、すべて400になることを検証する。

## LOGIC-12 [P2] システム設定から不正な税率・コミッション率を保存できる

- 対象モジュール: システム設定 → 請求 → 営業成績
- 対象メソッド:
  - `SystemConfigApiController.update(List<SystemConfig>)`
  - `SystemConfigServiceImpl.put(String, String, String)`
  - `InvoiceServiceImpl.generate(...)`
  - `SalesPerformanceServiceImpl.calculateMonthlyPerformance(...)`
- 発生条件: `billing.tax-rate=-0.1`、`commission.rate=500`、任意の不正文字列／未知キーをAPIへ送る。
- 実際の結果: キー別の型・範囲・許可値検証がない。数値文字列なら負の税額や100%超コミッションへそのまま使用され、非数値は警告後に既定値へ黙ってフォールバックする。設定画面の表示値と実際の計算値が一致しない場合もある。
- 期待結果: 許可キーごとのスキーマを定義し、税率・コミッション率・日数・列挙値を保存前に検証する。未知キーの作成可否も明示する。無効値は400でDB・キャッシュを変更しない。
- 追加すべきテスト: キー別の正常・境界・不正値と、DB値・キャッシュ値・計算結果の不変を検証する。

## LOGIC-13 [P2] freee従業員紐付けの確認者が常にNULLになる

- 対象モジュール: freee給与連携 → 監査証跡
- 対象メソッド:
  - `FreeePayrollApiController.link(...)`
  - `FreeeIntegrationServiceImpl.link(Long, String, Long)`
- 発生条件: 管理者またはHRがエンジニアとfreee従業員を紐付ける。
- 実際の結果: コントローラーが `userId` に常に `null` を渡し、`confirmedBy` もNULLで保存される。
- 期待結果: 認証中ユーザーIDを `SecurityUtils.currentUserId()` 等で渡し、`confirmedAt` と合わせて監査可能にする。
- 追加すべきテスト: 管理者／HRで紐付け、`confirmed_by` がログインユーザーIDになることを検証する。

## LOGIC-14 [P2] freee従業員IDの実在性・重複を業務エラーとして検証しない

- 対象モジュール: freee給与連携
- 対象メソッド: `FreeeIntegrationServiceImpl.link(Long, String, Long)`
- 発生条件: 空、任意文字列、providerに存在しないID、既に別エンジニアへ紐付いたIDを送る。
- 実際の結果: エンジニアとBP除外だけを確認し、freee側従業員一覧との照合をしない。重複はユニーク制約のDB例外500になり得る。
- 期待結果: 非空、provider上の存在、未使用を確認し、競合は409で返す。provider障害と入力不正を区別する。
- 追加すべきテスト: providerモックで存在／不存在／重複／障害を検証する。

## LOGIC-15 [P2] 入社・不採用・内定辞退の候補者が期限超過一覧に残り続ける

- 対象モジュール: 候補者管理 → 通知・一覧強調
- 対象メソッド:
  - `CandidateApiController.getOverdue()`
  - `CandidateServiceImpl.changeStage(...)`
- 発生条件: 過去の `nextActionDate` を持つ候補者を終端ステージへ変更する。
- 実際の結果: 期限超過検索は日付だけで、終端ステージ除外や完了フラグがない。ステージ変更時にも `nextActionDate` をクリアしないため、対応済み候補者が期限超過として残る。
- 期待結果: 終端ステージを期限超過対象外とするか、終端遷移時に次アクションを完了／クリアする。履歴は維持する。
- 追加すべきテスト: 各終端ステージが期限超過APIから除外されることを検証する。

## LOGIC-16 [P2] 業務入力エラーの多くがHTTP 500になる

- 対象モジュール: 全REST API
- 対象メソッド:
  - `BusinessException.of(String, Object...)`
  - `GlobalExceptionHandler.handleBusinessException(...)`
- 証拠: 明示コードなしの `BusinessException.of("...")` が現行コードに160箇所、400/401/403/404/409を明示する呼び出しは11箇所。
- 発生条件: 未存在、状態遷移不正、入力不正、重複、未接続などの通常想定される業務エラー。
- 実際の結果: `BusinessException.of(String, ...)` の既定コードが500であり、`GlobalExceptionHandler` もHTTP 500へ変換する。クライアント、監視、監査ログ上で利用者エラーとシステム障害を区別できない。
- 期待結果: 例外ファクトリを `badRequest/notFound/conflict/forbidden/serviceUnavailable` 等へ分け、各業務エラーに正しいHTTPコードを与える。メッセージキーからコードを推測しない。
- 追加すべきテスト: 代表的な各エラー分類についてHTTPステータスと `ApiResult.code` の一致を検証する。

## LOGIC-17 [P2] 要員削除で過去の稼働率が遡及的に変わる

- 対象モジュール: エンジニア管理 → 分析
- 対象メソッド:
  - `AnalyticsServiceImpl.utilizationTrend(int)`
  - `EngineerMapper.selectCreatedAtOnly()`
- 発生条件: 過去月に在籍していた要員を論理削除する。
- 実際の結果: 分母は現在 `deleted_flag=0` の要員だけから過去月を再計算するため、削除した要員が過去の全月から消え、過去の稼働率・待機数が変わる。削除日時がないため退職時点も復元できない。
- 期待結果: 月次スナップショットを保持するか、在籍開始・終了日を持ち、対象月時点の母集団で計算する。論理削除を履歴上の不存在として扱わない。
- 追加すべきテスト: 過去在籍要員の削除前後で確定済み過去KPIが変わらないことを検証する。

## LOGIC-18 [P2] 稼働予定タイムラインで稼働中契約の期間重複条件が欠ける

- 対象モジュール: 契約管理 → 稼働予定分析
- 対象メソッド: `AnalyticsServiceImpl.getAvailabilityTimeline(...)`
- 発生条件: `status="稼動中"` だが表示範囲より後に開始する契約、または表示範囲より前に終了日を持つ不整合契約がある。
- 実際の結果: クエリの `稼動中` 分岐には `startDate <= toDate` と `endDate >= fromDate` がなく、範囲外契約も取得する。`終了` 分岐だけに期間条件がある。
- 期待結果: ステータス条件とは独立して、全対象契約へ共通の期間重複条件 `start <= to AND (end IS NULL OR end >= from)` を適用する。
- 追加すべきテスト: 範囲前、範囲内、範囲後、境界日の契約バーだけが返ることを検証する。

## LOGIC-19 [P2] 待機日数が将来終了の契約や対象外状態で0日に戻る

- 対象モジュール: 契約管理 → 待機分析・通知
- 対象メソッド: `AnalyticsServiceImpl.benchList()`
- 発生条件: `status="Bench"` の要員に、将来終了日の準備中契約、取消相当の契約、将来の更新ドラフト等が存在する。
- 実際の結果: 全ステータスの契約から最大 `endDate` を基準日にする。将来日なら `Math.max(0, ...)` により待機日数が0となり、長期待機を隠す。
- 期待結果: 実際に稼働終了した有効契約の終了日だけを使い、将来契約は別の稼働予定として扱う。適切な契約がなければ待機開始日／利用可能日を使う。
- 追加すべきテスト: 準備中更新ドラフト、解約、終了、契約なしを組み合わせて待機日数を検証する。

## LOGIC-20 [P2] ロール別メニュー置換がロール・メニューIDを検証しない

- 対象モジュール: ユーザー・権限管理
- 対象メソッド: `RoleMenuApiController.update(String, List<Long>)`
- 発生条件: 未知ロール、存在しないID、重複ID、管理者ロールの空リストを送る。
- 実際の結果: 先に全削除し、入力をそのまま一括登録する。トランザクションで全消失は回避されるが、ENUM/FK/ユニーク制約違反は500になる。管理者はフィルターを常にバイパスするため、管理者権限を編集しても実効動作と画面設定が一致しない。
- 期待結果: 固定ロール、存在する一意なメニューIDを事前検証する。管理者設定を不変にするか、バイパスとの関係をUI・APIで明示する。
- 追加すべきテスト: 未知／重複／不存在／管理者変更で、DB不変と適切な400/409を検証する。

## LOGIC-21 [P3] 未存在リソースが `200 + data:null/false` になるAPIが混在する

- 対象モジュール: エンジニア、顧客、案件、契約、候補者等
- 代表メソッド:
  - 各 `getById(...)` の `ApiResult.success(service.getById(id))`
  - 汎用 `updateById/removeById` のBoolean成功ラップ
- 発生条件: 存在しないIDをGET、PUT、DELETEする。
- 実際の結果: 404を返すサービスと、200で `null`／`false` を返すコントローラーが混在する。UIが成功と未存在を区別できず、監査ログも成功扱いになる。
- 期待結果: GET/PUT/DELETEは未存在を一貫して404にする。DELETEの冪等性を採る場合は仕様として統一し、レスポンスを明示する。
- 追加すべきテスト: 全主要リソースの未存在CRUDを契約テストとして共通化する。

## LOGIC-22 [P3] 同時請求生成でユニーク制約違反が500になる

- 対象モジュール: 工数 → 請求生成
- 対象メソッド: `InvoiceServiceImpl.generate(Long, String)`
- 発生条件: 同一顧客・請求月に対して、同じ未請求工数を2トランザクションが同時に取得して請求生成する。
- 実際の結果: 請求番号衝突にはリトライがあるが、請求明細の `work_record_id` 競合は変換していない。後発トランザクションはロールバックされるものの、予期しない500になる。
- 期待結果: 顧客＋月単位の排他、対象工数のロック、または制約違反を409／既存請求への冪等応答へ変換する。
- 追加すべきテスト: 2トランザクション同時生成で請求が1件だけ作成され、後発が409または同一請求を返すことを検証する。

## 横断的な受入基準

1. P1の修正後は、関連モジュールを単独でテストするだけでなく、変更元と参照先を同一テストでDB照合する。
2. DB制約は最後の防御として残し、制約違反を利用者向けの4xx業務エラーへ変換する。
3. `@RequestBody` の永続化エンティティ直接受け取りを段階的に廃止する。
4. 状態、採番、監査列、論理削除、履歴リンクはサービスだけが設定する。
5. 金額・率・年月・ID参照はUI属性だけでなく、DTOとサービスの両方で検証する。
