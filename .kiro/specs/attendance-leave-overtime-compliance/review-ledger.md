# Review Ledger — 雇用勤怠・休暇・時間外労働

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `attendance-leave-overtime-compliance` |
| handbook | `v2.0` |
| state | `REVIEW`（T071/T072/T073 **PASS・完結**（R6-P2-01 VERIFIED_CLOSED、R6-NOTE-01 CLOSED）、T074（M）着手可） |
| base | `5e29f39c96da85b29a0fe881326d979896a595d0` |
| head | T073 R6 fix delta転記 `ef4ce72`＝current merged HEAD（main=origin/main、push済み） |
| merge | T073までのdeltaはmainへmerge済み・push済み。V82永久欠番・S11=V83・S10=V84・V91（方式A）・**V98（休暇残数台帳、発注者割当）**実在。S12〜S17=V99〜V104へ繰り上げ済み（V101はscale-300実在、NOTE-R4-04） |
| latest review | `R11 R6-P2-01 fix delta再Review`（`62e3d31`/`ef4ce72`）。**R6-P2-01 VERIFIED_CLOSED、R6-NOTE-01 CLOSED、T073完結** |
| verdict | **T073完結**（P0=0/P1=0/P2=2/NOTE=4）。R6-P2-01（SQL境界）VERIFIED_CLOSED、R6-NOTE-01（閾値境界）CLOSED |
| issue count | `P0=0 / P1=0 / P2=2（R2-P2-01, R2-P2-02）/ NOTE=4（R3-06 dispatch, R4-03 scale-300, R4-04 scale-300採番, R6-NOTE-02 scheduler通知宛先）` |
| next action | **T074（M. 回帰/法務受入・L4全量）着手可**。L4（mvn test・fresh/legacy MySQL・browser Demo・法務fixture）を実施し、attendance起因FAILゼロを確認。cross-lane NOTE×3は統合担当解消後CI相当L4×1回。S11完了後にS12（staffing）解放。R2-P2-01/02は本taskで再評価 |

本台帳は、T067〜T069のtask実装とその証拠をappend-onlyで管理する。T068はDDL/entity/H2/smoke、T069はcalculator/asOf協定解決/fail-closed入力の実装を含むが、V83のmerge/applyはV82後とする。

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| attendance-leave-overtime-compliance-R2-P2-01 | P2 | tasks T070 mobile 390px、shared-standards §5、handbook §7 | `AttendanceUiContractTest.java:11-30`; `review-ledger.md:122,186` | 390px Demo証拠を確認するとHTML文字列assertのみ | 折返し・操作性・拒否表示を実ブラウザで未確認 | desktop/390pxで入力・状態遷移・二重click・reload・戻る・拒否表示を実測し証跡化 | T070 browser direct Demo（Mの全UI回帰とは分離可） | OPEN（T074/Mで再評価、T071を止めない） | — | — |
| attendance-leave-overtime-compliance-R2-P2-02 | P2 | shared-standards §3「全件取得APIを新設しない」、性能受入 | `AttendanceServiceImpl.java:195-229` | HR/管理者が要員数の多い法人で月次一覧をGET | 全要員＋全日次を1レスポンス/メモリへ展開し、上限・pagingがない | 月次summaryを安全なpagingで取得し、日次detailを必要時に同じscopeで取得 | 0/1/1000/1001要員、31日、scope別page/count/detail | OPEN（T074/Mで再評価、T071を止めない） | — | — |
| attendance-leave-overtime-compliance-NOTE-R3-04 | NOTE | handbook「review-ledger先頭に現行判定・OPEN issue・最新Review Packet」、packetの現行性 | `review-ledger.md` §4 | Round 3転記時に§4最新Review Packetが旧状態（base/headが`cc7c15c`で途切れ、「V83未merge」「T070 COMPLETED_UNREVIEWED」等）のままだった | §4が現行状態と矛盾し、次ReviewのBase/Head照合を誤らせる | §4を現行状態（Head `758649e`、V91実在、V83不変、171/0/0/0、R2-P1-02/R3-P2-01 VERIFIED_CLOSED、T071開始可、NOTE-R3-03引き継ぎ）へ全面更新 | 文書整合（`git diff --check`）、次Reviewのpacket照合 | **FIXED（R11 Round 3フォローアップで検証済み）** | `fc798be` | R11 Round 3フォローアップ |
| attendance-leave-overtime-compliance-NOTE-R3-05 | NOTE | §5 Requirements Traceの現行性 | `review-ledger.md` §5 | T068行「独立Review待ち」、T069行「COMPLETED_UNREVIEWED」、T070行「**FAIL**」、方式A行「FIXED_BY_IMPLEMENTER」が§1/§2/§3の現行判定と矛盾 | trace表が実装済み範囲を未Reviewと誤表示する | §5のverdict列を現行判定（T068/T069/T070 PASS、方式A VERIFIED_CLOSED、unverified列へR2-P2-01等を移行）へ更新 | 文書整合（`git diff --check`）、次Reviewのtrace照合 | **FIXED（R11 Round 3フォローアップで検証済み）** | `fc798be` | R11 Round 3フォローアップ |
| attendance-leave-overtime-compliance-NOTE-R3-06 | NOTE | dispatch統合調整（attendance欠陥ではない） | `FlywayMigrationSmokeTest.java:37-40`（ses user container）、dispatch新V84（`b9b91f9`、trigger/function作成） | dispatch V84 R5 merge後のtreeで`FlywayMigrationSmokeTest` fresh V1→latestが**Error 1419**（binary logging有効かつses userにSUPERなしでtrigger作成失敗）。dispatch自身の`FlywayDispatchComplianceSchemaSmokeTest`はroot userのためPASS | 共有fresh経路が全repoで壊れ、CI相当L4・`mvn test`（Docker有）が失敗する | dispatchレーンが`FlywayMigrationSmokeTest`のcontainerへ`log_bin_trust_function_creators`相当の設定を追加するか、V84のtrigger/functionを回避 | fresh MySQL全経路、`FlywayMigrationSmokeTest`、V84 R5 merge後のCI相当L4 | OPEN（dispatchレーンへ引き継ぎ） | `b9b91f9`（導入元） | dispatch側の修正commit時 |
| attendance-leave-overtime-compliance-NOTE-R4-03 | NOTE | scale-300統合調整（attendance起因ではない。repo L4/CIを破壊中） | `project.detail.desc`キー | scale-300（`0e29c555`）の`detail.html`が参照する`project.detail.desc`キーが4バンドル欠落 | `MessageBundleConsistencyTest`がFAILし、repo L4/CIがRED | `messages*.properties`へ`project.detail.desc`を追加（scale-300/統合担当） | `MessageBundleConsistencyTest`、CI相当L4 | OPEN（統合担当へ引き継ぎ） | `0e29c555`（導入元） | 統合担当の修正commit時 |
| attendance-leave-overtime-compliance-NOTE-R4-04 | NOTE | scale-300採番調整（attendance起因ではない。repo L4/CIを破壊中） | `SpecDispatchConsistencyTest`、予約表 | scale-300が`V101__remove_unimplemented_menu_routes.sql`を実在化し、S13=V100/S14=V101予約と衝突（attendanceのf26da9f0繰上げ後に他laneが採番） | `SpecDispatchConsistencyTest`がFAIL（`reserved <= latest`拒否）し、repo L4/CIがRED | 予約表を再繰上げ（S13/S14以降を最新実在V101以上へ）し、S11〜S17の全派工資料・`SpecDispatchConsistencyTest`を同期 | `SpecDispatchConsistencyTest`、CI相当L4 | OPEN（統合担当へ引き継ぎ） | scale-300採番commit | 統合担当の修正commit時 |
| attendance-leave-overtime-compliance-R5-P2-01 | P2 | tasks.md B1「timezoneはtenant設定（design §3）」 | `AttendanceSyncServiceImpl.java:61,73`（`CONFIG_TIMEZONE`）、`ZoneId` import未使用 | `attendance.sync.timezone`は設定登録のみで**読み取りコードが存在しない**（dead constant）。cursorの`updated_at`は文字列辞書順比較でtimezone semanticsなし | 外部providerがtimezone付きISOを返すとcursor前進・比較がずれる可能性。現行はmock既定・freeeはG4 provisionalのため即時影響なし | timezone設定をcursor正規化（zone付きparse）へ実装するか、javadoc/完了記録の虚偽主張を削除しG4 gateへ明示延期 | timezone正規化のboundary（zoneなし/zone付き/不正値）、cursor再実行 | **FIXED_BY_IMPLEMENTER**（`c750fcb3`: `tenantZone()`+`normalizeUpdatedAt()`でzoneなしをtenant timezone解釈・zone付きはInstant正規化して比較・保存。mapper直読みで同一tx整合） | `c750fcb3` | 独立再Review（R11） |
| attendance-leave-overtime-compliance-R5-P2-02 | P2 | tasks.md B1「外部データはread-onlyの照合に使われる」 | `AttendanceSyncServiceImpl.java:169-176`（pull） | pullはfetch→pulledCount加算→締め済み/承認済み上書き拒否のみで、**非締め月レコードは取得後破棄**（比較・差異の保存/表示なし） | 「照合」の実体が無く外部データを活かせない（安全側の拒否は実装済み） | 照合対象の保存または差異DTO化（T073の範囲と明確に線引きして実装） | 照合の一致/差異/該当なし集計、差異サンプル上限、read-only不変、T073差異表示との線引き | **FIXED（検証済み）**（`c750fcb3`: 外部レコードと本システム日次（manual/system）を比較し`matchedCount`/`diffCount`/`unmatchedCount`＋差異サンプル（最大20件）を`AttendanceSyncResultDto`へ追加。DB登録はしない。status API・管理画面UIで表示。T073（客先工数差異）とは別物として線引き。R5 fix delta再Reviewで実assert確認） | `c750fcb3` | R11 Round 5 fix delta再Review |
| attendance-leave-overtime-compliance-R5-P1-01 | P1 | T070 Objective・design §5.3・R5、platform-invariants §2 | `AttendanceSyncServiceImpl.java:117-155`（syncPull） | HR-Aが法人Bの要員の外部レコードをpull。`rejectExternalUpdate`（:140）が任意法人の締め済み/承認済み月へfinding書込＋通知し、`reconcile`（:145）が他法人要員の日次勤怠PII（出退勤・各分・engineerId・workDate）を結果DTOへ返却 | 他法人要員のPII漏洩と誤finding書込（R4-P1-01と同じ根本原因クラスがpull経路に残存） | syncPullへcaller scope境界（管理者=全件/HR=担当法人/マネージャー=組織scope）を適用し、reject/reconcileを同一境界に統一。scope外・要員未解決レコードはskip | HR A→A処理/A→B skip、finding書込なし・PII非返却、管理者全件、マネージャー組織scope | **VERIFIED_CLOSED**（`1582d96`を独立検証: `pullScopeEngineerIds`（管理者=全件、HR=`allowedHrEngineerIds`月末asOf、マネージャー=組織scope・hasFullAccess先判定、その他403）＋scope外・要員未解決はreject/reconcile前にskip（:123,141-149）。test: HR-Aが自法人締め済み更新のみ拒否（rejectedCount=1）、他法人要員のfinding不書込（findingB=null）、管理者は全要員処理を実assert。read-only・PII/finding漏れは解消） | `1582d96` | R11 R5-P1-01 fix delta再Review |
| attendance-leave-overtime-compliance-R5-P2-03 | P2 | tasks.md B1「締め済み月が外部から上書きされない」「黙って無視もしない」、design §5.4 | `AttendanceSyncServiceImpl.java:133-137,146-149,162-163`（syncPull） | **cursorがscope外レコードでも前進する**。`maxUpdatedAt`（:133-137）はscope skip（:146-149）より前に全レコードで更新され、単一グローバルcursor（`attendance.sync.freee.cursor`）へ保存（:162-163）。多法人DBでHR-Aが先にpullすると、HR-B配下要員のレコード分もcursorが進み、**HR-Bの後続pullでは該当レコードを取得できず、HR-B配下の締め済み月外部更新の拒否・照合が黙ってスキップされる** | 「黙って無視」になる（締め済み拒否の完全性がpull順に依存） | ①cursorをscope内レコードだけで前進、②legal entity別cursor keyへ分割、③pullをグローバル/system principalで実行し結果表示のみscope——のいずれかを決定表・設計で確定して実装 | HR-A→HR-Bの順序でpull、法人別cursor、締め済み拒否の他法人到達性 | **VERIFIED_CLOSED**（`3fce6d78`を独立検証: 法人別cursorキー（`attendance.sync.freee.cursor.le.<id>`、旧グローバルkeyはfallback保持）。`pullScopeLegalEntityIds`（管理者=全法人/HR=担当法人/マネージャー=組織scope要員の法人、hasFullAccess先判定）→ fetchは対象法人cursorの最小値、レコードは`legalEntityIdOf`（月次snapshot→要員組織の法人）で解決しscope外法人はskip・cursor不変、処理済み（法人cursor以前）はskip、処理したレコードの法人だけcursor前進・保存は変化した法人のみ。動的cursor keyはSYSTEM_MANAGED prefix管理。test: HR-A先pull→法人B cursor不変（行不存在）→HR-B後続pullでBレコード再取得（pulledCount=1）・締め済み拒否実行（rejectedCount=1）を実assert） | `3fce6d78` | R11 R5-P2-03 fix delta再Review |
| attendance-leave-overtime-compliance-R6-P2-01 | P2 | platform-invariants §2.2「条件は必ずSQLへ渡す。取得後のJava側filterは禁止」 | `AttendanceDiscrepancyServiceImpl.java`（list()） | 対象月の全`t_attendance_month`をSQLで取得し、scope（HR法人/manager組織）をJava側`stream().filter()`で適用 | 機能的にはscope漏れなし（PII洩れなし）だが、全件fetch＋不変条件違反（性能・一貫性） | `query.in(AttendanceMonth::getEngineerId, scopedEngineerIds)`（空集合はid=-1 sentinel）をSQLへ適用し、pendingWarnings以外を同一境界へ | HR/managerのscope外除外がSELECT自体で行われる（0件・該当1件）、空集合0件 | **FIXED_BY_IMPLEMENTER**（`62e3d31`: list()のscopeをSQL境界へ移行。空集合は`List.of(-1L)` sentinelでDB側0件。pendingWarnings（scheduler相当・全件）は対象外。fixture: HR他法人除外・マネージャー他組織除外を実assert） | `62e3d31` | 独立再Review（R11） |
| attendance-leave-overtime-compliance-R6-NOTE-01 | NOTE | tasks.md B2「閾値の境界」の設計記載 | design.md（閾値境界の向き未記載） | 閾値境界の向き（`\|diff\| >= threshold`＝ちょうど=超過）がtest・完了記録にのみ固定され、design.md決定表に未記録 | traceability欠落（overtime-rules §4.3の前例） | design.mdに境界の向きを追記 | 文書整合（`git diff --check`） | **対応済み**（`62e3d31`: design.md §5.4へ`overThreshold := |雇用勤怠 − 契約工数| >= threshold`（既定480分）、ちょうど=超過、fixture 479=以内/480=超過/481=超過を追記） | `62e3d31` | 次Reviewで確認 |
| attendance-leave-overtime-compliance-R6-NOTE-02 | NOTE | platform-invariants §2.4、T072拒否通知パターン | `NotificationGenerateService.java`（attendanceDiscrepancyWarning） | `pendingWarnings`はscheduler相当（全件・scope非依存）で、法人をまたぐ要員名・差分分を全管理者/HRへ通知 | 通知量・宛先粒度の運用判断（漏洩・越権ではない） | 運用方針としてledger/設計に明記（現行でOK） | 通知のdedupe key冪等・確認済み除外（既存testで実assert済み） | **対応済み**（運用方針をledger §11へ明記: scheduler principal=全件・宛先指定通知に組織条件を重ねない§2.4整合。宛先粒度はrelease gateの運用判断） | — | 運用開始時 |

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|
| attendance-leave-overtime-compliance-R1-P1-01 | VERIFIED_CLOSED | active文書の旧採番/B2 provenance | `3b03a94` | R11再Review: V83、V81/V82/V83、`4488ba8`、B2完了/R11範囲を確認 | R1 fix delta再Review | 新しい予約変更があれば再Review |
| attendance-leave-overtime-compliance-R1-P1-02 | VERIFIED_CLOSED | release gate期限と実装契約の不足 | `3b03a94` | R11再Review: ATT-GATE-05/06、内部正/外部正、UNKNOWN/findingを確認 | R1 fix delta再Review | gate契約変更時 |
| attendance-leave-overtime-compliance-R1-P2-02 | VERIFIED_CLOSED | V5初期形のみ記載 | `3b03a94` | R11再Review: V5 `DECIMAL(5,1)`/V39 `DECIMAL(6,2)`を確認 | R1 fix delta再Review | 現行migration変更時 |
| attendance-leave-overtime-compliance-R1-P2-03 | VERIFIED_CLOSED | L0 commandの再現情報不足 | `2299fbc` | R11再Review: script実行1/0/0/0、exit 0を確認 | R1 fix delta再Review | script契約変更時 |
| attendance-leave-overtime-compliance-R1-P2-01 | VERIFIED_CLOSED | code/evidence Headとmerged/apply状態の混同 | `9af7071` | fix base=`4789c19`、code=`1fb54d4`、HEAD=origin/main=`9af7071`をGitで確認。V83 tree内/V82不在と適用証拠未取得も分離記録 | R2 fix delta再Review | Packetと実GitのBase/Head/mergeが再度不一致になった場合 |
| attendance-leave-overtime-compliance-R2-P1-05 | VERIFIED_CLOSED | reopenを直接状態遷移として実装しapproval境界が無かった | `b91dc99`ほか | 理由DTO/API、申請時CLOSED維持、approval adapterのstatus/version CASを読解。指定29件と共通approval engine 45件が各skip 0でPASSし、申請者除外・route fail-closed・競合・監査契約を確認 | R2 fix delta再Review | `attendance.reopen` adapter/approval engine/route契約変更時 |
| attendance-leave-overtime-compliance-R2-P1-04 | VERIFIED_CLOSED | manager full-access/asOfと履歴ありNULL fallback | `34654f2`,`43c5a3e`,`e3fe948` | `hasFullAccess()`先判定、対象月末asOf、`CASE WHEN eh.id IS NULL THEN fallback ELSE eh.organization_id END`を読解。current Headの14 class 78/0/0/0、skip 0でKNOWN A/B・KNOWN NULL・空集合を確認。UNKNOWNは同CASEでNULL | R2 fix follow-up再Review | OrganizationScope/EngineerAccountLinkMapper/asOf consumer変更時 |
| attendance-leave-overtime-compliance-R2-P1-03 | VERIFIED_CLOSED | HR認可が対象月末engineer所属を使いmonth法人snapshotをconsumerにしていなかった | `43c5a3e`,`430296e`,`0406a4f` | HR担当legal entityをserver-side解決し、month SQL・表示名・day detailをsnapshot選択IDへ統一。closeはlock後snapshot法人を検証。`AttendanceMonthSnapshotScopeTest`を含むcurrent 15 class 79/0/0/0、skip 0でB HR 0件/拒否、A HR list/close成功を確認 | R2 snapshot consumer再Review | AttendanceMonth snapshot、HR legal entity resolver、management/close consumer変更時 |
| attendance-leave-overtime-compliance-R2-P1-01 | VERIFIED_CLOSED | V83実在後もS10をV82予約のまま扱い、将来V82補填を前提にしていた | `55c39cd`,`08eb098`,`23e48e0`,`b75af1a` | V82を永久欠番、S11=V83、S10=V84、S12〜S17=V85〜V90と正式決定。local-default/CI/Testcontainers/GitHub environment inventoryを確認し、reviewer再実行でMySQL V83 success/V82 absent/latest=83と予約fixtureを10/0/0/0、skip 0で確認 | R2 migration decision再Review | V82補填、V83編集、予約表または新environment inventory追加時 |
| attendance-leave-overtime-compliance-NOTE-R3-03 | RESOLVED | V1（`5f362fc`）へdispatch R5テーブルが混入したが、attendance欠陥ではない | `b9b91f9`（dispatch整合commit） | dispatch `b9b91f9`はV1を変更せず（V1は`5f362fc`経由で既にR5 shape）、V84 R5を本V1と整合する形でcommitし、R11が解消を確認（commit message「content landed via parallel S11 commit 5f362fc; verified identical at HEAD」） | R11 Round 3フォローアップ転記確認 | V84/V1のshapeを再度分岐させる変更時 |
| attendance-leave-overtime-compliance-R2-P1-02 | VERIFIED_CLOSED | 休憩総分を退勤直前へ配賦し深夜・時間外が休憩位置で誤る（方式A未実装） | `5f362fc`（V91追補）＋`b65996f` | Round 3独立再Review: V91 DDL（UNIQUE(attendance_id,sequence_no)、CHECK offset、FK CASCADE）＋V1統合＋H2 2形状＋MySQL smoke。`AttendanceCalculator.validateBreaks`（重複/区間外/開始≧終了/全体超過を1箇所でfail-closed、補間なし）＋intersection算定を読解。再現case（21:00〜23:00、休憩21:00〜22:00）はworked=60/lateNight=60をservice・calculator両testで実assert。区間不明行は再保存・月次再集計とも400拒否。isolated worktree `b65996f`で171/0/0/0 skip 0、MySQL smoke 2/0/0/0、fresh全経路2/0/0/0 | R11 Round 3 独立再Review | calculator/休憩区間model/`t_employee_attendance_break`/V91のいずれかを変更した場合 |
| attendance-leave-overtime-compliance-R3-P2-01 | VERIFIED_CLOSED | breakMinutes行が「無視または不一致として拒否」の2択を1セルに併記していた | `5f362fc` | Round 3独立再Review: design §5.1.1を「不一致は400拒否」へ1択確定し、`assertBreakMinutesMatch`＋`error.attendance.breakMinutesMismatch`で実装・test済み。区間なし`breakMinutes>0`も同一400で拒否を確認 | R11 Round 3 独立再Review | breakMinutes契約または決定表の該当行を変更した場合 |
| attendance-leave-overtime-compliance-R4-P1-01 | VERIFIED_CLOSED | grant/balanceが法人・組織scopeなしで任意engineerを許可するIDOR | `85ca62ba` | R11 Round 4 fix delta再Review（独立検証）: `grant`はHR=`allowedHrLegalEntityIds`×対象日snapshot法人の一致のみ（不一致404）、管理者全件、`legal_entity_id`をsnapshotから設定。`balance`は管理者全件/HR=担当法人（asOf今日）/マネージャー=組織scope（`hasFullAccess`先判定＋asOf）、要員不存在・所属不明・履歴ありNULLは404 fail-closed。HR他法人付与404＋担当内許可、HR/manager他法人残数閲覧404＋担当内許可を実assert。leave 4 class 24/0/0/0 skip 0 | R11 Round 4 fix delta再Review（独立） | grant/balanceのscope契約またはAttendanceScopeResolver/asOf consumer変更時 |
| attendance-leave-overtime-compliance-R4-P2-01 | VERIFIED_CLOSED | 差戻し後に再提出できないデッドエンド | `85ca62ba` | R11 Round 4 fix delta再Review（独立検証）: `resubmit`（本人のみ・approval status=returned時のみ）＋`POST /api/my/leave/{id}/resubmit`＋UI再提出ボタン＋4言語key。engineへpayload/diff委譲をtestでverify | R11 Round 4 fix delta再Review（独立） | resubmit契約またはapproval engineのroute再解決/CAS変更時 |
| attendance-leave-overtime-compliance-NOTE-R4-02 | FIXED | 外部正モード（`leave.balance.source`≠internal）でもgrantが可能だった | `85ca62ba` | R11 Round 4 fix delta再Review: `leave.balance.source`≠internalではgrantを400拒否（`error.leave.grantExternalDisabled`）。未設定（判定不能）もfail-closed。外部モード付与拒否を実assert | R11 Round 4 fix delta再Review（独立） | leave.balance.source契約変更時 |

## 4. 最新Review Packet

```text
- handbook version: v2.0
- spec/tasks: attendance-leave-overtime-compliance / T067〜T071完了（T072〜T074未着手）
- base/head/merge status: original `5e29f39` → ... → T071休暇実装`7981e5c` → T071完了記録`6d99658a` → R4 fix delta`85ca62ba`（＝current merged HEAD=origin/main、push済み）。R4 fix delta再Review確認（`85ca62ba..HEAD`）はattendance変更ゼロ・新規指摘なしで**PASS維持**
- changed files by task: T067成果文書/台帳、T068のV1/V83、H2 replay、engineer-schema-h2、application-test.yml、7 entity/mapper、MySQL smoke、migration consistency test、T069のcalculator/協定asOf resolver/UNKNOWN finding/定向test、T070の本人/管理API・service・DTO・画面・JS・SecurityConfig・sidebar・4言語i18n・定向test、R2-P1-02方式AのV91追補/calculator区間intersection/不一致400/区間不明fail-closed/UI/i18n/境界回帰、T071のV98（t_leave_ledger）/LeaveService/LeaveApprovalAdapter/休暇画面/権限seed/i18n、R4 fix deltaのgrant/balance scope・resubmit・外部モード付与拒否、tasks/design/source matrix、本台帳、中央台帳、予約表V99〜V104同期（S12〜S17）
- requirements/AC trace: 最重要境界、R1.1〜R1.4、R2.1〜R2.3、R3.1/R3.2/R3.4、R4.2、R5、T070のR1.1/R1.2/R1.3/R1.4/R3.3/R5、R2-P1-02方式AのR1.1/R1.2/R3.1/R5とdesign §5.1.1、T071のR2.1/R2.2/R2.3とdesign §5.3/§5.4
- migration state: V83不変（checksum維持）・V82永久欠番・V91実在（S11方式A追補）・V98実在（S11休暇残数台帳、発注者割当）・V84実在（dispatch）・V101実在（scale-300、S14予約と衝突中）。S12〜S17=V99〜V104予約（NOTE-R4-04で統合担当が再繰上げ予定）。本番適用なし
- test evidence: R11 Round 4 fix delta独立検証: leave 4 class = **24/0/0/0**（LeaveServiceTest 13＋Adapter 5＋Flow 2＋API 4）、attendance/approval/overtime/integrity含む22 class全PASS、skip 0。主担当実装時の全指定回帰195/0/0/0 skip 0、MySQL smoke 4/0/0/0（V83/V91/V98）
- Demo evidence: R4 fix delta境界（HR他法人付与404・担当内許可、HR/manager他法人残数404・担当内許可、外部モード付与拒否、差戻し→再提出）を定向実測。実ブラウザ（390px）は未実施
- skipped/unverified: T072〜T074、L4全量、実ブラウザ（390px）、paging（R2-P2-02）、dispatch V84修正後のfresh全経路（NOTE-R3-06）、scale-300起因のMessageBundleConsistencyTest（NOTE-R4-03）とSpecDispatchConsistencyTest（NOTE-R4-04）、法人一覧・36協定書・就業規則・法定休日曜日・勤務区分・休暇残数の正・適用除外者・HR法人の実資料突合（ATT-GATE-01〜06）
- known issue IDs: R2-P2-01、R2-P2-02（OPEN、T072を止めない）、NOTE-R3-06（dispatch、統合担当）、NOTE-R4-03（`project.detail.desc`欠落、scale-300、統合担当）、NOTE-R4-04（V101予約衝突、scale-300採番、統合担当）。R4-P1-01/R4-P2-01はVERIFIED_CLOSED、NOTE-R4-02はFIXED、NOTE-R4-01は記録済み
- out-of-scope: provider sync（T072）、差異通知（T073）、warning通知scheduler、M/L4（T074）
- rollback: 本番未適用。`85ca62ba`以前の各実装commitを逆順revertすれば本deltaを戻せる。V83/V91/V98は新規テーブル・列のみ追加、既存migration編集なし
- requested verdict: T071までの実装範囲 **PASS**（R11 Round 4 fix delta再Review確認、R4-P1-01/R4-P2-01 VERIFIED_CLOSED、NOTE-R4-02 FIXED、T072開始可）
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 最重要境界 / R1.3 / R4.2 | 雇用勤怠、客先請求工数、freeeの責任境界を混同しない | source matrix §1 | L0文書整合 | HR提示資料で境界を説明 | HR承認未実施 | 中間 |
| R1.2 / R3.2 | カレンダー・法人別協定へ未確認値を推測投入しない | source matrix §3/§5/§9 | L0未確認明記 | 法定休日曜日・協定一覧テンプレートを提示 | 就業規則・協定書未入手 | 中間 |
| R2.1 / R2.2 | 休暇種別と残数の正を後続実装で取り違えない | source matrix §4 | L0種別/正の確認状況 | 休暇種別一覧をHRへ提示 | 種別ごとの正未確認 | 中間 |
| R3.2 / R3.4 | 適用除外者を役職名の推測で誤判定しない | source matrix §6 | L0対象者未確認を明記 | 管理監督者一覧テンプレートを提示 | HR個別確認未実施 | 中間 |
| R5 | 確定値未入手でも判定不能を適合と誤認しない | findings F-1〜F-6、§10 | L0 | fail-closed/release gate区分を提示 | release gate未達 | 中間 |
| R1.1/R1.2/R1.3 | 雇用勤怠を分単位で記録し、calendar/source/NULL・0/外部冪等をDDLで固定する | V1/V83、`m_work_calendar*`、`t_employee_attendance`、`t_attendance_month` | `AttendanceSchemaTest`、MySQL smoke | calendar日を投入しNULL/0とsource重複拒否を確認 | 締め済み更新拒否はT070でservice確認、V83適用は本番gate | T068 PASS（R2-P1-01 VERIFIED_CLOSED、独立Review済み） |
| R2.1/R2.2 | 休暇申請の期間・分・approval参照列を持ち、残数正本未確認を後続でfail-closed扱いできる | `t_leave_request`、残数ledgerは正本確定後の条件付き | schema replay、migration integrity | 休暇DDLの列・期間CHECKを確認 | 外部正/内部正の業務挙動はT071 | T068 PASS（DDL部分独立Review済み） |
| R3.2/R3.4 | 法人別協定、月初起算、適用除外者UNKNOWN、follow-upを保持する | `m_overtime_agreement`、`t_overtime_followup`、`t_engineer.overtime_exempt_flag(NULL=未確認)` | 月初CHECK、config 9 key、MySQL smoke | invalid `valid_from`拒否とconfig seedを確認 | calculator UNKNOWN/findingはT069で確認済み、法人資料はATT-GATE-02/03/06 | T068 PASS（DDL部分独立Review済み） |
| R3.1/R3.2/R3.4/R5 | 6ルールの境界と法人/asOf協定を正しく判定し、正本・適用区分・必須履歴の不足を適合にしない | `OvertimeComplianceCalculator`、`OvertimeAgreementResolver`、`OvertimeAgreementSnapshot.from`、`OvertimeRule`のUNKNOWN finding | 公式fixture 27、resolver integration 3、DDL/dispatch直接回帰を含む70/0/0/0。R11 Round 3独立再Reviewに含まれ171/0/0/0 | T069-D1で45h/360h/80h境界、月100hの`>=`、法人別上限優先、協定なし/適用除外不明/履歴不足を確認 | 法人別協定・法定休日・適用除外者資料はATT-GATE-02/03/06で未確認 | T069 PASS（独立Review済み） |
| R1.1/R1.2/R1.3/R1.4/R3.3/R5 | 本人入力、月次提出、上長差戻し/承認、HR締めを同一状態CAS・scope・CSRF境界で扱う | `AttendanceServiceImpl`、本人/管理API、DTO、画面/JS、SecurityConfig/sidebar、4言語message | reviewer再実行17/0/0/0＋R2 fix delta 79/0/0/0。R11 Round 3独立再Reviewに含まれ171/0/0/0 | 逐次状態遷移・営業403・CSRF・markup静的確認。実ブラウザ（390px）未実施 | R2-P2-01（390px実ブラウザ） | T070 PASS（R2-P1-02〜05、R3-P2-01 VERIFIED_CLOSED） |
| R2-P1-02方式A fix delta（R1.1/R1.2/R3.1/R5、design §5.1.1） | 休憩区間を保存し実労働区間で深夜・時間外を算定し、区間不明・不一致をfail-closedで拒否する | V91追補（`t_employee_attendance_break`）、V1統合、H2 2形状、entity/mapper、calculator区間intersection、`assertBreakMinutesMatch`、区間不明fail-closed、UI/i18n 4言語 | 主担当指定回帰135/0/0/0＋MySQL 3/0/0/0。R11 Round 3独立再Review: **171/0/0/0 skip 0**、MySQL smoke 2/0/0/0、fresh全経路 2/0/0/0 | 深夜前/中/後、跨夜、複数休憩、0分、全時間休憩、重複、区間外、開始≧終了、8h/週40h/22時、月次再集計、区間不明行、不一致400拒否を定向実測 | 実ブラウザ、paging、L4 | **VERIFIED_CLOSED**（R11 Round 3独立再Review） |

## 6. 横断契約

### 6.1 Scope consumer inventory

| consumer | endpoint/job | population source | DataScope | organization | tenant | empty-set | test |
|---|---|---|---|---|---|---|---|
| T067文書 | なし（文書のみ） | 既存migration/specの棚卸し | N/A | N/A | 独立DB前提 | N/A | L0 |
| 後続attendance | 勤怠・休暇・warning・通知・scheduler | design.md §5.3の決定表 | 本spec決定表 | HR法人 / manager組織∩DataScope / 本人自己のみ | 独立DB | 後続taskでSQL境界へ適用 | T068以降 |
| T070 attendance | `/api/my/attendance*`、`/api/work-records/attendance*`、画面/API/通知の母集団 | 本人は`EngineerAccountLinkService`、managerは対象月末asOfの`OrganizationScopeService`、HR/管理者はテナント内要員 | managerは既存DataScopeの組織scope規則と同じID母集団、営業は勤怠対象外 | 本人自己のみ / manager配下 / HR法人（現行テナントDB境界） | tenant filterは既存DB境界、clientの`engineerId`/`legalEntityId`を本人操作で受けない | manager空集合は0件、本人未紐付けは403、未知scopeは404 | `AttendanceApiControllerTest`、`AttendanceWorkflowServiceTest` |

### 6.2 Temporal/NULL matrix

| field/concept | current | history | snapshot | explicit NULL | missing history | asOf rule | boundary test |
|---|---|---|---|---|---|---|---|
| 勤務カレンダー/法定休日 | 未確認のため未確定 | F1でvalid_from/to | 月次確定時固定 | 所定なしと0分を区別 | 協定/規程なしはfinding | 勤務日時点の有効版 | F1/F2 |
| 36協定 | 法人別資料未入手 | F1でvalid_from/to | 判定結果をfollowupへ | 協定行なしは協定未締結・判定不能 | 既定値で適合にしない | 対象月初asOf | T069 |
| 休暇残数 | 正本未確認 | F1/A2で台帳または外部参照 | N/A | 外部正は参照のみ | 不明でも外部正モードは申請拒否しない | 申請日時点 | A2 |

### 6.3 Transaction/cache matrix

| mutation | CAS/UNIQUE | transaction | cache event | commit | rollback | concurrent test |
|---|---|---|---|---|---|---|
| T067文書 | N/A | N/A | N/A | 文書commit後 | git revert可能 | N/A |
| 後続勤怠/同期/締め | design.md §5.4の状態CAS、外部source unique | 後続taskで定義 | 後続taskで定義 | 締め・承認後に外部呼出し | 締め済み外部更新はfinding | T068〜T073 |
| T070日次/月次 | 月次`version`と状態の条件付きUPDATE、manual日次はsource固定 | `@Transactional`でmonth lock→daily write→aggregate、状態遷移を同一service tx | UI/認可cacheと別境界、scopeの新規キャッシュは追加しない | 成功はcommit、CAS不一致/締め済みはBusinessExceptionでrollback | 締め済み月は編集拒否、external syncは後続B1 | `AttendanceWorkflowServiceTest` |

### 6.4 Migration matrix

| shape | source version | command/test | assertions | result | commit |
|---|---|---|---|---|---|
| fresh | V1 → local V83 | `FlywayAttendanceSchemaSmokeTest` | tables/FK/index/CHECK/config/source/NULL・0 | PASS（1/0/0/0、ephemeral MySQL） | `b327b1b` |
| legacy | published V81 → local V83 | `FlywayAttendanceSchemaSmokeTest`相当のtarget 83実行 | t_engineer列追加、V83 shape | PASS（V82未適用の一時containerのみ） | `b327b1b` |
| H2 replay/entity | V1 + `schema-attendance-h2.sql` + `engineer-schema-h2.sql` | `AttendanceSchemaTest` | 7 tables/entity CRUD/unique/month-start/NULL・0/config | PASS（5/0/0/0） | `b327b1b` |
| partial/backfill/repair | V83 | T074/Mへ繰越 | V82先行merge、既存DBrepair、rollback | 未実施 | — |

## 7. Test Evidence

| level | command | environment | tests | failures | errors | skipped | exit | commit | executor |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| L0 | PowerShell inline文書整合チェック、`git diff --check` | Windows PowerShell / worktree | 1 | 0 | 0 | 0 | 0 | `93c1ac6` | 主担当 |
| L1〜L3 | `mvn -Dtest=AttendanceSchemaTest,MigrationScriptIntegrityTest,SpecDispatchConsistencyTest test` | Windows/H2 | 40 | 0 | 0 | 0 | 0 | `b327b1b` | 主担当 |
| L1〜L3 | `mvn -Dtest=FlywayAttendanceSchemaSmokeTest test` | Docker/MySQL 8.0 ephemeral | 1 | 0 | 0 | 0 | 0 | `b327b1b` | 主担当 |
| L1〜L3 | `mvn -Dtest=OvertimeComplianceCalculatorTest,OvertimeAgreementResolverTest,AttendanceSchemaTest,MigrationScriptIntegrityTest,SpecDispatchConsistencyTest test` | Windows/H2 | 70 | 0 | 0 | 0 | 0 | `d395797` | 主担当 |
| L1〜L3 | `mvn -Dtest=AttendanceApiControllerTest,AttendanceWorkflowServiceTest,AttendanceUiContractTest,MessageBundleConsistencyTest,JsSyntaxCheckTest,RoleNavigationVisibilityTest test` | Windows/H2 + Node | 17 | 0 | 0 | 0 | 0 | `cc7c15c` | 主担当 |

T067 L0にskipはない。T068はH2/MavenとDocker/MySQL smokeを実行済み。browser/UI、締めservice、Node、L4全量は担当範囲外またはT070/T074/Mへ繰り越す。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|
| T067-D1 | HR/発注者、現行DB・spec資料 | 文書Demo | source matrix、法人別36協定一覧、法定休日、勤務区分、休暇種別、適用除外者一覧を提示 | 本システム正の境界と未確認項目が明示され、推測値がない | 資料は提示可能。HR/法人資料の受領と承認は未実施 | `source-matrix-and-agreement-inventory.md` §1〜§8 | CONDITIONAL（本番release gate） |
| T068-D1 | 開発/Review担当 | H2 + MySQL 8.0 ephemeral | calendar日へNULL/0を登録、freee source重複、月初外協定、config全keyを検証 | NULL/0を区別、重複/月初外を拒否、9 key seed | 1週間の画面Demo、締め済み拒否、`/system-config`表示編集はT070/M | `AttendanceSchemaTest`、`FlywayAttendanceSchemaSmokeTest` | PASS（DDL Demo） |
| T069-D1 | HR/Review担当 | Windows/H2、calculator fixture | 45h/360h/80hの境界、月100hのみ`>=`、休日労働入力の分岐、協定asOf/法人別優先、協定なし/適用区分不明/履歴不足を実行 | `VIOLATION`と`INDETERMINATE`が仕様どおりで、既定値による適合がない | warning通知・follow-up永続化・UIは後続task | `OvertimeComplianceCalculatorTest`、`OvertimeAgreementResolverTest` | PASS（calculator Demo） |
| T070-D1 | 要員/管理者/営業のrole fixture | Windows/H2、390px markup契約 | 本人入力→提出→差戻し→再提出→承認→締め、締め後編集、営業の画面/API、CSRF、4言語message、JS構文を確認 | state CASとscope/role/CSRF境界が一致し、営業は403、締め後編集は拒否 | 実ブラウザ幅、HR法人scope、manager asOf/full-access、法定時間区分、理由付き再open | reviewer L2 17/0/0/0。`AttendanceUiContractTest`は文字列assertのみ | **FAIL / UNVERIFIED** |

## 9. Release Gate Register

| gate ID | 未確認事項 | owner | 合格条件 | 未確認時の実装挙動 | 影響 | 期限/実施時点 |
|---|---|---|---|---|---|---|
| ATT-GATE-01 | 法人の実数・名称 | 発注者/HR | 法人一覧を確定 | 法人不明は判定不能finding。法人別行を推測seedしない | V83法人別行 | F1 seed/本番締め前 |
| ATT-GATE-02 | 法人別36協定書・特別条項・上限・起算月 | HR/各法人 | 協定書を確認し`m_overtime_agreement`へ登録 | 協定行なしは判定不能finding。既定値で適合にしない | calculator判定 | 本番締め/release前 |
| ATT-GATE-03 | 法定休日・所定休日の曜日 | HR | 就業規則とcalendarを突合 | 休日区分不明は判定不能finding。休日労働の算入を推測しない | 休日労働算入 | 本番締め/release前 |
| ATT-GATE-04 | 勤務区分の実運用 | HR | 就業規則とwork_typeを確定 | 未確認の勤務区分を既定値へ寄せず、判定不能として扱う | F1 model | 本番締め/release前 |
| ATT-GATE-05 | 休暇残数の正 | HR | 種別・法人ごとに本システム正/外部正を確定 | **内部正**は台帳/CASで不足を拒否し、**外部正**は参照のみで不足でも申請を拒否せず、正本未確認は判定不能findingとして記録する | A2申請可否 | **本番締め/release前** |
| ATT-GATE-06 | 管理監督者・適用除外者 | HR | 個別対象者を確定し構造化フラグへ反映 | 対象者不明は適用除外と推測せず、判定不能finding。法定上限の適合確定をしない | F2非判定 | **本番締め/release前** |

上記はT067/T068以降の開発着手条件でも、T074/Mの自己PASS条件でもない。A2は内部正/外部正の両モードを実装し、
F2は協定行・休日区分・適用除外者・履歴が不足する場合にUNKNOWN/判定不能findingを出す。未確認時は本番締め・
適合確定・自動通知をfail-closedにし、開発自体は継続する。

## 10. T067完了証跡（1行/Task）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T067 | 最重要境界、R1.2/R1.3、R2.1/R2.2、R3.2/R3.4、R4.2、R5 | `source-matrix-and-agreement-inventory.md`、本台帳、`tasks.md`、中央台帳 | L0 PASS、1/0/0/0、`git diff --check` exit 0 | T067-D1、資料提示可能。HR確認はrelease gate | `93c1ac6`（現台帳同期は後続の文書provenance commit） | 法人/協定/就業規則/適用除外者未確認。既定値で適合にせず判定不能として管理 |
| T068 | R1.1〜R1.4、R2.1/R2.2、R3.2/R3.4、R5 | V1/V83、H2 2形状、application-test.yml、7 entity/mapper、定向test/MySQL smoke、tasks/design/source matrix、中央台帳 | H2 5/0/0/0、integrity+dispatch 35/0/0/0、MySQL 1/0/0/0、`git diff --check` PASS | T068-D1。DDL境界を実測。締め済み拒否/UIはT070/Mへ | `b327b1b` | V82先行merge、HR gate未達、service-level締め拒否、休暇正本未確定。適用除外flagはNULL=未確認 |
| T069 | R3.1/R3.2/R3.4/R5 | calculator、asOf協定resolver、snapshot変換、nullable適用区分、履歴不足finding、境界fixture、定向test | calculator/resolver/DDL/dispatch `70/0/0/0`、`git diff --check` PASS | T069-D1。45h/360h/80h、月100h、法人別優先、UNKNOWNを実測 | 法人別協定・休日区分・適用除外者はrelease gate、warning通知/UIは後続 | `d395797` |
| T070 | R1.1/R1.2/R1.3/R1.4/R3.3/R5 | 本人/管理API、`AttendanceServiceImpl`、DTO、画面/JS、SecurityConfig/sidebar、4言語i18n、定向test | `AttendanceApiControllerTest` 7/0/0/0、`AttendanceWorkflowServiceTest` 1/0/0/0、UI 2/0/0/0、message 4/0/0/0、JS 1/0/0/0、navigation 2/0/0/0、合計17/0/0/0、`git diff --check` PASS | T070-D1。本人入力→提出→差戻し→再提出→承認→締め、締め後編集拒否、営業403、CSRF、390px markup | HR法人の実資料突合、実ブラウザDemo、V83適用はATT-GATE/V82後 | `cc7c15c` |
| R2-P1-02方式A fix delta | R1.1/R1.2/R3.1/R5、design §5.1.1（R3-P2-01の1択確定含む） | V91追補、V1統合、H2 2形状、entity/mapper、calculator区間intersection、service不一致400/区間不明fail-closed、UI、i18n 4言語、境界回帰、予約表V92〜V97同期 | current HEAD指定回帰**135/0/0/0 skip 0**＋migration整合36/0/0/0、`git diff --check` PASS | 方式A境界（深夜前/中/後、跨夜、複数休憩、0分、全時間、重複、区間外、開始≧終了、8h/週40h/22時）、区間不明行、不一致400を実測 | 独立VERIFIED_CLOSED、実ブラウザ、paging、L4、MySQL fresh/legacy適用 | 本delta commit |
| T071 休暇/approval統合 | R2.1/R2.2/R2.3、design §5.3/§5.4 | `t_leave_ledger`（V98）、`LeaveService`、`LeaveApprovalAdapter`（leave.request/cancel）、残数両モード、分計算（calendar所定分）、期間重複/締め済み月拒否、営業通知分岐、menu/権限seed、本人/管理画面、i18n 4言語 | 休暇系4 class **20/0/0/0 skip 0**、全指定回帰**191/0/0/0 skip 0**、MySQL smoke 4/0/0/0（V83/V91/V98）、`git diff --check` PASS | 申請→承認→calendar反映（月次leave_minutes）、代理承認、外部正モードの不足許容、営業通知分岐を定向実測 | 独立Review、実ブラウザ、L4、dispatch V84修正後のfresh全経路 | 本delta commit |
| T071 R4 fix delta（R4-P1-01/R4-P2-01/NOTE-R4-02） | R2.2（残数scope）、R5（権限外閲覧拒否）、design §5.3/§5.4 | grant/balanceの主体別scope（HR=法人・manager=組織・管理者全件・404 fail-closed）、`resubmit`（returned限定・engine委譲）、外部モード付与400拒否、UI/i18n 4言語 | 休暇系4 class **24/0/0/0 skip 0**（新規: HR他法人付与404・HR/manager残数scope・外部モード付与拒否・差戻し再提出）、全指定回帰**195/0/0/0 skip 0、BUILD SUCCESS**、`git diff --check` PASS | R4-P1-01/R4-P2-01を独立VERIFIED_CLOSED、NOTE-R4-02をFIXED（R11 Round 4 fix delta再Review、独立22 class全green、skip 0） | 実ブラウザ、L4、paging、cross-lane NOTE×3 | `85ca62ba` |
| T072 B1 freee/provider sync | R1.3、R5、G6、G4、design §3/§5.4、platform-invariants §7 | `AttendanceProvider`＋`MockAttendanceProvider`＋`FreeeAttendanceProvider`（`FreeeIntegrationService.apiGet/apiPost`共通基盤: 401 refresh 1回/429 backoff/timeout 503/4xx retryなし/冪等キー・相関ID/秘密非ログ）、`AttendanceSyncService`（冪等push・法人別cursor差分pull・締め済み/承認済み月拒否→`t_overtime_followup` warning_code='EXT_OVERWRITE_REJECTED' UPSERT＋HR/管理者通知・read-only照合・timezone tenant設定）、run/status/export-csv API、管理画面同期カード＋JS、i18n 4言語、SystemConfig SCHEMAS/SYSTEM_MANAGED_KEYS | 新規5 class **29/0/0/0 skip 0**、指定回帰 **165/0/0/0 skip 0**、`git diff --check` PASS。R5-P1-01（scope）・R5-P2-01/02/03（timezone/照合/法人別cursor）で独立VERIFIED_CLOSED/FIXED | sandbox（mock provider）sync→再実行で外部1件（重複送信skip）、締め済み月への外部更新を流して拒否+finding（t_overtime_followup行）を実測。CSV出力（BOM付きUTF-8）も実測 | 独立Review、実ブラウザ、L4、ATT-GATE-01〜06 | `840539da` ほか |
| T073 B2 客先工数差異/通知 | R4.1、R4.2、design §5.4 | `AttendanceDiscrepancyService`（read-only比較DTO: 雇用勤怠`worked_minutes` vs 契約工数`actual_hours×60`、閾値`attendance.discrepancy.threshold-minutes`既定480分、理由確認は`m_system_config` JSON、scope設計§5.3、`pendingWarnings` scheduler principal相当）、API list/confirm、管理画面差異カード＋JS、`NotificationGenerateService.attendanceDiscrepancyWarning()`（管理者/HRへdedupe key冪等通知）、i18n 4言語 | 新規2 class **13/0/0/0 skip 0**（Service 8・API 5）、指定回帰 **232/0/0/0 skip 0**、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当） | 8h差異（1200分）を確認して理由保存、保存前後のbilling_amount・actual_hoursがSQLで同一であることを`AttendanceDiscrepancyServiceTest`で実測（R4.2） | 独立Review、実ブラウザ、L4、ATT-GATE-01〜06 | `5c34db26` |

## 11. Round履歴

### Round 0 — 2026-08-09 — 主担当中間記録

- base/head: `5e29f39` / `93c1ac6`
- scope: T067のみ、文書整合と開始条件
- reviewed issue IDs: なし
- new issue IDs: なし。release gateはATT-GATE-01〜06として別管理
- independently executed tests: L0文書整合チェック、`git diff --check`（PASS）
- verdict: T067完了（独立Review待ち。release gateは未達のまま管理）
- ledger/central synchronization: tasks.md・中央台帳・本台帳はT067完了記録へ更新済み。現行台帳のprovenance同期commitは`git log -1 -- <path>`で解決する

### T068 completion — 2026-08-09 — 主担当実装記録

- base/head: `be2fb19` current merged baseline（`8edcaa6`を含む） → `b327b1b` local T068 implementation
- scope: V1統合baseline、V83増分（未merge）、H2 replay 2形状、entity/mapper、MySQL smoke、migration consistency、T068のみ。T069以降は未着手
- implementation: 分整数モデル、calendar/日次/月次/休暇/36協定/follow-up、`(source, source_external_id)` UNIQUE、scheduled NULL/0、valid_from月初CHECK、`overtime.*` 9 key、`overtime_exempt_flag=NULL` fail-closed形状
- independently executed tests: `AttendanceSchemaTest` 5/0/0/0、`MigrationScriptIntegrityTest` 27/0/0/0、`SpecDispatchConsistencyTest` 8/0/0/0、`FlywayAttendanceSchemaSmokeTest` 1/0/0/0、`git diff --check` PASS
- Demo: T068-D1 PASS。UI `/system-config`、締め済み更新拒否、1週間画面操作はT070/Mへ繰越
- verdict: `COMPLETED_UNREVIEWED`。R11 P2-01は独立再Review待ち。V83はV82先行merge/apply後にのみ配備可能
- ledger/central synchronization: `tasks.md` F1のみ`[x]`、中央台帳をT068完了/未mergeへ同期。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決する

### T069 completion — 2026-08-09 — 主担当実装記録

- base/head: local T068 `b327b1b`（Packet/current merged baselineは`be2fb19`） → T069 code `d395797`
- scope: `OvertimeComplianceCalculator`の6ルール、`m_overtime_agreement`の法人/asOf resolver、snapshot変換、適用除外NULL、必須履歴不足の判定不能finding、calculator定向test。V83のmerge/apply・UI/API・通知schedulerは未実施
- implementation: 閾値はagreement snapshot→`m_system_config`→定数、月100時間のみ`>=`、休日労働の入力選択は月次値の境界に限定、協定なし・適用区分不明・必須履歴不足は`INDETERMINATE`、rolling n不足は仕様どおりskip
- independently executed tests: `OvertimeComplianceCalculatorTest` 27/0/0/0、`OvertimeAgreementResolverTest` 3/0/0/0、`AttendanceSchemaTest` 5/0/0/0、`MigrationScriptIntegrityTest` 27/0/0/0、`SpecDispatchConsistencyTest` 8/0/0/0。指定回帰合計70/0/0/0、`git diff --check` PASS
- Demo: T069-D1 PASS。法人別上限がconfigより優先されること、target month asOf、協定なし/適用区分NULL/履歴不足のfindingを実測。warning通知・follow-up永続化・UIは後続task
- verdict: `COMPLETED_UNREVIEWED`。R11 P2-01は独立再Review待ち。V83はV82先行merge/apply後にのみ配備可能
- ledger/central synchronization: `tasks.md` F2を`[x]`、中央台帳をT069完了/未mergeへ同期。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決する

### T070 completion — 2026-08-09 — 主担当実装記録

- base/head: current merged `1fd0f74`（`origin/main`） → T070 code `cc7c15c`
- scope: 本人日次入力/削除/提出、管理一覧、上長差戻し・承認、HR/管理者締め、管理者再open、月次状態CAS/version、本人/manager/HR scope境界、営業403、CSRF、4言語i18n、390px responsive markup。休暇approval/provider sync/差異通知は未実施
- implementation: `AttendanceServiceImpl`が`m_employee_attendance`/`t_attendance_month`を客先工数から分離し、本人はaccount link自己のみ、managerは対象月末asOfのorganization scope（既存DataScopeのmanager規則と同じ母集団）、HR/管理者はテナント内管理scope、営業はSecurityConfigで画面/APIとも拒否。`入力中→提出済→差戻し→提出済→承認済→締め済→再open`を状態CASで保護し、締め済み編集を拒否
- independently executed tests: `AttendanceApiControllerTest` 7/0/0/0、`AttendanceWorkflowServiceTest` 1/0/0/0、`AttendanceUiContractTest` 2/0/0/0、`MessageBundleConsistencyTest` 4/0/0/0、`JsSyntaxCheckTest` 1/0/0/0、`RoleNavigationVisibilityTest` 2/0/0/0。T070指定回帰合計17/0/0/0、`git diff --check` PASS
- Demo: T070-D1 PASS。本人入力→提出→差戻し→再提出→承認→締め、締め後編集拒否、営業画面/API拒否、CSRF、4言語message、390px markupを定向確認。HR法人の実資料突合と実ブラウザ幅確認はATT-GATE/Mへ繰越
- verdict: `COMPLETED_UNREVIEWED`。R11 P2-01はcurrent merged Head=`1fd0f74`、local ledger provenanceは`git log -1 -- review-ledger.md`で同期済みの`FIXED_BY_IMPLEMENTER`。V83はV82先行merge/apply後にのみ配備可能
- ledger/central synchronization: `tasks.md` A1を`[x]`、中央台帳をT070完了/T071〜T074未着手へ同期。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決する

### Round 1 — 2026-08-09 — 独立Review入力

- base/head: original `5e29f39` → Packet/current merged Head `509bdb7`（`main=origin/main`）
- scope: T067文書差分、B2補助diff provenance、採番・gate・L0証拠
- reviewed issue IDs: `attendance-leave-overtime-compliance-R1-P1-01`、`-P1-02`、`-P2-01`、`-P2-02`、`-P2-03`
- new issue IDs: 上記5件。P0=0、P1=2、P2=3
- independently executed tests: reviewer `git diff --check 5e29f39..509bdb7` PASS。PowerShell inline文書整合チェックはexact command不在
- verdict: `NOT REVIEWABLE`（T068〜T074/V83/L4/Demo未提出。T067はfix必要）
- ledger/central synchronization: P1/P2をFIXED_BY_IMPLEMENTERとして本Roundのfix deltaへ引き継ぐ

### Fix Delta — 2026-08-09 — 主担当

- base/head: `509bdb7` → `2299fbc`
- scope: R1-P1-01〜R1-P2-03の最小修正のみ。T068本体は未開始
- fixes: V83 config seed、B2=`4488ba8`/V81/V82/V83 provenance、ATT-GATE-05/06期限と内部/外部両モード契約、current merged Head固定、V39現行精度、L0 script
- direct regression: `powershell -NoProfile -ExecutionPolicy Bypass -File .kiro/specs/attendance-leave-overtime-compliance/verify-t067-l0.ps1 -BaseCommit 509bdb7 -HeadCommit 2299fbc` → `T067 R1 fix delta L0: PASS`、assertions=10、tests=1/failures=0/errors=0/skipped=0/exit=0
- verdict: `FIXED_BY_IMPLEMENTER`。独立再ReviewでVERIFIED_CLOSEDにするまで自己PASSしない

### Round 1 fix delta再Review — 2026-08-09 — 独立Review入力

- base/head: `509bdb7` → fix内容`2299fbc` → Packet/current merged Head`8edcaa6`
- reviewed issue IDs: R1-P1-01、R1-P1-02、R1-P2-01、R1-P2-02、R1-P2-03
- verdict: P1-01/P1-02/P2-02/P2-03 = `VERIFIED_CLOSED`、P2-01 = `OPEN`。新規P0/P1なし
- evidence: `509bdb7..2299fbc`、`509bdb7..8edcaa6`はいずれも1/0/0/0、`git diff --check` PASS、production/test差分0、worktree clean
- P2-01 minimum fix: fix内容Head=`2299fbc`とPacket/current merged Head=`8edcaa6`を分離し、台帳provenanceを`git log -1 -- review-ledger.md`で解決する。今回の同期後、同一issueを再起票しない

### Round 2 — 2026-08-09 — T070独立Review

- review target: base=`1fd0f7492ab46388c961e2e721ccdedd416929c4` → code=`cc7c15c60bc26bf7b19fbca7759b6e65f572a725` → evidence/current merged Head=`4789c192a733d2d64b13c9941ab53e3780aefbe9`
- git evidence: `HEAD=origin/main=4789c19`、worktree clean、`1fd0f74`/`cc7c15c`/`b327b1b`/`d395797`はいずれもHeadの祖先。treeにはV83がありV82が無い。`git diff --check 1fd0f74..4789c19` PASS
- review中のworking tree: 判定対象を固定した後、reviewerが変更していない`src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java`の未commit差分を検出。`reserved <= latest`を拒否する追加testであり、current Headの判定・test件数・fix verificationには含めない。reviewer変更は本台帳だけ
- independently executed tests: `mvn '-Dtest=AttendanceApiControllerTest,AttendanceWorkflowServiceTest,AttendanceUiContractTest,MessageBundleConsistencyTest,JsSyntaxCheckTest,RoleNavigationVisibilityTest' test` → 17/0/0/0、BUILD SUCCESS、exit 0
- L4 decision: T074が固定L4 checkpointであり同一HeadのL4証拠も未提出のため、本RoundではT070のL2読解/再実行に限定。P1修正がSecurityConfig/OrganizationScope/V83へ及ぶ場合はtest policy §5によりL3またはL4へ昇格する
- reviewed issue: R1-P2-01はFIXED_BY_IMPLEMENTERを不受理。current GitとPacketのmerged状態が再度不一致なのでOPEN継続。同じ根本原因で再起票しない
- new issues: R2-P1-01〜05、R2-P2-01〜02。P0=0/P1=5/P2=3（既存R1-P2-01を含む）
- task verdict: T070=`FAIL`。T067〜T069の全面再監査は本Round対象外。T071〜T074は未着手
- next task: T071はR2-P1-02〜04の共有calendar/scope契約がVERIFIED_CLOSEDになるまで開始不可。R1-P2-01/R2-P2-01〜02だけではblockしない
- spec/Wave verdict: `FAIL: open blockers=R2-P1-01,R2-P1-02,R2-P1-03,R2-P1-04,R2-P1-05`。S12/次Waveは開始不可

#### attendance-leave-overtime-compliance-R2-P1-01

- severity: P1
- violated requirement/acceptance: `design.md` §1、`tasks.md` T068の「V83のmerge/applyはV82後」、handbook §4.1
- file:line: `design.md:5`、`tasks.md:33`、`src/main/resources/db/migration/V83__attendance_leave_overtime_compliance.sql:1`
- reproduction: V81まで適用済みのDB / deploy principal / current `origin/main=4789c19`を配備する。Git treeにはV83がありV82が無い
- expected / actual: expected=V82を含むHeadでV82→V83の順に適用。actual=V83が先にFlyway適用対象となり、Packetの「V83未merge」とも矛盾する
- customer/security/operation impact: 現Headを一度適用すると後日のV82がout-of-orderになり、validate/startup failureまたは再採番・復旧作業が必要になる
- evidence: `git ls-tree -r --name-only HEAD`でV83あり/V82なし、`git merge-base --is-ancestor b327b1b HEAD` exit 0
- minimum acceptable fix: current mainを配備停止しV82を先にmergeする。V83適用済み環境を棚卸しし、存在する場合は過去V83を編集せずlatest+1の順方向復旧/再採番を確定する。台帳のmerged/appliedを実Git・Flyway historyに同期する
- direct regression scope: V81 legacy→V82→V83、fresh V1→latest、Flyway history/checksum、V83適用済み環境のrepair手順
- discovered in: original head（current merged Packet）

#### attendance-leave-overtime-compliance-R2-P1-02

- severity: P1
- violated requirement/acceptance: R1.1、R1.2、R3.1、R5、design §2/§5.1、`overtime-rules.md` §1.2
- file:line: `src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:276-301,304-321,332-354`
- reproduction: 所定480分の通常日に要員が09:00〜23:00、休憩0分を保存する。別caseで勤務カレンダー上の通常日をclientが`法定休日`として送る
- expected / actual: expected=calendarを勤務日asOfで解決し、通常caseは法定内480・時間外360・深夜60、休日種別はserver側の正を使用。actual=`通常`の840分全てをregularへ入れ、overtime/lateNight=0。休日区分もclient値を無条件採用する
- customer/security/operation impact: 月45h/年360h/720h/月100h/rolling平均の入力が誤り、超過警告を見逃すか誤警告する
- evidence: `toAttendance`は`WorkCalendarMapper`/`WorkCalendarDayMapper`を参照せず、`lateNightMinutes(0)`固定。`AttendanceCalculator`実装はrepoに存在しない。既存workflow testは9:00〜18:00だけ
- minimum acceptable fix: calendar/所定時間/法定休日を勤務日asOfで解決する`AttendanceCalculator`を実装し、日8h・週40h、所定休日、法定休日、22:00〜5:00、跨夜、休憩を分単位で一箇所計算する。未確認休日区分はfinding/fail-closedにする
- direct regression scope: 480分のlimit-1/limit/limit+1、週40h、所定休日/法定休日、22時境界、跨夜、休憩、calendar優先順位、scheduled NULL/0、月次再集計→T069 input
- discovered in: original head `cc7c15c`

#### attendance-leave-overtime-compliance-R2-P1-03

- severity: P1
- violated requirement/acceptance: T070 Objective「HRは法人分」、design §5.3、R5「権限外閲覧を拒否」
- file:line: `src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:68-77,174-177,195-229,332-354,421-430`
- reproduction: 同一DBにlegal entity A/Bと各要員を作り、A担当HRで管理一覧を開くかB要員IDへcloseを送る
- expected / actual: expected=A法人のlist/detail/count/actionだけ。actual=HRは`buildOverview(..., null)`で全要員を取得し、任意engineerIdを無条件許可する。新規monthはlegalEntityId未設定、dayはlegalEntityId/organizationIdとも未設定
- customer/security/operation impact: HRが担当外法人の出退勤・備考等のPIIを閲覧し、月次締めまで実行できる
- evidence: HRと管理者が同じ分岐。`buildOverview`のunfiltered `selectList(null)`、`allowedEngineerId`のHR bypass。対象testはHR fixture/法人境界を持たない
- minimum acceptable fix: HRの担当legal entityをserver-side identity/organization historyから解決し、month/dayへlegal entity・organization snapshotを保存する。list/actionともSQL境界で同じ法人集合を適用し、NULL/未知法人はfail-closedにする
- direct regression scope: HR A→A許可/A→B 0件・404、close/reject/reopen各action、管理者全件、法人NULL/履歴なし、list/detail/count同一母集団
- discovered in: original head `cc7c15c`

#### attendance-leave-overtime-compliance-R2-P1-04

- severity: P1
- violated requirement/acceptance: T070 Objective、design §5.3、platform-invariants §1/§2、`OrganizationScopeService` public contract
- file:line: `src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:75-77,421-431`、`src/main/java/com/ses/mapper/EngineerAccountLinkMapper.java:17-39`
- reproduction: (a) `organization.scope.enabled=false`でmanagerが対象月を一覧、(b) 対象月後に組織A→Bへ異動した要員の過去月をA/B managerが一覧/承認
- expected / actual: expected=(a) `hasFullAccess=true`は組織条件なし、(b) 対象月末の履歴所属で判定。actual=(a) `allowedEngineerIds`の空集合を0件扱い、(b) `COALESCE(e.organization_id, uo.organization_id)`が現在のengineer組織を優先する
- customer/security/operation impact: managerの承認対象が全消失するか、異動後の現在組織へ過去勤怠が誤開示され、旧上長が承認できない
- evidence: `OrganizationScopeService`はfull access時の空集合を「条件なし」と明記するが呼出側は確認しない。mapper javadoc/SQLはcurrent engineer organizationを正とする。既存T070 testはmanager service scope/異動fixtureを持たない
- minimum acceptable fix: `hasFullAccess()`を先に評価し、manager時だけ許可集合をSQLへ渡す。要員所属は対象月末asOfの履歴Resolverで解決し、履歴ありNULLと不存在を`CASE WHEN h.id IS NULL`で区別する
- direct regression scope: scope enabled/disabled、空集合0件、直属user追加、異動前/当日/翌日、未来異動、有限終了、履歴なし/ありNULL、list/action同一母集団
- discovered in: original head `cc7c15c`（shared mapperの既存欠陥を新consumerで顕在化）

#### attendance-leave-overtime-compliance-R2-P1-05

- severity: P1
- violated requirement/acceptance: G6「手動修正は理由、申請/承認、version、監査を必須」、R1.4、design §5.4
- file:line: `src/main/java/com/ses/controller/api/AttendanceApiController.java:69-72`、`src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:180-193`、`src/main/java/com/ses/entity/AttendanceMonth.java:41`
- reproduction: 管理者が`POST /api/work-records/attendance/{engineerId}/reopen?month=2026-08`を理由なしで送る
- expected / actual: expected=理由必須の申請/承認とversion/state CAS、業務監査へ理由を保存。actual=単一管理者の1 callでCLOSED→APPROVED、reason入力/保存がなく`closeReason`も未使用
- customer/security/operation impact: 締め済みsnapshotの訂正根拠と職務分離を追跡できず、不正・誤修正の説明責任を満たさない
- evidence: controller/service interfaceにreason/approval requestが存在せず、ApiAuditFilterはuser/method/URI/statusだけを記録する。reopenの自動testも無い
- minimum acceptable fix: reopen commandへ必須reasonとapproval referenceを追加し、申請者単独確定を拒否、承認時にreason/actor/time/versionを永続化する。汎用API監査に加えて業務監査を再読可能にする
- direct regression scope: 空理由拒否、申請者=承認者拒否、二重reopen 409、rollback、監査再読、reopen後snapshot維持
- discovered in: original head `cc7c15c`

#### attendance-leave-overtime-compliance-R2-P2-01

- severity: P2
- violated requirement/acceptance: tasks T070 mobile 390px、shared-standards §5、handbook §7 Demo
- file:line: `src/test/java/com/ses/web/AttendanceUiContractTest.java:11-30`、`review-ledger.md:122,186`
- reproduction: T070-D1証跡を開き、390pxで実ブラウザ操作したartifactを確認する
- expected / actual: expected=desktop/390pxで状態遷移、二重click、reload、戻る、拒否表示の実測。actual=HTMLの`table-responsive`等3文字列をassertしただけで、台帳自身も実ブラウザ未実施と記録する
- customer/security/operation impact: 狭幅で操作ボタン・modal・errorが利用可能か未検証。機能結果の既知誤りは未再現のためP2
- evidence: reviewer再実行17/0/0/0だが`AttendanceUiContractTest`本文は静的文字列assertのみ
- minimum acceptable fix: desktop/390pxの実ブラウザDemoを実施し、主要操作・拒否表示・二重click/reload/backを証跡化する
- direct regression scope: T070本人/manager/HR画面。Mの全UI回帰はT074で別途実施
- discovered in: original head `cc7c15c`

#### attendance-leave-overtime-compliance-R2-P2-02

- severity: P2
- violated requirement/acceptance: shared-standards §3「一覧はpaging上限1000、全件取得APIを新設しない」、handbook §6.2/性能観点
- file:line: `src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:195-229`
- reproduction: HR/管理者 / 1万人の要員と各31日の日次勤怠 / 対象月一覧をGETする
- expected / actual: expected=scope済み月次summaryを安全なpage sizeで取得し、日次detailは必要時に同じ母集団で取得。actual=`engineerMapper.selectList(null)`、全month、全dayを一括List化して単一DTOで返す
- customer/security/operation impact: DB・heap・JSON応答が要員数×日数で増加し、管理画面のtimeout/メモリ圧迫を招く。実負荷証拠は未取得のためP2
- evidence: controller/requestにpage/sizeがなく、serviceにも`PageUtils.safePage`/limitがない。性能testなし
- minimum acceptable fix: month summaryをpagingし、day detailを遅延取得または選択要員単位へ分離する。page/count/detailへ同じHR/manager scopeをSQL境界で適用する
- direct regression scope: 0/1/1000/1001要員、31日、page/count/detail母集団一致、上限外size正規化
- discovered in: original head `cc7c15c`

### Round 2 fix delta — 2026-08-09 — 主担当

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T070 R2 fix delta | R1.1/R1.2/R1.4/R3.1/R5、design §2/§5.1/§5.3/§5.4、platform-invariants §1/§2 | `AttendanceCalculator`、`AttendanceScopeResolver`、`AttendanceScopeMapper`、`EngineerAccountLinkMapper`、`AttendanceServiceImpl`、`AttendanceReopenApprovalAdapter`、reopen DTO/controller/JS、4言語message、定向test | `mvn -B -Dtest=AttendanceApiControllerTest,AttendanceWorkflowServiceTest,AttendanceUiContractTest,MessageBundleConsistencyTest,JsSyntaxCheckTest,RoleNavigationVisibilityTest,AttendanceCalculatorTest,AttendanceScopeResolverTest,AttendanceManagerScopeTest,AttendanceReopenApprovalAdapterTest,AttendanceServiceFullAccessTest test` → 29/0/0/0、skip 0、exit 0。`git diff --check` PASS | T070-D1補助証跡：calendarの通常日09:00〜23:00/休憩0をclient休日指定してもregular=480/overtime=360/lateNight=60、HR法人A/B、manager月末asOf、理由付きreopen申請を確認 | `34654f2`、`4dadfb3`、`b91dc99`、`356d9ee`、`146046e`、`0fc9a22`、`2709e2b`、`5260fd5`、`a0de2b6`、`1fb54d4` | 独立再Review前の主担当証跡。実ブラウザ、paging/detail分離、全環境Flyway history、ATT-GATE-01〜06、L4は未検証。V83はV82環境証跡と先行順序が解消するまでfreeze |

- implementation summary: 日次の正は勤務日asOfの`m_work_calendar`/`m_work_calendar_day`へ移し、日8時間・週40時間・所定休日・法定休日・深夜・跨夜・休憩を`AttendanceCalculator`へ集約した。clientの`workType`は分類の正にしない。未解決calendar、履歴、法人scopeはUNKNOWN/判定不能としてfail-closed。
- scope summary: HRは担当legal entityからserver-sideで要員集合を解決し、month/dayへlegal entity・organization snapshotを保存する。managerは`hasFullAccess()`を先に評価し、有限scopeだけ対象月末asOfの履歴集合をlist/actionへ同じ境界で渡す。履歴UNKNOWN、snapshot NULLは許可しない。
- reopen summary: 締め済み再openは理由必須の`attendance.reopen` approval requestへ委譲し、既存approval engineの申請者除外、request/action監査、target version/state CASを利用する。申請時点では`CLOSED`を維持する。
- issue state: `R2-P1-02`、`R2-P1-03`、`R2-P1-04`、`R2-P1-05`は`FIXED_BY_IMPLEMENTER / independent re-review requested`。独立ReviewがVERIFIED_CLOSEDとするまで自己PASSにはしない。`R2-P1-01`はV82/V83の全environment `flyway_schema_history`証跡不足のため`OPEN / ENVIRONMENT_EVIDENCE_REQUIRED`を継続し、V83のコード・migration・applyは変更していない。`R1-P2-01`は実Git HeadとPacketの同期確認を次のReviewで再確認する。
- remaining P2: `R2-P2-01`（desktop/390px実ブラウザartifact）と`R2-P2-02`（month paging/detail分離）は未完了。T071開始条件であるP1-02〜04の独立VERIFIED_CLOSEDとは別に、T074/Mで再評価する。
- base/head: fix deltaのreview baseは`4789c19`、実装確認Headは`1fb54d4`（`HEAD=origin/main`）。ledger自身のcommitを含むcurrent Headは`git log -1 -- .kiro/specs/attendance-leave-overtime-compliance/review-ledger.md`で解決する。V83の適用履歴はGit commitでは証明しない。
- rollback: 本番未適用のためDB rollbackは不要。コードrollbackは上記実装commitを逆順でrevertし、approval requestの未確定申請は既存engineのcancel/expire運用で処理する。V83 migrationの編集・削除・再採番は行わない。
- review handoff condition: R11担当が上記direct regressionを独立実行し、P1-02〜05の証跡と、別途R10からの全環境Flyway historyを確認した後にT071の開始可否を判定する。R11再Review合格前にT071〜T074を開始済みとは記録しない。

### Round 2 fix delta 独立再Review — 2026-08-09 — R11担当

- review target: fix base=`4789c192a733d2d64b13c9941ab53e3780aefbe9` → code/test=`1fb54d4` → ledger/current merged Head=`9af70718cc34ec18522219273e8f18e745777a9e`
- git evidence: Review固定時`HEAD=origin/main=9af7071`、`4789c19`/`1fb54d4`はいずれもHeadの祖先、worktree clean、`git diff --check 4789c19..9af7071` PASS。Review中に本台帳の暫定判定だけを同期する`8c23e44`へ進んだが実装対象差分は不変。treeはV83あり/V82なし
- independent direct regression: 指定11 classを再実行し`29/0/0/0`、skip 0、exit 0。追加で`ApprovalEngineServiceTest,ApprovalEngineConflictTest,RouteResolverServiceTest`を再実行し`45/0/0/0`、skip 0、exit 0
- scope: handbook §11に従いOPEN issue、fix delta、direct regression、変更public contractのconsumerだけを確認。T067〜T069の全面再監査とL4全量再実行はしていない
- issue result: `R2-P1-05=VERIFIED_CLOSED`、`R1-P2-01=VERIFIED_CLOSED`。`R2-P1-02`〜`04`は下記残存条件によりOPEN、`R2-P1-01=OPEN / ENVIRONMENT_EVIDENCE_REQUIRED`。`R2-P2-01`〜`02`は変更なし

#### attendance-leave-overtime-compliance-R2-P1-02 — OPEN（fix delta残存）

- severity: P1
- violated requirement/acceptance: R1.1、R1.2、R3.1、R5、design §5.1「勤務日asOfの有効calendar」、design §5.2、決定表外判断禁止
- file:line: `src/main/java/com/ses/service/attendance/AttendanceCalculator.java:46-55,75-103`、`src/test/java/com/ses/service/attendance/AttendanceCalculatorTest.java:60-70,99-121`
- reproduction (data/role/time): 同一legal entity=100へ法人既定calendarと、別要員21・別組織11のより新しい個人calendarを作る。要員20・組織10が2026-08-03を保存する。別caseで21:00〜23:00、実休憩21:00〜22:00、休憩60分を保存する
- expected / actual: expected=要員20には本人→組織10→法人100既定の排他的tierだけを適用し、休憩が深夜帯外なら22:00〜23:00の深夜60分を保持する。actual=別要員calendarも`legalEntityId`一致で候補/priority 2となり、新しい版なら選択される。休憩は時刻帯を持たず常に`end-break`として退勤直前へ配賦され、このcaseの深夜は0分になる
- customer/security/operation impact: calendar/休日/所定時間または深夜時間が別要員設定へ置換され、時間外集計・割増・36協定findingを誤る
- evidence: query/filterは各scope列をORし、specificityも対象外の個人calendarを法人一致で受理する。DDLは複数scope列の同時設定を禁止しない。既存testは対象本人calendar対法人calendarだけで、別要員/別組織競合をassertしない。休憩testは休憩位置の入力なしに退勤直前配賦を固定しており、spec決定表に根拠がない
- minimum acceptable fix: calendar適用tierをspecで確定し、SQL/Javaとも他要員・他組織calendarが法人fallbackへ入らない排他的条件にする。休憩開始/終了を保持するか、深夜・時間外への配賦規則を決定表へ追加してからcalculatorを合わせる
- direct regression scope: 本人/他要員/対象組織/他組織/法人既定、同日validFrom/id競合、休憩が深夜前/中/後、8h・週40h・22時・跨夜、月次再集計→T069 input
- discovered in: fix delta `4dadfb3`〜`1fb54d4`

#### attendance-leave-overtime-compliance-R2-P1-03 — OPEN（fix delta残存）

- severity: P1
- violated requirement/acceptance: T070 Objective「HRは法人分」、design §5.3、R5、platform-invariants §1.1「明示NULLと履歴不存在を区別」/§2.2
- file:line: `src/main/java/com/ses/service/attendance/AttendanceScopeResolver.java:45-54`、`src/main/java/com/ses/mapper/AttendanceScopeMapper.java:47-51`、`src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:86-90,469-474`
- reproduction (data/role/time): 対象月末に要員のaccounting history行を`organization_history_status='KNOWN', organization_id=NULL`で作り、linked userのprimary organizationを法人Aへ置く。法人A担当HRで管理一覧を開くか当該要員へclose/rejectを送る
- expected / actual: expected=履歴行ありNULLは現在/user組織へfallbackせず、法人判定不能としてDB側0件・action 404。actual=SQLの`ELSE COALESCE(eh.organization_id, uo.organization_id)`とJavaの`organizationId == null` fallbackが法人Aへ復活させる
- customer/security/operation impact: 本来所属不明でfail-closedすべき要員の出退勤・備考等PIIをHRへ開示し、締め/差戻し等の更新を許可する
- evidence: platform-invariantsの正解は`CASE WHEN h.id IS NULL THEN current ELSE h.value END`。指定29件はPASSしたが`AttendanceScopeResolverTest`は履歴なしのA/Bだけで履歴ありNULLを持たない。実行ログのSQLにも問題の`ELSE COALESCE`を確認
- minimum acceptable fix: 履歴行不存在の場合だけcurrent/user organizationへfallbackし、履歴行ありはNULLを含め履歴値を採用する。snapshot/list/actionを同じResolver規則に統一する
- direct regression scope: HR A→A/A→B、履歴なし/KNOWN NULL/UNKNOWN、前日/当日/翌日、月初/月末、list/detail/count/close/reject/reopen、管理者全件
- discovered in: fix delta `34654f2`〜`1fb54d4`

#### attendance-leave-overtime-compliance-R2-P1-04 — OPEN（fix delta残存）

- severity: P1
- violated requirement/acceptance: T070 Objective、design §5.3、platform-invariants §1.1/§2、`OrganizationScopeService` public contract
- file:line: `src/main/java/com/ses/mapper/EngineerAccountLinkMapper.java:28-43`、`src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:91-94,476-480`
- reproduction (data/role/time): manager配下要員について対象月末のhistory行を`KNOWN, organization_id=NULL`とし、linked userのprimary organizationだけをmanager配下に置く。managerで同月のlist/actionを行う
- expected / actual: expected=履歴ありNULLはscope不明として0件/404。actual=`ELSE COALESCE(eh.organization_id, uo.organization_id)`がuser組織を採り、managerへ許可する。なお`hasFullAccess()`先判定と対象月末asOf自体はfix deltaで修正済み
- customer/security/operation impact: 所属不明の過去勤怠をmanagerへ誤開示し、承認/差戻しを実行可能にする
- evidence: `AttendanceManagerScopeTest`は履歴A/BのasOfだけ、`AttendanceServiceFullAccessTest`は無制限sentinelだけをassertし、履歴ありNULLを持たない。mapperはplatform-invariants §1.1の禁止形を残す
- minimum acceptable fix: shared mapperを`CASE WHEN eh.id IS NULL THEN ... ELSE eh.organization_id END`へ修正し、履歴ありNULLをDB側0件にする。mapperの全consumerで同一asOf/NULL規則を維持する
- direct regression scope: full-access/有限scope/空集合、直属user追加、履歴なし/KNOWN NULL/UNKNOWN、前日/当日/翌日、未来開始/有限終了、list/action同一母集団
- discovered in: fix delta `34654f2`〜`1fb54d4`

#### task別 Requirements → 実装 → test → Demo → 判定

| task | requirements | fix delta実装 | 独立test/Demo | 判定 |
|---|---|---|---|---|
| T067 | G6/source inventory/release gate | 本delta変更なし | 本再Review対象外 | 既存判定維持 |
| T068 | R1 DDL/V83 | 本delta変更なし。V83 tree内/V82不在 | MySQL/Flyway history未実施 | `R2-P1-01 OPEN`、deploy/apply freeze |
| T069 | R3 overtime calculator | T070日次calculator出力がconsumer | 日次境界testはPASS、calendar/休憩残存条件あり | T070 input契約は未合格 |
| T070 | R1.1/R1.2/R1.4/R3.3/R5 | calculator、HR/manager scope、reopen approval | 29件PASS。approval共通45件PASS。実ブラウザ未実施 | **FAIL**（P1-02〜04 OPEN、P1-05 CLOSED） |
| T071 | R2 leave/approval | 未着手 | 未実施 | 開始不可 |
| T072 | provider sync | 未着手 | 未実施 | 未判定 |
| T073 | discrepancy/notification | 未着手 | 未実施 | 未判定 |
| T074 | M regression/legal acceptance | 未着手 | L4/Demo未実施 | 未判定 |

#### 横断判定・未検証環境・開始可否

| 観点 | 判定 | 根拠 |
|---|---|---|
| migration/rollback | FAIL（環境証拠待ち） | V83あり/V82なし。V83 deploy/apply freezeと順方向復旧方針は維持。全environment Flyway history、fresh/legacy MySQLは未検証 |
| scope/security | FAIL | role/CSRF/4言語testはPASS、full-access sentinelも修正済み。ただしHR/managerの履歴ありNULLがfail-open |
| 状態競合/監査 | PASS（T070 reopen delta） | reason必須、申請時CLOSED、approval engineの申請者除外、request/action監査、status/version CASをコードと74件の関連testで確認 |
| 外部障害 | 未判定 | T072未着手。本fix deltaは外部APIを変更しない |
| 性能 | P2 OPEN | month summary/day detailが未paging。R2-P2-02継続 |
| UI/Demo | P2 OPEN | desktop/390px実ブラウザ未実施。R2-P2-01継続 |

- production前条件: ATT-GATE-01〜06、法人別36協定・就業規則突合は本ReviewのFAIL理由にしていない。V82→V83順序と全environment `flyway_schema_history`証拠はdeploy/apply前の必須条件
- overall verdict: `FAIL: open P1=R2-P1-01,R2-P1-02,R2-P1-03,R2-P1-04`
- next task/Wave: T071はP1-02〜04が独立`VERIFIED_CLOSED`になるまで開始不可。次spec/次Waveも開始不可
- central ledger転記用短文: `R11 T070 R2 fix deltaは29件＋approval共通45件PASS。R2-P1-05/R1-P2-01はVERIFIED_CLOSED。calendar scope/休憩配賦とHR/managerの履歴ありNULL fallbackによりR2-P1-02〜04はOPEN、V82/V83環境証拠R2-P1-01もOPEN。T070 FAIL、T071/次Wave開始不可。`

### Round 2 fix delta follow-up — 2026-08-09 — 主担当

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T070 R2-P1-02〜04 follow-up | R1.1/R1.2/R3.1/R5、design §5.1/§5.3、platform-invariants §1.1/§2.2 | `AttendanceCalculator`、`AttendanceScopeResolver`、`AttendanceScopeMapper`、`EngineerAccountLinkMapper`、calculator/HR/manager境界test | R11指定11 class＋Approval共通3 class: 77/0/0/0、skip 0、exit 0。追加calendar tier test: 7/0/0/0、`git diff --check` PASS | 本人→対象組織→法人既定を排他的に解決し、別要員/他組織calendarをfallbackへ混入させない。履歴ありNULLはHR/managerともDB側0件・snapshot判定不能 | `43c5a3e`、`e3fe948` | P1-02の休憩配賦規則は未確定。V83・migration・本番applyは変更していない |

- implementation delta: `AttendanceCalculator`はperson、organization（`engineer_id IS NULL`）、legal entity default（`engineer_id/organization_id IS NULL`）を排他的tierとして順に問い合わせる。履歴あり`KNOWN + organization_id=NULL`は、`AttendanceScopeResolver`、`AttendanceScopeMapper`、`EngineerAccountLinkMapper`のいずれも現在所属・linked userへfallbackしない。`UNKNOWN`も従来どおり除外する。
- test delta: 既存のT070指定29件＋Approval共通45件に加え、current codeでcalculator tier追加7件を実行した。full combined commandはcalculator追加前の77件、追加分は7件（新規1件を含む）であり、current cumulative evidenceは78 assertions相当として扱い、未実行の全量test/L4とは区別する。
- residual issue state: `R2-P1-03`、`R2-P1-04`は実装fix済みだが、R11の独立VERIFIED_CLOSED前のため`FIXED_BY_IMPLEMENTER / re-review requested`。`R2-P1-02`はcalendar tierをfix済みだが、休憩配賦の決定表外判断が残るため`OPEN / SPEC_CLARIFICATION_REQUIRED`。`R2-P1-01`、`R2-P2-01`、`R2-P2-02`は従前どおりOPEN。P1-05とR1-P2-01はR11判定のVERIFIED_CLOSEDを維持し、再変更しない。
- spec clarification proposal（実装停止点）: `breakMinutes`だけでは休憩が深夜前・中・後のどこに存在したかを復元できない。発注者は次のいずれかを決定表へ追加する必要がある。(A) 推奨：休憩開始/終了（必要なら複数区間）を入力・保存し、勤務区間から実休憩区間を除外して深夜/時間外を区間intersectionで算定する。(B) `breakMinutes`のみを維持し、休憩をどの時間帯へ配賦するか（深夜前/中/後、跨夜、複数休憩、境界）を明示的な決定表として固定する。決定前にcalculatorへ新しい配賦規則やschema変更を推測追加しない。
- migration/gate: `src/main/resources/db/migration/V83__attendance_leave_overtime_compliance.sql`は変更していない。V82不在・全environment `flyway_schema_history`未取得のためV83 deploy/apply freezeを継続する。ATT-GATE-01〜06と社労士/法人規程突合は本開発fixのblockerではないが、本番gateとして未達を維持する。
- base/head: fix review base=`4789c19`、current code/test Head=`e3fe948`、`HEAD=origin/main`。ledger同期commit後のcurrent Headは`git log -1 -- .kiro/specs/attendance-leave-overtime-compliance/review-ledger.md`で解決する。
- rollback: 本fixは本番未適用。`43c5a3e`、`e3fe948`を逆順revertすればcalendar/scope変更を戻せる。V83の削除・編集・再採番は行わない。
- next Review handoff: R11担当は`43c5a3e`/`e3fe948`を対象に、履歴なし/KNOWN NULL/UNKNOWNのHR/manager list/action、person/organization/legal tier、同日validFrom/id競合を独立再実行する。休憩配賦はspec決定後に追加実装・回帰し、P1-02〜04が独立VERIFIED_CLOSEDになるまでT071を開始しない。

### Round 2 fix follow-up 独立再Review — 2026-08-09 — R11担当

- review target: base=`4789c192a733d2d64b13c9941ab53e3780aefbe9`、code fix=`43c5a3e`、additional regression=`e3fe948`、ledger/current merged Head=`5f84fa7ffcc1174b2734a8fc45440c4a31244d4a`
- git evidence: Review開始時`HEAD=origin/main=5f84fa7`、worktree clean、各commitはHeadの祖先、`git diff --check 4789c19..5f84fa7` PASS。V83変更なし、V82 tree不在。Review中に他task由来の未追跡`src/test/java/com/ses/migration/FlywayEnvironmentEvidenceTest.java`を検出したが、本Review・実装commit・78件の証拠には含めない
- independent regression: R11指定11 class＋approval共通3 classをcurrent Headで一括実行し`78/0/0/0`、skip 0、exit 0。主担当記載の77件ではなく、追加calendar testを含むcurrent正味件数は78件
- convergence scope: R2-P1-02〜04のfix delta、変更mapper/resolverのattendance consumer、direct regressionだけを確認。T067〜T069全面再監査/L4/MySQL/Flywayは再実行していない
- result: `R2-P1-04=VERIFIED_CLOSED`。`R2-P1-02=OPEN / SPEC_CLARIFICATION_REQUIRED`。`R2-P1-03`は明示NULL subconditionを確認したが、月次snapshotとHR認可母集団の不一致が同じissueに残るためOPEN。新規issue IDは起票しない

#### attendance-leave-overtime-compliance-R2-P1-02 — OPEN / SPEC_CLARIFICATION_REQUIRED

- severity: P1
- violated requirement/acceptance: R1.1/R1.2/R3.1/R5、design §5.1、決定表外判断禁止
- file:line: `src/main/java/com/ses/service/attendance/AttendanceCalculator.java:46-55,75-134`、`src/test/java/com/ses/service/attendance/AttendanceCalculatorTest.java:60-70,99-155`
- reproduction (data/role/time): 要員 / 2026-08-03 21:00〜23:00 / 実休憩21:00〜22:00 / `breakMinutes=60`
- expected / actual: expected=実休憩が深夜前なので深夜60分。ただし現行modelは休憩時刻を保持せず、期待配賦を決める決定表も無い。actual=`effectiveEnd=end-break`で退勤直前へ配賦し深夜0分
- customer/security/operation impact: 深夜・時間外・割増および36協定入力が休憩位置により誤る
- evidence: calendarは本人→対象組織（個人NULL）→法人既定（個人/組織NULL）の排他的queryへ修正され、別要員/他組織fixtureもPASS。休憩は依然total minutesだけで位置を復元不能
- minimum acceptable fix: 方式A（休憩区間を保存して勤務/深夜区間とのintersection）または方式B（total minutesの配賦規則を全境界込みで決定表へ追加）を発注者が選択し、spec改訂後に実装する。Review担当は未決定規則を選ばない
- direct regression scope: 深夜前/中/後、跨夜、複数休憩、休憩0/全時間、不正重複、8h/週40h/22時、月次再集計→T069 input
- discovered in: original head、calendar subconditionはfix delta `43c5a3e`/`e3fe948`で解消、休憩はspec gap継続

#### attendance-leave-overtime-compliance-R2-P1-03 — OPEN（snapshot consumer残存）

- severity: P1
- violated requirement/acceptance: T070 Objective「HRは法人分」、design §5.3、R5、platform-invariants §1「過去実績は月次snapshotし以後不変」/§2.2
- file:line: `src/main/java/com/ses/service/impl/AttendanceServiceImpl.java:86-90,223-260,461-474,364-412`、`src/main/java/com/ses/service/attendance/AttendanceScopeResolver.java:45-65`、`src/main/java/com/ses/mapper/AttendanceScopeMapper.java:47-51`
- reproduction (data/role/time): 要員が月初の法人A所属時に`AttendanceMonth(legal_entity_id=A)`を作成し、対象月末までに法人Bへ異動する。法人A HRと法人B HRで対象月をlistし、close/reject/reopenを送る
- expected / actual: expected=既存monthの法人snapshot Aを認可の正とし、A HRだけがlist/action可能。actual=対象月末の要員所属Bからallowed engineer IDを作り、month query/actionはIDだけを検査するためB HRがA snapshot月を操作し、A HRは拒否される
- customer/security/operation impact: 異動を跨ぐ過去勤怠PIIが別法人HRへ誤開示され、締め・差戻し・再open申請の主体も誤る
- evidence: `buildOverview`はHR allowed engineer IDでmonthを絞るが`legal_entity_id`はNOT NULL確認だけ。`allowedEngineerId`も同じ月末engineer ID集合のみを検査する。monthのsnapshot列は作成後保持されるが認可consumerに使われない。今回のKNOWN NULL testはresolver/mapperだけで、この異動snapshot caseを持たない
- minimum acceptable fix: HR担当legal entity ID集合をserver-sideで解決し、既存monthの`legal_entity_id` snapshotに対してlist/detail/count/actionを同じSQL母集団で適用する。actionはmonthをlock後snapshot法人を検証し、engineer current/asOfだけで許可しない。snapshot訂正は明示権限・監査理由に限定する
- direct regression scope: A→B月中異動、異動前/当日/翌日、A/B HR list/detail/count/close/reject/reopen、履歴なし/KNOWN NULL/UNKNOWN、管理者全件、rollback
- discovered in: original issueのsnapshot/list/action条件、fix follow-up `43c5a3e`でNULL subcondition解消後もconsumerに残存

#### task・横断・開始判定

| 対象 | 判定 | 独立証拠/未検証 |
|---|---|---|
| T070 calendar | PARTIAL PASS | 排他的tierはPASS。休憩配賦はspec決定待ち |
| T070 HR scope | FAIL | KNOWN NULL/UNKNOWNはfail-closedへ修正。month snapshotとlist/action母集団が不一致 |
| T070 manager scope | PASS | `R2-P1-04 VERIFIED_CLOSED`。full-access/asOf/NULL CASEを確認 |
| migration | FAIL / ENVIRONMENT_EVIDENCE_REQUIRED | R2-P1-01継続。V82→V83、全environment Flyway history、fresh/legacy MySQL未検証 |
| security/scope | FAIL | HR法人越境のsnapshot caseが残る。CSRF/role/manager境界は関連test PASS |
| performance/UI | P2 OPEN | paging/detail分離、desktop/390px実ブラウザ未実施 |
| T071 | 開始不可 | R2-P1-02/03がVERIFIED_CLOSEDになるまでblock |

- production前条件: ATT-GATE-01〜06と社労士/法人別規程突合は本ReviewのFAIL理由にしていない。V83 deploy/apply freezeを維持
- overall verdict: `FAIL: open P1=R2-P1-01,R2-P1-02,R2-P1-03`
- central ledger転記用短文: `R11 T070 R2 follow-upはcurrent Head 78/0/0/0 PASS。calendar排他的tierとmanager NULL/asOfを確認しR2-P1-04 VERIFIED_CLOSED。R2-P1-02は休憩配賦のSPEC_CLARIFICATION_REQUIRED、R2-P1-03はmonth legal entity snapshotをHR list/action認可へ使わないためOPEN。R2-P1-01環境証拠待ち、P2×2継続。T070 FAIL、T071/次Wave開始不可。`

### Round 2 snapshot consumer fix delta — 2026-08-09 — 主担当

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T070 R2-P1-03 follow-up | T070 Objective「HRは法人分」、R5、design §5.3、platform-invariants §1/§2.2 | `AttendanceScopeResolver`、`AttendanceServiceImpl`、`AttendanceMonthSnapshotScopeTest`、full-access fixture | R11指定11 class＋Approval共通3 class＋snapshot test: 79/0/0/0、skip 0、exit 0。`git diff --check` PASS | 月初法人Aでmonth snapshotを作成後、要員current所属をBへ置換。B HRはlist 0件/close 404、A HRはlist表示/close成功。管理者経路は従来どおり全件 | `430296e`、`0406a4f` | R11独立VERIFIED_CLOSED前。R2-P1-02の休憩配賦spec未確定、R2-P1-01のFlyway環境証拠、P2-01/02は未完了 |

- implementation delta: HR management listは対象月末のcurrent/asOf engineer IDを認可正にせず、server-side解決した担当legal entity集合を`t_attendance_month.legal_entity_id`へSQL条件として適用する。既存monthのengineer表示名・日次detailは、同じ月次snapshotで選ばれたmonth ID集合から導出する。HR closeはmonthをlockした後、同じsnapshot法人集合を検証してから状態CASへ進む。manager/adminの既定scope・admin全件は変更していない。
- consumer boundary: `AttendanceScopeResolver.allowedHrLegalEntityIds`、`AttendanceServiceImpl.buildOverview`、`close`/`assertHrMonthSnapshotAllowed`を同じ法人snapshot規則へ統一した。明示NULL/UNKNOWNの履歴fail-closed修正は`43c5a3e`の既存deltaを維持する。
- test evidence: current full direct commandは`AttendanceApiControllerTest, AttendanceWorkflowServiceTest, AttendanceUiContractTest, MessageBundleConsistencyTest, JsSyntaxCheckTest, RoleNavigationVisibilityTest, AttendanceCalculatorTest, AttendanceScopeResolverTest, AttendanceManagerScopeTest, AttendanceReopenApprovalAdapterTest, AttendanceServiceFullAccessTest, AttendanceMonthSnapshotScopeTest, ApprovalEngineServiceTest, ApprovalEngineConflictTest, RouteResolverServiceTest`で79/0/0/0。Review中に検出された他task由来のFlyway evidenceは本specのcommit・判定・test件数へ含めていない。
- issue state: `R2-P1-03=FIXED_BY_IMPLEMENTER / independent re-review requested`。月次snapshot A→B list/actionをR11へ提出済みで、独立VERIFIED_CLOSEDまでは自己PASSにしない。`R2-P1-04=VERIFIED_CLOSED`、`R2-P1-02=OPEN / SPEC_CLARIFICATION_REQUIRED`、`R2-P1-01=OPEN / ENVIRONMENT_EVIDENCE_REQUIRED`、P2-01/02 OPENを維持する。
- spec blocker: 休憩時刻・配賦規則は決定表改訂待ち。A（休憩区間保存＋intersection）またはB（`breakMinutes`のみの全境界配賦規則）の発注者決定前にcalculator/schemaを推測変更しない。
- base/head: R11 follow-up base=`4789c19`、snapshot code=`430296e`、test=`0406a4f`、current `HEAD=origin/main=0406a4f`。ledger同期後のHeadは`git log -1 -- .kiro/specs/attendance-leave-overtime-compliance/review-ledger.md`で解決する。
- rollback: 本番未適用。`0406a4f`、`430296e`を逆順revertすれば本deltaを戻せる。snapshotデータの訂正は行っておらず、V83 migrationの編集・削除・再採番はしていない。
- next Review handoff: R11担当はA→B月中異動、異動前/当日/翌日、A/B HR list/detail/count/close/reject/reopen、履歴なし/KNOWN NULL/UNKNOWNを独立確認する。R2-P1-02の決定表改訂後に休憩境界testを追加し、R2-P1-02/03がVERIFIED_CLOSEDになるまでT071を開始しない。

### Round 2 snapshot consumer fix 独立再Review — 2026-08-09 — R11担当

- review target: code=`430296e`、test alignment=`0406a4f`、ledger/current merged Head=`df7f6b1f5e27b64876133d26debd95422d29379a`
- git evidence: `HEAD=origin/main=df7f6b1`、各commitはHeadの祖先、`git diff --check 5f84fa7..df7f6b1` PASS。Review開始時から他taskの未commit差分が多数あり、本specの`design.md/tasks.md`もmigration予約文だけ変更されていたが、target commitへ含まれずsnapshot実装・休憩決定表を変更しないため本判定から除外して保持
- independent regression: R11指定11 class＋snapshot test＋approval共通3 classをcurrent Headで一括再実行し`79/0/0/0`、skip 0、exit 0
- result: `R2-P1-03=VERIFIED_CLOSED`。新規P0/P1なし。`R2-P1-02=OPEN / SPEC_CLARIFICATION_REQUIRED`、`R2-P1-01=OPEN / ENVIRONMENT_EVIDENCE_REQUIRED`、P2-01/02 OPENを維持

#### attendance-leave-overtime-compliance-R2-P1-03 — VERIFIED_CLOSED

- violated requirement/acceptance: T070 Objective「HRは法人分」、design §5.3、R5、platform-invariants §1/§2.2
- implementation: `AttendanceScopeResolver.allowedHrLegalEntityIds`がHRの担当法人集合をserver-side解決。`AttendanceServiceImpl.management`は`t_attendance_month.legal_entity_id`へSQL条件を適用し、取得monthのengineer IDだけで表示名・day detailを取得する。`close`はmonth lock後に同じsnapshot法人集合を検証してから状態CASする
- direct evidence: `AttendanceMonthSnapshotScopeTest`はmonth=A snapshot、engineer current=B、HR A/Bを作り、B HRのlist 0件/close拒否、A HRのlist 1件/close成功と`締め済`をassertする。current 79件PASS
- operation matrix: HRの本機能操作はmanagement list（DTO内month count/detail/day）とclose。reject/approveは`requireManagerRole`でmanager/admin限定、reopenはadmin限定のためHR snapshot consumerではない。manager/adminの既存母集団はdeltaで変更していない
- history boundary: 履歴なしはcurrent/user fallback、KNOWN NULL/UNKNOWNはfail-closedという`43c5a3e`のCASE/Resolverを維持し、snapshot認可は既存monthの法人を正とするため月中A→B異動で月末所属へ巻き戻らない
- rollback: 本番未適用。`0406a4f`、`430296e`を逆順revert。DB snapshot訂正、V83編集・削除・再採番は不要
- reopen condition: HR legal entity resolver、AttendanceMonth snapshot、management/close、role境界のいずれかを変更した場合
- discovered in: original issue、fix verified at `df7f6b1`

#### 横断・開始判定

| 観点 | 判定 | 根拠 |
|---|---|---|
| T070 HR scope/security | PASS | A/B snapshot list/detail/count相当とclose、NULL/UNKNOWN、manager/admin非変更を確認 |
| T070 calendar/休憩 | FAIL / SPEC_CLARIFICATION_REQUIRED | calendar tierはPASS、休憩配賦決定表が未確定。R2-P1-02継続 |
| migration | FAIL / ENVIRONMENT_EVIDENCE_REQUIRED | committed HeadではV83あり/V82なし。全environment Flyway history未取得、deploy/apply freeze継続 |
| performance/UI | P2 OPEN | paging/detail API分離、desktop/390px実ブラウザ未実施 |

- production前条件: ATT-GATE-01〜06、社労士/法人別規程突合は本ReviewのFAIL理由にしない。uncommittedのV84予約文は次の固定Packetへcommitされるまでmigration判定の正にしない
- overall verdict: `FAIL: open P1=R2-P1-01,R2-P1-02`
- next task/Wave: T071はR2-P1-02が独立`VERIFIED_CLOSED`になるまで開始不可。次spec/次Waveも開始不可
- central ledger転記用短文: `R11 T070 snapshot consumer fixはcurrent Head 79/0/0/0 PASS。HR list/detail/count相当とcloseをmonth legal entity snapshotへ統一し、A→B異動fixtureでB HR 0件/拒否・A HR list/close成功を確認。R2-P1-03 VERIFIED_CLOSED、新規P0/P1なし。R2-P1-02は休憩配賦SPEC_CLARIFICATION_REQUIRED、R2-P1-01は環境証拠待ち、P2×2継続。T070 FAIL、T071/次Wave開始不可。`

### Round 2 migration decision follow-up 独立再Review — 2026-08-09 — R11担当

- review target: migration environment test=`55c39cd`、formal decision/synchronization=`08eb098`、provenance=`23e48e0`,`b75af1a`、current Head=`b75af1a1eff16e6c5723a2a2310a31ec324e7f80`
- git evidence: `HEAD=origin/main=b75af1a`。S11 V83 scriptは既存のまま、V82 fileなし、S10〜S17予約資料はV84〜V90へ同期。Review開始時の本台帳だけは前回reviewer追記が未commitであり、本sectionを同じreviewer差分へ追加した
- independent regression: `FlywayEnvironmentEvidenceTest,SpecDispatchConsistencyTest`をDocker/MySQL 8で再実行し`10/0/0/0`、skip 0、exit 0。fresh DBへ82 migrationsを適用しV83 success、V82 absent、latest successful=83、checksum/installed_onありを確認
- result: `R2-P1-01=VERIFIED_CLOSED`。新規P0/P1なし。`R2-P1-02=OPEN / SPEC_CLARIFICATION_REQUIRED`、P2-01/02 OPENを維持

#### attendance-leave-overtime-compliance-R2-P1-01 — VERIFIED_CLOSED

- violated requirement/acceptance: design §1、tasks T068 migration順、handbook §4
- prior reproduction: V81環境へV82不在でV83を適用し、将来V82を追加するとout-of-order/validate failureになる
- resolution / expected: V83はS11実在migrationとして変更せず、V82を永久欠番として後から補填しない。S10はV84、後続はV85〜V90へ繰り上げる
- environment evidence: local-defaultはlatest V74でV82/V83なし。CI/Testcontainers fresh MySQLはV83 success/V82 absent/latest=83。GitHub Environmentは0、staging/production/other legacyはrepoに未構成としてinventory化し、後から外部環境が提示された場合はgateを再開する
- customer/security/operation impact: 将来V82追加という競合原因を正式decisionとguardで除去し、過去V83編集/out-of-order/legacy backfillを禁止した
- evidence: `migration-order-decision-r4-p1-01.md`、`environment-evidence-packet.md`、`s10-r4-p1-01-v83-realized.properties`、`SpecDispatchConsistencyTest`、`FlywayEnvironmentEvidenceTest`。reviewer再実行10件PASS
- rollback: 本deltaは文書/fixture/testだけ。該当commitをrevertし、DB rollbackやV83編集は行わない
- reopen condition: V82を作成・補填する変更、V83 checksum変更、予約表変更、staging/production/legacy environment追加
- discovered in: original head、fix verified at `b75af1a`

#### attendance-leave-overtime-compliance-R2-P1-02 — OPEN継続

- current evidence: `df7f6b1..b75af1a`にAttendanceCalculator、休憩model、design §5.1の休憩配賦決定表、休憩境界testの変更はない
- reproduction/impact/minimum fix/direct regression: 既存OPEN registerおよびRound 2 fix follow-up記載を維持。方式A/Bの発注者決定、spec改訂、実装、深夜前/中/後・跨夜・複数休憩等の直接回帰が必要
- ledger inconsistency: 中央`spec-execution-ledger.md`のS11行は`T071開始可`と記載するが、R2-P1-02が独立閉鎖されていないため誤り。同issueのblocker記録として元の実装対話で訂正する

#### 横断・開始判定

| 観点 | 判定 | 根拠 |
|---|---|---|
| migration | PASS（repo-known scope） | V82永久欠番、V83 MySQL適用成功、予約guard 10件PASS。新environment追加時は再gate |
| T070 calendar/休憩 | FAIL | calendar tierは閉鎖済み。休憩配賦だけ未決定 |
| security/scope/state | PASS（今回対象契約） | R2-P1-03/04/05のVERIFIED_CLOSEDを維持。本deltaでconsumer変更なし |
| performance/UI | P2 OPEN | paging/detail分離、desktop/390px実ブラウザ未実施 |

- production前条件: ATT-GATE-01〜06、社労士/法人別規程突合は本ReviewのFAIL理由にしていない。repo外の本番環境が追加された場合はFlyway read-only evidenceを追加する
- overall verdict: `FAIL: open P1=R2-P1-02`
- next task/Wave: T071はR2-P1-02が独立`VERIFIED_CLOSED`になるまで開始不可。次spec/次Waveも開始不可
- central ledger転記用短文: `R11 migration follow-upはDocker/MySQLを含む10/0/0/0、skip 0でV82永久欠番・V83 success・S10=V84以降のguardを確認しR2-P1-01 VERIFIED_CLOSED。新規P0/P1なし。R2-P1-02は休憩配賦のspec/実装/test変更がなくOPEN、P2×2継続。中央ledgerのT071開始可は誤り。T070 FAIL、T071/次Wave開始不可。`

### Round 2 central gate correction 独立再Review — 2026-08-09 — R11担当

- review target: migration provenance=`b75af1a` → central ledger gate correction=`7f60738a0dd1b3a9314cc3b115dae1173673358d`
- git evidence: `HEAD=origin/main=7f60738`、target commitは中央`spec-execution-ledger.md`のS11行だけを1行置換し、`git diff --check b75af1a..7f60738` PASS。本review ledgerの未commit reviewer追記はtarget commitへ混入していない
- independent regression: current Headで`SpecDispatchConsistencyTest`を再実行し`9/0/0/0`、skip 0、exit 0
- result: 中央S11行から誤った「T071開始可」を削除し、R2-P1-02の独立`VERIFIED_CLOSED`までT071・次Wave開始不可へ訂正したことを確認。R2-P1-01のV82永久欠番/S11=V83/S10=V84とV83不変契約も維持。新規P0/P1なし
- issue state: `R2-P1-02=OPEN / SPEC_CLARIFICATION_REQUIRED`、P2-01/02 OPENを維持。`P0=0 / P1=1 / P2=2`、T070=`FAIL`
- next task/Wave: 休憩配賦の発注者decision、決定表改訂、実装、休憩境界direct regression、独立再Reviewの順でR2-P1-02を閉鎖するまでT071・次Waveを開始しない
- central ledger転記用短文: `R11 central gate correctionはcurrent Head 7f60738でSpecDispatchConsistencyTest 9/0/0/0、skip 0。中央S11行をR2-P1-02 VERIFIED_CLOSEDまでT071/次Wave開始不可へ訂正済みと独立確認。P0=0/P1=1/P2=2、T070 FAILを維持。`

### Round 2 R2-P1-02 方式A発注者決定 — 2026-08-09 — 主担当

- decision: 発注者は方式A「休憩区間保存方式」を採用。1勤務日に複数区間を保存し、跨夜は勤務開始基準のoffsetで日付を曖昧にせず、`breakMinutes`は区間合計から導出する。法定内・時間外・休日・深夜は勤務区間と休憩区間のintersectionで算定する
- fail-closed contract: 重複、勤務区間外、開始≧終了、勤務時間全体超過を拒否する。既存の`breakMinutes`のみの行から架空区間を生成せず、区間不明の補正・承認が完了するまで再確定を拒否する
- spec delta: `requirements.md` R1.1、`design.md` §1/§5.1.1/§6、`tasks.md` T070 fix deltaへ決定表・保存形状・direct regressionを反映した
- implementation gate: 現行`V83`はR11正式decisionにより不変、V84はS10、V85〜V90は後続spec予約。休憩区間DDLを追加する追補migration versionが未割当のため、V83編集・予約外migration作成・既存`remarks`への代替保存は行わない
- proposed clarification: S11方式A追補として、S10/V84・S12〜S17/V85〜V90を侵食しない未使用version（候補V91）を正式割当する。割当後にV1/追補Flyway/H2 replay/engineer-schema-h2/MySQL smoke/entity/API/calculatorを同一deltaで実装する
- test contract: 深夜前/中/後、跨夜、複数休憩、0分、全時間休憩、重複、勤務区間外、開始≧終了、8時間/週40時間/22時境界、月次再集計、既存区間不明行をL1〜L3でdirect regressionする
- current state: `R2-P1-02=OPEN / MIGRATION_VERSION_CLARIFICATION_REQUIRED`。migration versionの発注者決定まで実装を停止し、T071・次Waveは開始しない

### Round 3 R2-P1-02方式A fix delta 独立再Review — 2026-08-09 — R11担当

- review target: delta=`3891c0e`→`5f362fc`（方式A実装）→`b65996f`（V91予約・文書同期）、committed Head=`b65996f`。検証はworktreeの未commit差分（dispatch V84 R5 WIP）を排除したisolated worktree `b65996f`で標準構成のまま実施（schema除外なし）
- independent evidence: `git diff --check 3891c0e..b65996f` PASS、V83 checksum不変、V82欠番、V91実在、S12〜S17=V92〜V97同期
- independent regression: attendance 21 class＋approval 3 class＋overtime＋integrity＋dispatch-consistency＝**171/0/0/0、skip 0、BUILD SUCCESS**。`FlywayAttendanceSchemaSmokeTest`（V83＋V91追補を実MySQLへ）**2/0/0/0**（shape・CHECK・UNIQUE・開始≧終了・sequence重複拒否を実assert）。`FlywayMigrationSmokeTest`（fresh V1→latest 全経路・V91含む）**2/0/0/0**。主担当の環境注記（dispatch WIPによるH2破損）はcommitted Headでは再現せず、標準schema構成でcontext init含め全てgreen
- result: `R2-P1-02=VERIFIED_CLOSED`、`R3-P2-01=VERIFIED_CLOSED`。新規P0/P1なし。P2×2（R2-P2-01 390px、R2-P2-02 paging）はOPEN継続でT071を止めない
- new note: `NOTE-R3-03` — `5f362fc`のV1 diffにdispatch S10 R5 reworkのテーブル（`t_contract_compliance_snapshot`等）が混入。committed treeはV1（新shape）＋V84（旧shape・IF NOT EXISTS）でgreen確認済みだが、dispatchレーンは自身のV1/V84 R5を本V1と整合する形でcommitする必要がある（統合担当への調整NOTE。attendance欠陥ではない）
- cross-cutting: migration PASS（V91 fresh/legacy両経路、V1統合、予約guard PASS、V83不変・V82欠番維持）。scope/security/状態は変更なし（R2-P1-03/04/05のVERIFIED_CLOSED維持、calculate()のconsumerは`AttendanceServiceImpl.java:338`のみ）。分整数モデル維持、8h/週40h/22時境界は休憩を挟んだ実労働で判定。UI/i18nは4バンドル同一9キー、breaks[0] prefillは未使用経路（データ消失経路なし）
- unverified: T071〜T074、L4全量、実ブラウザ（390px）、paging、dispatch V84 R5 merge後のtree（その時点でCI相当L4を1回推奨）、ATT-GATE-01〜06（release gate）
- overall verdict: **PASS（T070までの実装範囲）**。P0=0 / P1=0 / P2=2 / NOTE=1
- next task/Wave: **T071開始可**（R2-P1-02のVERIFIED_CLOSEDで開始不可条件が解消）。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 3方式A fix deltaはisolated worktreeのcommitted Head b65996fで171/0/0/0 skip 0、MySQL smoke 2/0/0/0、fresh全経路2/0/0/0を独立確認。R2-P1-02=VERIFIED_CLOSED、R3-P2-01=VERIFIED_CLOSED、新規P0/P1なし。P2×2 OPEN継続でT071を止めない。T071開始可、S11完了はT074/M後。NOTE-R3-03: attendance commitのV1へdispatch R5テーブル混入、統合担当へ調整。`

### Round 2 R2-P1-02 方式A fix delta — 2026-08-09 — 主担当

- version decision: 発注者（R11 §6固定手順の指示）により**V91**をS11方式A追補へ割当。`V91__attendance_break_intervals.sql`を実在させた。V91実在に伴い予約guard（`reserved <= latest`拒否）へ適合するため、S12〜S17の予約を**V92〜V97**へ繰り上げ、`README.md`予約表・`parallel-execution-plan.md`・S12〜S17各design/tasks・派工対話・copyable-conversations・`SpecDispatchConsistencyTest`（design.md正本連動）を同期した。R4-P1-01 fixture（`s10-r4-p1-01-v83-realized.properties`）はR4時点の履歴pinとして不変
- implementation: `t_employee_attendance_break`（V1統合baseline＋V91増分＋H2 2形状＋MySQL smoke）、entity/mapper（論理削除なし）、`AttendanceDayRequest.breaks`（時刻区間）、calculatorは`BreakInterval`のoffset検証（重複/区間外/開始≧終了/全体超過）と勤務区間intersectionで実労働・深夜を算定、`AttendanceServiceImpl`は時刻→offset変換（跨夜は+24h）、`assertBreakMinutesMatch`（**R3-P2-01の1択＝不一致は400拒否**）、既存区間不明行の再保存拒否と月次再集計拒否、削除時の区間削除、read DTOへ区間時刻を返却、UIは複数休憩区間入力、i18n 4バンドルへ9キー追加
- fail-closed boundary: 区間なし`breakMinutes > 0`は400（`breakMinutesMismatch`）、区間不明行が残る月の`refreshAggregate`は400（`breakUnknown`）、既存区間不明行の区間なし再保存は400（`breakUnknown`）、全時間休憩（合計=勤務区間）は許可、隣接区間は許可
- direct regression: `mvn -Dtest=<attendance系19 class + approval共通3 class> test`＝**135/0/0/0、skip 0、BUILD SUCCESS**（内訳: AttendanceCalculatorTest 21、AttendanceBreakIntervalServiceTest 6、AttendanceSchemaTest 6、Workflow 2、API 8、Resolver/Manager/FullAccess/Snapshot/Reopen 計8、MessageBundle 4、JS 1、UiContract 2、RoleNavigation 2、Approval 3 class 45、Overtime 30）。`SpecDispatchConsistencyTest 9/0/0/0`＋`MigrationScriptIntegrityTest 27/0/0/0`、`git diff --check` PASS
- mysql smoke: `FlywayAttendanceSchemaSmokeTest`はV83までFlyway適用後、V91追補DDLを実MySQLへ直接適用してshape・CHECK・UNIQUEを検証（2/0/0/0）。V84（S10/dispatch）は同laneの`FlywayDispatchComplianceSchemaSmokeTest`が検証するため本testの対象外。`FlywayEnvironmentEvidenceTest`（target 83）は不変で1/0/0/0。dispatch laneの並行WIP（V84未commit・Error 1295）に依存しない構成とした
- environmental note: 最終再実行時、dispatch laneの並行WIP（未commit）が`sql/schema-dispatch-compliance-h2.sql`のDROP欠落（m_workplace重複）でH2 contextを破損させていた。H2 context回帰12 classは`-Dspring.sql.init.schema-locations`で同schemaのみを除外した構成で**75/0/0/0、skip 0**を再確認（attendance実装内容は21:14の135/0/0/0と同一）。dispatch WIPのmerge後に標準構成での再実行を独立Reviewへ依頼する。V84/V91は番号順に適用されるため相互干渉なし
- issue state: `R2-P1-02=FIXED_BY_IMPLEMENTER / independent re-review requested`（calendar tier subconditionは既存VERIFIED維持、休憩配賦が今回のdeltaで解消）。`R3-P2-01`（R11新規P2、breakMinutesセル2択）は「不一致は400拒否」へ1択確定し実装済みのため`FIXED_BY_IMPLEMENTER / independent re-review requested`。`R2-P2-01`（390px実ブラウザ）、`R2-P2-02`（paging）は従前どおりOPEN
- base/head: review baseはcurrent merged `3891c0e`、本deltaのcommit（V91追補＋コード＋文書＋台帳）は本commit。V83 checksum不変、V82欠番不変、V84（S10）不変
- rollback: 本番未適用。本delta commitをrevertすればV91追補・calculator・service変更を戻せる。V91適用済みDBの復旧は不要（新規テーブルのみ追加）
- next Review handoff: R11担当はV91 fresh/legacy MySQL適用、`t_employee_attendance_break`制約、calculator境界（深夜前/中/後、跨夜、複数休憩、0分、全時間、重複、区間外、開始≧終了、8h/週40h/22時）、区間不明行、不一致400拒否、予約表V92〜V97を独立再実行する。R2-P1-02・R3-P2-01のVERIFIED_CLOSEDまでT071・次Waveを開始しない

### Round 3 ledger転記修正 — 2026-08-09 — 主担当（NOTE-R3-04/05対応）

- base/head: `758649e`（Round 3転記commit）上に本修正を追加。code変更なし
- fixes: §4「最新Review Packet」を現行状態（Head `758649e`=origin/main、base/headチェーンを`3891c0e`→`5f362fc`→`b65996f`→`758649e`へ更新、V91実在・V83不変・V82欠番・S12〜S17=V92〜V97、171/0/0/0/MySQL 2/0/0/0/fresh 2/0/0/0、R2-P1-02/R3-P2-01 VERIFIED_CLOSED、T071開始可、NOTE-R3-03引き継ぎ）へ全面更新（NOTE-R3-04）。§5「Requirements Trace」のverdict列を現行判定（T068/T069/T070 PASS、方式A VERIFIED_CLOSED、unverified列をR2-P2-01等へ移行）へ更新（NOTE-R3-05）
- issue state: `NOTE-R3-04=FIXED`、`NOTE-R3-05=FIXED`（次Reviewで確認）。`NOTE-R3-03`は統合担当OPEN継続、P2×2はOPEN継続でT071を止めない
- post-merge verification: dispatch V84 R5 merge（`b9b91f9`）後のtreeで標準構成の指定回帰を再実行し**171/0/0/0、skip 0**（H2 context含む）を確認。MySQL smokeは`FlywayAttendanceSchemaSmokeTest 2/0/0/0`、`FlywayEnvironmentEvidenceTest 1/0/0/0`、`FlywayDispatchComplianceSchemaSmokeTest 1/0/0/0`がPASS。ただし`FlywayMigrationSmokeTest` fresh経路のみdispatch新V84のtrigger作成（Error 1419、ses userにSUPERなし・binary logging有効）で失敗し、**NOTE-R3-06としてdispatchレーンへ引き継ぎ**（attendance欠陥ではない。V91はfresh経路のV84修正後に続けて適用される）
- verification: 文書整合のみ（`git diff --check` PASS）。code/testは変更していないためtest再実行なし
- ledger/central synchronization: 中央`spec-execution-ledger.md`S11行の実績・next actionへNOTE-R3-04/05修正を追記。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決

### Round 3フォローアップ 独立再Review — 2026-08-09 — R11担当

- review target: delta=`fc798be`（ledger §4/§5修正）＋`b9b91f9`（dispatch V84 R5 re-sync merge）＋`0ff7f2b`（post-merge検証記録・NOTE-R3-06登録）。worktree clean
- independent evidence: `git diff --check` PASS。§4最新Review Packet（NOTE-R3-04）と§5 Requirements Trace（NOTE-R3-05）が現行状態へ更新済みであることを読解確認 → 両件とも**FIXED（検証済み）**
- independent regression（current Head `0ff7f2b`、標準構成）: attendance 21 class＋approval 3＋overtime＋integrity＋dispatch-consistency＝**171/0/0/0、skip 0、BUILD SUCCESS**。`FlywayAttendanceSchemaSmokeTest` 2/0/0/0、`FlywayEnvironmentEvidenceTest` 1/0/0/0、`FlywayDispatchComplianceSchemaSmokeTest`（root）1/0/0/0。`FlywayMigrationSmokeTest`（共有fresh/legacy全経路）は**2 ERROR（Error 1419、V84 trigger、ses user SUPER不足）**を独立再現
- result: NOTE-R3-04/05 FIXED。NOTE-R3-06はdispatch帰属が正しいことを独立再現で確認（dispatch自身smokeはrootでPASS）。R2-P1-02/R3-P2-01のVERIFIED_CLOSEDに影響なし。新規P0/P1なし
- cross-cutting: attendance scopeはPASS維持。共有境界は**repo全体の`mvn test`（Docker有）が現HeadでRED**（FlywayMigrationSmokeTest×2、dispatch V84起因）。CI no-skip gateも連動fail見込み → 統合担当・dispatchレーンがV84修正（`log_bin_trust_function_creators`設定追加またはtrigger/function見直し）を最優先実施し、修正後にCI相当L4×1回とV91 fresh全経路を再検証する
- unverified: T071〜T074、L4全量、実ブラウザ（390px）、paging、V84修正後のfresh全経路＋CI相当L4、ATT-GATE-01〜06
- overall verdict: **PASS（T070までの実装範囲）維持**。P0=0 / P1=0 / P2=2 / NOTE=2（R3-03統合担当、R3-06 dispatch）
- next task/Wave: **T071開始可（条件付き）**。共有smokeがdispatch V84起因でREDの間は統合担当がdispatch修正を優先し、CI相当L4はV84修正後に実施。T071の実装自体はattendance scope内で検証済みのため開始を止めない（handbook §10）。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 3フォローアップ: NOTE-R3-04/05は§4/§5が現行状態へ更新済みを読解確認（FIXED）。NOTE-R3-06を独立再現——FlywayMigrationSmokeTestがError 1419（dispatch V84 trigger、ses user SUPER不足）で2 ERROR、dispatch自身smokeはrootで1/0/0/0 PASS、帰属はdispatchに正しい。attendanceは171/0/0/0、FlywayAttendanceSchemaSmokeTest 2/0/0/0、FlywayEnvironmentEvidenceTest 1/0/0/0でPASS維持。P0=0/P1=0/P2=2/NOTE=2。T071開始可（共有fresh経路のV84修正とCI相当L4はdispatch修正後に実施）。`

### Round 3フォローアップ転記確認 — 2026-08-09 — R11担当

- review target: delta=`0ff7f2b..3d6864b`（docs: Round 3フォローアップ独立Review記録）。code変更なし（ledger 2ファイルのみ、`git diff --check` PASS）。worktree clean、push済み
- result: 転記は前回判定と完全一致（§1現行判定、§2 Register、§11末尾、中央ledger S11行）。**新規P0/P1/P2/NOTEなし**
- note closures: `NOTE-R3-04=FIXED（検証済み）`、`NOTE-R3-05=FIXED（検証済み）`、`NOTE-R3-03=RESOLVED`（dispatch `b9b91f9`がV1不変のままV84 R5を整合commit済みであることを確認。commit message「content landed via parallel S11 commit 5f362fc; verified identical at HEAD」）。残るOPENは`NOTE-R3-06`（dispatch帰属、共有`FlywayMigrationSmokeTest` REDのまま）
- cross-cutting: attendance scope PASS維持（171/0/0/0、MySQL smoke 3系統greenは前roundの独立実行で確認済み、本roundは文書転記のみ）。共有境界はNOTE-R3-06としてdispatchへ引き継ぎ継続。V1不整合リスク（NOTE-R3-03）は解消
- unverified: T071〜T074、L4全量、実ブラウザ（390px）、paging、V84修正後のfresh全経路＋CI相当L4、ATT-GATE-01〜06
- overall verdict: **PASS（T070までの実装範囲）維持**。P0=0 / P1=0 / P2=2 / NOTE=1（R3-06 dispatch）
- next task/Wave: **T071開始可（条件付き維持）**。共有smoke REDの間は統合担当がdispatch V84修正を優先し、CI相当L4とV91 fresh全経路の再検証はV84修正後に実施。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 3フォローアップ転記確認: 3d6864bはledger 2ファイルのみ・code変更なし・git diff --check PASS。§1/§2/§11・中央S11行の転記は前回判定と完全一致。NOTE-R3-04/05=FIXED（検証済み）、NOTE-R3-06=OPEN（dispatch帰属）。NOTE-R3-03はdispatch b9b91f9がV1不変のままV84 R5を整合commitしたため解消。新規P0/P1/NOTEなし。attendance scope PASS維持、T071開始可（条件付き）、S11完了はT074/M後。`

### Round 3フォローアップ転記確認2 — 2026-08-09 — R11担当（収束完了）

- review target: delta=`3d6864b..867ade5`（docs: Round 3フォローアップ転記確認）。code変更なし（ledger 2ファイルのみ、`git diff --check` PASS）。worktree clean
- result: 転記は前回判定と完全一致（§1現行判定、§2 RegisterのNOTE-R3-03=RESOLVED、§11末尾、中央ledger S11行）。**新規P0/P1/P2/NOTEなし**
- convergence: 本delta（T070方式A・ledger収束）に関するReview対話は**収束完了**。同一判定の転記確認はこれ以上繰り返さない。次の独立ReviewはT071（休暇/approval統合）実装後、およびdispatch V84修正確認（NOTE-R3-06）
- unverified: T071〜T074、L4全量、実ブラウザ（390px）、paging、V84修正後のfresh全経路＋CI相当L4、ATT-GATE-01〜06
- overall verdict: **PASS（T070までの実装範囲）維持・収束**。P0=0 / P1=0 / P2=2 / NOTE=1（R3-06 dispatch）
- next task/Wave: T071開始可（条件付き維持）。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 3フォローアップ転記確認2: 867ade5はledger 2ファイルのみ・code変更なし・git diff --check PASS。§1/§2/§11・中央S11行の転記は前回判定と完全一致。NOTE-R3-03=RESOLVED、R3-04/05=FIXED、R3-06=OPEN（dispatch帰属）。新規指摘なし。attendance scope PASS維持、T071開始可（条件付き）。本deltaのReview対話は収束完了——次のReviewはT071実装後。`

### T071 completion — 2026-08-09 — 主担当実装記録

- base/head: current merged Head（T070 delta）上にT071を実装。V98は発注者割当（質問回答）により採用し、S12〜S17の予約をV99〜V104へ繰り上げて全派工資料を同期（前回V91と同じ手続き）
- version decision: `t_leave_ledger`を**V98**で適用（発注者V98割当＋S12〜S17=V99〜V104繰り上げ）。V1統合baseline・H2 2形状・MySQL smoke（`FlywayAttendanceSchemaSmokeTest`にV98 test追加）を同一deltaで同期
- implementation: `t_leave_ledger`（GRANT/CONSUME、残数=ΣGRANT−ΣCONSUME）、config `leave.balance.source`（internal=G6既定/external/未設定=判定不能finding）・`leave.balance.types`・`leave.sales-notification.types`、`LeaveMinutesCalculator`（全日=所定分・半休=所定の半分・時間休=時刻差、カレンダー未定義日は所定なし=0分）、`LeaveServiceImpl`（分計算・期間重複拒否・締め済み月拒否・残数両モード・取消=承認付き・付与・照会・scope）、`LeaveApprovalAdapter`（leave.request/leave.cancel：状態CAS・承認時残数CAS・月次leave_minutes反映・営業通知R2.3）、menu `myLeave`/`leaveManagement`＋`leave.*`権限seed（営業除外、design §5.3）、SecurityConfig（/leave管理3ロール、/my/leave要員）、本人/管理画面＋JS、i18n 4バンドル
- direct regression: 休暇系4 class（Service 9/Adapter 5/Flow統合 2/API 4）＝**20/0/0/0 skip 0**。全指定回帰（attendance 19＋approval 3＋overtime＋integrity＋dispatch-consistency＋休暇4）＝**191/0/0/0 skip 0、BUILD SUCCESS**。MySQL smoke `FlywayAttendanceSchemaSmokeTest` 3/0/0/0（V83/V91/V98）＋`FlywayEnvironmentEvidenceTest` 1/0/0/0。`git diff --check` PASS
- Demo: 申請（分計算・残数・重複・締め済み月）→承認（route・代理承認）→calendar反映（月次leave_minutes）→取消（残数戻し）→営業通知分岐（種別config）をH2で定向実測。外部正モードの不足許容も確認
- issue state: 新規issueなし。既存OPENはP2×2（390px実ブラウザ、paging）とNOTE-R3-06（dispatch）のみ
- ledger/central synchronization: `tasks.md` A2を`[x]`化、中央台帳S11行をT071完了/独立Review待ちへ更新。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決

### Round 4 R4-P1-01 fix delta — 2026-08-09 — 主担当

- review target: R11 Round 4独立Review（T071）で**R4-P1-01（P1: grant/balanceの法人・組織scope欠如によるIDOR）**、**R4-P2-01（P2: 差戻し後の再提出デッドエンド）**、NOTE-R4-01（route運用設定）、NOTE-R4-02（外部正モードの付与無効化）を指摘
- fixes:
  - **R4-P1-01**: `grant`は管理者=全件、HR=担当法人内のみ（`allowedHrLegalEntityIds`×対象日の要員snapshotの法人一致、不一致は404）。`balance`は管理者=全件、HR=担当法人内、マネージャー=組織scope（`hasFullAccess`先判定＋asOf）、他ロール403、要員不存在/所属不明/履歴ありNULLは404。grant行の`legal_entity_id`をsnapshotから設定（従来NULLのままだった）
  - **R4-P2-01**: `LeaveService.resubmit`＋`POST /api/my/leave/{id}/resubmit`＋本人UIの再提出ボタン（approval status=returned時のみ）。engineの`resubmit`へpayload/diffを委譲
  - **NOTE-R4-02**: `leave.balance.source`がinternal以外ではgrantを400拒否（`error.leave.grantExternalDisabled`）。未設定（判定不能）も同様にfail-closed
  - **NOTE-R4-01**: leave.request/leave.cancelのapproval routeはS07運用規約どおり管理者設定（route未設定はfail-closed＋config-gap通知）。運用手順として本台帳§9相当へ記録
- direct regression: 休暇系4 class **24/0/0/0 skip 0**（新規: HR他法人付与拒否・HR/manager残数閲覧scope・外部モード付与拒否・差戻し再提出）、全指定回帰 **195/0/0/0 skip 0、BUILD SUCCESS**、`git diff --check` PASS
- issue state: `R4-P1-01=FIXED_BY_IMPLEMENTER / independent re-review requested`（T072開始不可は維持）。`R4-P2-01=FIXED_BY_IMPLEMENTER`、`NOTE-R4-02=FIXED_BY_IMPLEMENTER`、`NOTE-R4-01=運用設定手順として記録済み`
- next Review handoff: R11担当はHR A→A許可/A→B 404、manager配下/外、履歴なし/NULL、外部モード付与拒否、差戻し→再提出を独立再実行し、R4-P1-01をVERIFIED_CLOSEDにするまでT072を開始しない

### Round 4 fix delta 独立再Review — 2026-08-09 — R11担当

- review target: fix delta=`85ca62ba`（R4-P1-01/R4-P2-01/NOTE-R4-02対応＋NOTE-R4-01記録）。worktree clean（当時）
- independent evidence: `git diff --check` PASS。独立実行（22 class）**全PASS**、leave 4 class = **24/0/0/0**（LeaveServiceTest 13＋Adapter 5＋Flow 2＋API 4）、attendance/approval/overtime/integrity全green、skip 0
- result: `R4-P1-01=VERIFIED_CLOSED`（grant: HR=`allowedHrLegalEntityIds`×対象日snapshot法人一致のみ・不一致404、管理者全件、`legal_entity_id`をsnapshotから設定。balance: 管理者全件/HR=担当法人（asOf今日）/マネージャー=組織scope（`hasFullAccess`先判定＋asOf）、要員不存在・所属不明・履歴ありNULLは404 fail-closed。test: HR他法人付与404＋担当内許可、HR/manager他法人残数閲覧404＋担当内許可を実assert）、`R4-P2-01=VERIFIED_CLOSED`（`resubmit`本人のみ・approval status=returned時のみ＋`POST /api/my/leave/{id}/resubmit`＋UI再提出ボタン＋4言語key、engineへpayload/diff委譲をtestでverify）、`NOTE-R4-02=FIXED`（`leave.balance.source`≠internalはgrantを400拒否、未設定含むfail-closed）、`NOTE-R4-01=記録済み`（leave.request/leave.cancel routeはS07規約どおり運用設定・fail-closed＋config-gap通知）
- new notes（cross-lane、attendance起因ではない）: `NOTE-R4-03` — `MessageBundleConsistencyTest` FAIL（scale-300 `0e29c555`の`detail.html`が参照する`project.detail.desc`キー欠落、repo L4/CI破壊）。`NOTE-R4-04` — `SpecDispatchConsistencyTest` FAIL（scale-300が`V101__remove_unimplemented_menu_routes.sql`を実在化し、S13=V100/S14=V101予約と衝突、予約表の再繰上げが必要）。両件とも統合担当へ引き継ぎ
- cross-cutting: scope/security PASS（grant/balanceも主体別scopeをserver-side解決）、状態機械/競合 PASS（resubmitはengineのroute再解決・CAS契約へ委譲）、migration/採番はattendance側PASS（V101衝突はNOTE-R4-04として統合担当）、i18n 4バンドル同キー
- unverified: T072〜T074、L4全量、実ブラウザ（390px）、paging（R2-P2-02）、NOTE-R3-06/4-03/4-04解消後のCI相当L4、ATT-GATE-01〜06
- overall verdict: **PASS（T071 fix delta）**。P0=0 / P1=0 / P2=2（R2-P2-01, R2-P2-02）/ NOTE=3（R3-06, R4-03, R4-04）
- next task/Wave: **T072（freee/provider sync）開始可**（R4-P1-01のP1解消で開始不可条件が消滅）。統合担当はNOTE-R4-03/4-04/NOTE-R3-06の3件を解消後、CI相当L4×1回を実行。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 4 fix delta再Review: 85ca62baを独立検証——grant/balanceは主体別scope（HR=法人・manager=組織・hasFullAccess先判定・404 fail-closed）をserver-side解決で適用しR4-P1-01 VERIFIED_CLOSED。resubmit（returned限定・engine委譲・UI/i18n）でR4-P2-01 VERIFIED_CLOSED、NOTE-R4-02 FIXED、NOTE-R4-01記録済み。leave 24/0/0/0、attendance/approval 22 class全green、skip 0。新規NOTE×2（cross-lane）: MessageBundleConsistencyTestのproject.detail.desc欠落（scale-300）とSpecDispatchConsistencyTestのV101予約衝突（scale-300採番）は統合担当へ。P0=0/P1=0/P2=2/NOTE=3。T072開始可。`

### Round 4 fix delta 再Review確認 — 2026-08-09 — R11担当（収束完了）

- review target: `85ca62ba..HEAD`（attendance code変更ゼロ。HEAD新規commitはdispatch T062系`39f0384c`/`07a4ff41`のみ、attendance scope外）。`git diff --stat 85ca62ba..HEAD`でattendance/leave関連パス・V91/V98 migration・leave UI・spec文書すべて空、`git diff --check` PASS
- result: 前回判定（Round 4 fix delta再Review）をそのまま維持。**新規P0/P1/P2/NOTEなし**。R4-P1-01/R4-P2-01=VERIFIED_CLOSED、NOTE-R4-02=FIXED、NOTE-R4-01=記録済み、R2-P2-01/02=OPEN（T074/M）、NOTE-R3-06（dispatch）・NOTE-R4-03/4-04（scale-300、統合担当）OPEN
- cross-cutting: attendance scope **PASS維持**（P0=0/P1=0/P2=2/NOTE=3、うちcross-lane 3件）。dispatch T062のcommitはattendance共有file（V91/V98、SecurityConfig、sidebar、4言語bundle）へ非干渉を差分で確認
- NOTE: Round 4 fix delta再Reviewの判定が本台帳§1へ未転記のままだった（R4-P1-01 FIXED_BY_IMPLEMENTER・T072開始不可の旧状態）。本roundで§1/§2/§3/§4/§10/§11・中央ledgerへ転記しcommitする（従来roundと同じ同期手順）
- unverified: T072〜T074、L4全量、実ブラウザ（390px）、paging、cross-lane NOTE×3解消後のCI相当L4、ATT-GATE-01〜06
- overall verdict: **PASS（T071 fix delta）維持・収束**。P0=0 / P1=0 / P2=2 / NOTE=3
- next task/Wave: **T072開始可（維持）**。統合担当はNOTE-R3-06/R4-03/R4-04の3件を解消後、CI相当L4×1回を実行。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 4 fix delta再Review確認: 85ca62ba..HEADはattendance変更ゼロ（dispatch T062のみ、diff --check PASS）。前回判定（R4-P1-01/R4-P2-01 VERIFIED_CLOSED、leave 24/0/0/0、T072開始可）を維持、新規指摘なし。台帳§1を現行判定へ転記commit済み。cross-lane NOTE×3（R3-06 dispatch、R4-03/04 scale-300）は統合担当OPEN。`

### T072 completion — 2026-08-10 — 主担当実装記録

- base/head: T071 fix delta `85ca62ba` → T072実装 `840539da`（rebase後。dispatch T063 `6d5e21f5`を取り込んだ後にpush済み、HEAD=origin/main）。**DDLなし**（予約外migration禁止・NOTE-R4-04でV99以降衝突中のため、cursor/結果は`m_system_config` JSON、外部レコードは既存`t_employee_attendance`のsource='freee'形状を設計上参照）
- implementation:
  - `AttendanceProvider` interface（design §3: push=承認/締め済みの冪等送信、pull=外部updated_at cursor差分取得）
  - `MockAttendanceProvider`（`attendance.sync.provider=mock`既定。payload hash冪等キーで重複送信→外部1件）
  - `FreeeAttendanceProvider`（`attendance.sync.provider=freee`。`FreeeIntegrationService.apiGet/apiPost`へ委譲 — OAuth/refresh共通基盤に401 refresh 1回・429 exponential backoff・timeout/5xx→503・4xx retryなし・Idempotency-Key/X-Correlation-IDヘッダー・秘密非ログを実装。実freee API形状は本番release gate（G4））
  - `AttendanceSyncService`（承認/締め済み月のみpush。pullは締め済み/承認済み月への外部更新を**拒否してfinding**＝`t_overtime_followup`へwarning_code='EXT_OVERWRITE_REJECTED'でUPSERT（UNIQUEで冪等）＋管理者/HRへ通知（dedupe key）。cursor・直近結果はm_system_config JSON（SYSTEM_MANAGED）。timezoneは`attendance.sync.timezone`（既定Asia/Tokyo、直書きしない）。CSV出力はCsvUtils（UTF-8 BOM、RFC4180、scope適用））
  - API: `POST /api/work-records/attendance/sync/run`（管理者/HRのみ）、`GET .../sync/status`（管理者/HR/マネージャー）、`GET .../sync/export-csv`（管理者/HR/マネージャー、scope適用）。管理画面（management.html）に同期カード＋JS、i18n 4バンドル
- 逸脱と根拠（ledger記録）: 締め済み/承認済み月への外部更新拒否の「finding」は、既存`t_overtime_followup`（時間外follow-upテーブル）へwarning_code='EXT_OVERWRITE_REJECTED'で記録する。理由: 本spec内でfindingの永続化先は同テーブルが唯一（design §5.1「判定結果をt_overtime_followupへ」）であり、新規テーブルは予約外migration禁止で作れない。warning_codeで種別を区別し、UNIQUE(engineer_id, period_month, warning_code)で冪等にする。テーブル名の意味論はhealth_action_status列をNULLのまま使わないことで保全する
- direct regression: 新規5 class **29/0/0/0 skip 0**（`FreeeIntegrationServiceApiTest` 7、`AttendanceSyncServiceTest` 8、`AttendanceSyncApiControllerTest` 7、`FreeeAttendanceProviderTest` 3、`MockAttendanceProviderTest` 3、`JsSyntaxCheckTest` 1）＋指定回帰 **165/0/0/0 skip 0**（attendance 19 class＋leave 4 class＋migration integrity 27）、`git diff --check` PASS。**唯一の既知FAIL: `MessageBundleConsistencyTest`の`project.detail.desc`（NOTE-R4-03、scale-300起因・統合担当OPEN。T072起因ではない）**
- Demo: sandbox（mock provider）でsync→再実行。同一payloadの再送は`duplicateSkippedCount=1`（外部1件）。締め済み月へ外部更新を流すと`rejectedCount=1`かつ`t_overtime_followup`へEXT_OVERWRITE_REJECTED行が永続化され、通知dedupe keyが発行されることを`AttendanceSyncServiceTest`で実測。CSV出力はBOM付きUTF-8・ヘッダー・承認/締め済みのみを実測
- issue state: 新規issueなし（T072起因のP0/P1/P2なし）。既存OPENはP2×2（390px実ブラウザ、paging）とNOTE×3（R3-06 dispatch、R4-03/04 scale-300、統合担当）
- rollback: 本番未適用。`840539da`をrevertすれば本deltaを戻せる。DDL変更なし・migration不変（V83/V91/V98 checksum維持）
- next Review handoff: R11担当は401 refresh 1回/429 backoff/timeout/4xx、重複送信で外部1件、部分失敗、締め済み月への外部更新拒否+finding、秘密の非ログ出力、営業/要員403・マネージャーrun 403・CSRFを独立再実行する。T073（B2）は独立Review後に着手可
- ledger/central synchronization: `tasks.md` B1を`[x]`化、中央台帳S11行をT072完了/独立Review待ちへ更新。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決

### Round 5 T072独立Review — 2026-08-10 — R11担当

- review target: delta=`840539da`（実装）→`82b8612c`（完了記録）。転記commit `42b80b30`（R4判定）は前回verdictどおり
- independent evidence: 独立実行（29 class）**214 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**。T072新規5 class = **28/0/0/0**（SyncService 8、SyncApi 7、FreeeProvider 3、MockProvider 3、FreeeIntegrationApi 7）。attendance/leave/approval/overtime/integrity全green
- result: **PASS（T072）**。P0=0/P1=0/P2=4/NOTE=3。検証PASSの要点: G6境界（承認/締め済みのみpush・read-only pull・締め済み/承認済み月への外部更新拒否→`EXT_OVERWRITE_REJECTED` finding（UNIQUE冪等）＋HR/管理者dedupe通知）、冪等push（payload SHA-256→Idempotency-Key、同一payload再送で外部1件、部分失敗は月別エラー分離）、共通基盤（401 refresh 1回・429 exponential backoff+jitter・timeout/5xx→503・4xx retryなし→400・Idempotency-Key/X-Correlation-ID・秘密非ログ（log capture test））、scope（管理者=全件/HR=法人SQL境界/マネージャー=組織scope、run=管理者/HR・status/CSV=マネージャー閲覧可、営業/要員403、CSRF）、CSV fallback（UTF-8 BOM・RFC4180・承認/締め済みのみ・scope適用）、config（provider enum・timezone・cursor・last-result、SYSTEM_MANAGED、同一tx mapper直読）
- new issues: `R5-P2-01`（P2: `attendance.sync.timezone`がdead constant。javadoc/完了記録の虚偽主張）、`R5-P2-02`（P2: pullがfetch後破棄で照合実体なし。T073の範囲と線引きして実装することが望ましい）
- cross-lane: NOTE-R4-03（`project.detail.desc`）は本Reviewで再現確認済み（scale-300起因・統合担当）。NOTE-R4-04（V101予約衝突）解消されず。NOTE-R3-06（dispatch V84 fresh経路）確認待ち
- overall verdict: **PASS（T072）**。P0=0 / P1=0 / P2=4（R2-P2-01, R2-P2-02, R5-P2-01, R5-P2-02）/ NOTE=3
- next task/Wave: **T073（B2 客先工数差異/通知）開始可**（R5-P2-02はT073の差異表示設計と線引きして実装することが望ましい）。cross-lane 3件（R3-06/R4-03/R4-04）は統合担当が解消後、CI相当L4×1回。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 Round 5 T072独立Review: 新規5 class 28/0/0/0＋attendance回帰214件（唯一のFAILはcross-lane NOTE-R4-03）skip 0。G6境界（承認/締め済みのみpush・read-only pull・締め済み外部更新拒否→EXT_OVERWRITE_REJECTED finding＋dedupe通知）、冪等push（同一payload外部1件）、401 refresh 1回/429 backoff/timeout 503/4xx retryなし/秘密非ログ、scope（管理者/HR法人/manager組織）・CSRF・CSV BOMを実assert。新規P2×2: R5-P2-01（attendance.sync.timezoneが未使用のdead code、javadoc/完了記録の虚偽主張）、R5-P2-02（pullがfetch後破棄で照合実体なし）。P0=0/P1=0/P2=4/NOTE=3。T073開始可、cross-lane 3件は統合担当。`

### Round 5 T072 R5-P2-01/02 fix delta — 2026-08-10 — 主担当

- base/head: `82b8612c` → fix `c750fcb3`（rebase後、HEAD=origin/main、push済み）
- fixes:
  - **R5-P2-01**: `tenantZone()`（`attendance.sync.timezone`をmapper直読みで解決、既定Asia/Tokyo、不正値はwarn＋fallback）＋`normalizeUpdatedAt()`（zone付きISOはOffsetDateTime→Instant正規化、zoneなしはtenant timezoneで解釈→Instant、不正値は原文維持）を実装し、cursorの比較・保存を正規化値へ統一。`CONFIG_TIMEZONE`のdead constant解消。javadoc/完了記録の主張が実装と一致
  - **R5-P2-02**: pullで外部レコードと本システム日次（source=manual/system）を照合し、`matchedCount`/`diffCount`/`unmatchedCount`と差異サンプル（最大20件、`sourceExternalId`/`engineerId`/`workDate`/外部値/内部値）を`AttendanceSyncResultDto`へ追加。DB登録はしない（read-only）。syncAllも照合結果を引き継ぎ。status API・管理画面UI（一致/差異/該当なし＋差異一覧）で表示。T073（客先工数差異）とは別物（freee照合 vs 客先工数差異）として線引きをledgerへ明記
- direct regression: 新規5 class＋照合/timezone追加分 **31/0/0/0 skip 0**（SyncService 10、SyncApi 7、FreeeProvider 3、MockProvider 3、FreeeIntegrationApi 7、JS 1）＋指定回帰 **136/0/0/0 skip 0**（attendance 19 class＋leave系＋migration integrity 27）、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当）
- issue state: `R5-P2-01=FIXED_BY_IMPLEMENTER / independent re-review requested`、`R5-P2-02=FIXED_BY_IMPLEMENTER / independent re-review requested`。T073開始可（R11判定）を維持
- rollback: 本番未適用。`c750fcb3`をrevertすれば本deltaを戻せる。DDL変更なし
- next Review handoff: R11担当はtimezone正規化（zoneなし/zone付き/不正値）と照合の一致/差異/該当なし集計・差異サンプル上限・read-only不変を独立再実行し、R5-P2-01/02をVERIFIED_CLOSEDにする。T073（B2）は独立再Review後に着手可

### Round 5 fix delta再Review — 2026-08-10 — R11担当

- review target: delta=`c750fcb3`（R5-P2-01 timezone実装＋R5-P2-02 照合実体）＋`ba5625f3`（転記）
- independent evidence: 独立実行（29 class）**216 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**、BUILD SUCCESS。`AttendanceSyncServiceTest` 10/0/0/0（+2: timezone正規化・照合集計）
- result: `R5-P2-01=FIXED（検証済み）`（`tenantZone()` mapper直読・既定Asia/Tokyo・不正値fallback+warn、`normalizeUpdatedAt()` zoneなし→tenant tz解釈/zone付き→Instant正規化、test: UTC設定で"2026-08-11T10:00:00Z"、"+09:00"→Z正規化を実assert）、`R5-P2-02=FIXED（検証済み）`（`reconcile()` 一致/差異/該当なし集計・差異サンプル上限20・read-only、test実assert）
- new issue: **`R5-P1-01`（P1・original head `840539da`発見）** — `syncPull`（`AttendanceSyncServiceImpl.java:117-155`）にcaller scopeが無い。push/CSVは`monthsForScope`を適用するが、pullは全外部レコードを対象に: ①`rejectExternalUpdate`（:140）が**任意法人の締め済み/承認済み月へfinding書込＋通知**、②`reconcile`（:145）が**他法人要員の日次勤怠PII**（出退勤・各分・engineerId・workDate）を結果DTOへ返却。**HR-Aが法人Bの要員データをpull可能**。R4-P1-01と同じ根本原因クラス（per-engineer操作にserver-side scopeなし）がpull経路に残存。前回R5でmonthsForScope（push/CSV）のみ確認しpullを失念（process miss）
- cross-cutting: scope/security **FAIL**（pull経路のみscope漏れ。push/CSV/statusは正）。timezone/照合 PASS（P2両件実装・test済み）。回帰は全attendance/leave/approval/sync class green、skip 0
- overall verdict: **FAIL: open blockers = attendance-leave-overtime-compliance-R5-P1-01**。P0=0 / P1=1 / P2=4（R2-P2-01, R2-P2-02, R5-P2-01, R5-P2-02）/ NOTE=3（cross-lane）
- next task/Wave: **T073開始不可**。R5-P1-01のfix delta（syncPullへHR法人/管理者のscope境界を適用し、reject/reconcileを同一境界に統一、HR A→A処理/A→B skipのfixture追加）とdirect regressionを提出のうえ、独立VERIFIED_CLOSEDまでT073以降を開始しない。P2×2（R5-P2-01/02）は本deltaで閉鎖済み
- central ledger転記用短文: `R11 Round 5 fix delta再Review: c750fcb3のtimezone実装（tenantZone+normalizeUpdatedAt、test実assert）と照合実体（reconcile一致/差異/該当なし＋差分サンプル、read-only）を検証しR5-P2-01/02 FIXED。216件中FAILはcross-lane NOTE-R4-03のみ。ただし新規P1: R5-P1-01——syncPull（117-155）がcaller scopeなしで全外部レコードを処理し、HRが他法人要員の締め済み拒否finding書込と照合PII（出退勤・分）を取得可能（R4-P1-01と同根、original head 840539da由来・R5でpullを失念したprocess miss）。T073開始不可、R5-P1-01 VERIFIED_CLOSEDまで。`

### Round 5 R5-P1-01 fix delta — 2026-08-10 — 主担当

- base/head: `ba5625f3` → fix `1582d96`（rebase後、HEAD=origin/main、push済み）
- fixes: **R5-P1-01** — `pullScopeEngineerIds(YearMonth)`を追加（管理者=null（全件）、HR=`AttendanceScopeResolver.allowedHrEngineerIds(userId, 対象月末)`、マネージャー=`hasFullAccess()`先判定＋`allowedEngineerIds(対象月末)`、営業・要員は403）。`syncPull`で各レコードのengineerIdを解決後、要員未解決またはscope外のレコードは**reject/reconcileともskip**（他法人要員へのfinding書込・通知・PII返却を防ぐ）。reject/reconcileはscope内レコードのみ実行
- direct regression: sync系2 class **19/0/0/0 skip 0**（SyncService 12＝R5-P1-01のHR A→A処理/A→B skip・管理者全件fixture 2件含む、SyncApi 7）＋指定回帰 **199/0/0/0 skip 0**（attendance 19 class＋leave系＋approval共通＋migration integrity＋provider matrix）、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当）
- issue state: `R5-P1-01=FIXED_BY_IMPLEMENTER / independent re-review requested`（T073開始不可は維持）。`R5-P2-01=FIXED（検証済み）`、`R5-P2-02=FIXED（検証済み）`
- rollback: 本番未適用。`1582d96`をrevertすれば本deltaを戻せる。DDL変更なし
- next Review handoff: R11担当はHR A→A処理/A→B skip（finding書込なし・PII非返却）、管理者全件、マネージャー組織scope、要員未解決skip、営業/要員403を独立再実行し、R5-P1-01をVERIFIED_CLOSEDにする。T073（B2）は独立再Review後に着手可

### R5-P1-01 fix delta再Review — 2026-08-10 — R11担当

- review target: delta=`1582d96`（syncPull caller scope）＋`a955aa3`（転記）
- independent evidence: 独立実行（29 class）**218 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**、BUILD SUCCESS。`AttendanceSyncServiceTest` **12/0/0/0**（+2: HR scope・管理者全件）
- result: `R5-P1-01=VERIFIED_CLOSED` — `pullScopeEngineerIds`（管理者=全件、HR=`allowedHrEngineerIds`月末asOf、マネージャー=組織scope・hasFullAccess先判定、その他403）を適用し、要員未解決・scope外レコードはreject/reconcile前にskip（`AttendanceSyncServiceImpl.java:123,141-149`）。test: HR-Aが自法人締め済み更新のみ拒否（rejectedCount=1）、他法人要員のfinding不書込（findingB=null）、管理者は全要員処理を実assert。read-only・PII/finding漏れは解消
- new issue: **`R5-P2-03`（P2）** — cursorがscope外レコードでも前進する。`maxUpdatedAt`（:133-137）はscope skip（:146-149）より前に全レコードで更新され、単一グローバルcursor（`attendance.sync.freee.cursor`）へ保存（:162-163）。多法人DBでHR-Aが先にpullすると、HR-B配下要員のレコード分もcursorが進み、**HR-Bの後続pullでは該当レコードを取得できず、HR-B配下の締め済み月外部更新の拒否・照合が黙ってスキップされる**（「黙って無視」になる）。最小修正: ①cursorをscope内レコードだけで前進、②legal entity別cursor keyへ分割、③pullをグローバル/system principalで実行し結果表示のみscope——のいずれかを決定表・設計で確定して実装
- cross-cutting: scope/security **PASS**（R5-P1-01解消。push/CSV/pull/statusすべて主体別境界が一致）。cursor/拒否の完全性 **P2**（多法人でpull順に依存する拒否漏れ）。回帰は全sync/attendance/leave/approval green、skip 0
- overall verdict: **PASS（T072 fix delta）**。P0=0 / P1=0 / P2=3（R2-P2-01, R2-P2-02, R5-P2-03）/ NOTE=3（cross-lane）
- next task/Wave: **T073（B2 客先工数差異/通知）開始可**。R5-P2-03（cursorとscope外レコードの扱い）はT073着手時に、pullのグローバル/スコープ運用方針と合わせてfixを推奨（P2のため開始を止めない）。cross-lane 3件（R3-06/R4-03/R4-04）は統合担当が解消後、CI相当L4×1回。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 R5-P1-01 fix delta再Review: 1582d96を独立検証——pullScopeEngineerIds（管理者全件/HR法人/manager組織・asOf）＋scope外skipでreject/reconcile母集団を統一し、HR-A→A拒否/B finding不書込・管理者全件を実assert。R5-P1-01 VERIFIED_CLOSED。新規P2: R5-P2-03——cursorがscope外レコードでも前進し、多法人で後続HRの締め済み拒否・照合が黙ってスキップされ得る（:133-137 vs :146-149の順序）。218件中FAILはcross-lane NOTE-R4-03のみ。P0=0/P1=0/P2=3/NOTE=3。T073開始可。`

### Round 5 R5-P2-03 fix delta — 2026-08-10 — 主担当

- base/head: `a955aa3` → fix `3fce6d78`（rebase後、HEAD=origin/main、push済み）
- fixes: **R5-P2-03** — Reviewの3択から**②（legal entity別cursor key）**を採用。cursor keyを`attendance.sync.freee.cursor.le.<legalEntityId>`へ分割（管理者用のグローバルkeyは維持しつつ法人別も保存。SYSTEM_MANAGED判定はprefix `attendance.sync.freee.cursor.le.`で拡張）。`syncPull`は対象法人（管理者=全法人、HR=`allowedHrLegalEntityIds`、マネージャー=組織scope要員の属する法人）のcursorの最小値でfetchし、各レコードは所属法人（month行→要員の現在所属組織）を解決してscope判定。cursor前進は処理したレコードの法人ごと（`nextCursorByLegalEntity`）で、他法人のcursorは不変。`AttendanceScopeMapper.selectAllLegalEntityIds()`・`AttendanceScopeResolver.allLegalEntityIds()`を追加。design.md §5.4へ運用方針を明記
- direct regression: sync系2 class **20/0/0/0 skip 0**（SyncService 13＝R5-P2-03 fixture含む、SyncApi 7）＋指定回帰 **219/0/0/0 skip 0**（attendance 19 class＋leave系＋approval共通＋migration integrity＋provider matrix＋SystemConfigApi）、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当）
- issue state: `R5-P2-03=FIXED_BY_IMPLEMENTER / independent re-review requested`。`R5-P1-01=VERIFIED_CLOSED`、`R5-P2-01/02=FIXED（検証済み）`維持
- rollback: 本番未適用。`3fce6d78`をrevertすれば本deltaを戻せる。DDL変更なし（m_system_configの動的キーのみ）
- next Review handoff: R11担当はHR-A→HR-B順序のpull（法人B cursor不変→後続拒否到達）、管理者全法人、マネージャー組織scope要員の法人、法人別cursorのSYSTEM_MANAGED非表示を独立再実行し、R5-P2-03をVERIFIED_CLOSEDにする。T073（B2）は独立再Review後に着手可

### R5-P2-03 fix delta再Review — 2026-08-10 — R11担当

- review target: delta=`3fce6d78`（法人別cursor実装）＋`9be5e5c2`（転記）。design.mdにも決定を記録
- independent evidence: 独立実行（29 class）**219 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**、BUILD SUCCESS。`AttendanceSyncServiceTest` **13/0/0/0**
- result: `R5-P2-03=VERIFIED_CLOSED` — 法人別cursorキー（`attendance.sync.freee.cursor.le.<id>`、旧グローバルkeyはfallback保持）を実装。`pullScopeLegalEntityIds`（管理者=全法人/HR=担当法人/マネージャー=組織scope要員の法人、hasFullAccess先判定）→ fetchは対象法人cursorの最小値、レコードは`legalEntityIdOf`（月次snapshot→要員組織の法人）で解決しscope外法人はskip・cursor不変、処理済み（法人cursor以前）はskip、処理したレコードの法人だけcursor前進・保存は変化した法人のみ。動的cursor keyはSYSTEM_MANAGED prefix管理。test: HR-A先pull→法人B cursor不変（行不存在）→HR-B後続pullでBレコード再取得（pulledCount=1）・締め済み拒否実行（rejectedCount=1）を実assert
- cross-cutting: 締め済み拒否の完全性 **PASS**（pull順・法人間で漏れなく到達。黙って無視しない契約が法人別cursorで成立）、scope/security **PASS**（R5-P1-01境界を維持、engineer scope＋LE scopeの二重確認）、config/cache **PASS**（動的keyはmapper直読/直書でcache非依存、SYSTEM_MANAGED prefixで管理者編集不可）、回帰全green、skip 0
- overall verdict: **PASS（T072完結）**。P0=0 / P1=0 / P2=2（R2-P2-01, R2-P2-02）/ NOTE=3（cross-lane）
- next task/Wave: **T073（B2 客先工数差異/通知）開始可（維持）**。R5-P2-03の閉鎖でpull運用方針（法人別cursor）が確定済み。cross-lane 3件（R3-06/R4-03/R4-04）は統合担当が解消後、CI相当L4×1回。次spec/次WaveはS11完了（T074/M、L4）後
- central ledger転記用短文: `R11 R5-P2-03 fix delta再Review: 3fce6d78を独立検証——法人別cursor（cursor.le.<id>、旧key fallback）＋scope法人集合＋レコード別LE解決＋処理法人のみcursor前進を読解し、HR-A先pull→法人B cursor不変→HR-B後続pullでB拒否実行を実assert。設計決定（design.md）も記録済み。219件中FAILはcross-lane NOTE-R4-03のみ、skip 0。R5-P2-03 VERIFIED_CLOSED、新規指摘なし。P0=0/P1=0/P2=2/NOTE=3。T073開始可。`

### T073 completion — 2026-08-10 — 主担当実装記録

- base/head: T072完了 `9be5e5c` → T073実装 `5c34db26`（rebase後、HEAD=origin/main、push済み）。**DDLなし**（予約外migration禁止。理由保存は`m_system_config` JSON）
- implementation:
  - `AttendanceDiscrepancyService`/`Impl`: read-only比較DTO（雇用勤怠`t_attendance_month.worked_minutes` vs 契約工数`t_work_record.actual_hours×60`をengineer別に月次比較、稼働中/終了契約のみ、`contract_minutes`合算）。閾値`attendance.discrepancy.threshold-minutes`（既定480分、不正値は480へfallback）。`overThreshold = |diff| >= threshold`（R4.1境界）。理由確認は`m_system_config` JSON（`attendance.discrepancy.confirmed`、closing.confirmed-months前例。SYSTEM_MANAGED）。scopeはdesign §5.3（管理者=全件/HR=法人（`allowedHrLegalEntityIds`×snapshot法人）/マネージャー=組織scope（hasFullAccess先判定）、営業・要員は既存SecurityConfigの403）。`pendingWarnings`はscheduler principal相当（全件・scope非依存）で閾値超過かつ未確認のみ
  - API: `GET /api/work-records/attendance/discrepancy?month=`（一覧）、`POST .../discrepancy/confirm`（理由保存。空理由400・500文字超400）
  - 通知: `NotificationGenerateService.attendanceDiscrepancyWarning()`（日次バッチ`generateAll()`へ追加。管理者/HRへdedupe key `ATT_DISCREPANCY:{engineerId}:{month}` で冪等）
  - UI: management.htmlに差異カード（要員/勤怠/契約/差異/状態/理由/操作）＋JS（確認ボタン→理由prompt→POST）。i18n 4バンドル
- 逸脱と根拠（ledger記録）: 理由保存の永続化先は`m_system_config` JSON（`attendance.discrepancy.confirmed`）。理由: (1) 新規テーブルは予約外migration禁止（NOTE-R4-04でV99以降衝突）、(2) work record系テーブル（t_work_record.remarks等）への書込みは「WorkRecordServiceImplの金額計算・請求ロジックへの接続」（R4.2）と見なされるため避ける、(3) closing.confirmed-monthsのJSON運用前例に従う
- direct regression: 新規2 class **13/0/0/0 skip 0**（Service 8: 差異計算・閾値境界（480ちょうど超過/479範囲内）・理由保存で請求金額不変（billing_amount・actual_hoursをSQLで実測）・空理由400・営業403・HR担当法人/他法人404・pendingWarnings；API 5: 営業403/要員403/管理者一覧/マネージャー確認/CSRF）＋指定回帰 **232/0/0/0 skip 0**（attendance全系＋sync系＋leave系＋approval共通＋migration integrity＋provider matrix＋SystemConfigApi）、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当）
- Demo: 8h差異（1200分）を確認して理由保存、保存前後の`billing_amount`・`actual_hours`がSQLで同一であることを`AttendanceDiscrepancyServiceTest`で実測（R4.2「請求金額を自動変更しない」）
- issue state: 新規issueなし（T073起因のP0/P1/P2なし）。既存OPENはP2×2（390px実ブラウザ、paging）とNOTE×3（cross-lane、統合担当）
- rollback: 本番未適用。`5c34db26`をrevertすれば本deltaを戻せる。DDL変更なし（m_system_configの動的キーのみ）
- next Review handoff: R11担当はread-only不変（理由保存前後のbilling_amount/actual_hours同一）、閾値境界（480-1/480/480+1分）、scope（HR法人/マネージャー組織/営業403）、通知の冪等（dedupe key）、pendingWarnings（scheduler相当）を独立再実行する。T074（M）は独立Review後に着手可
- ledger/central synchronization: `tasks.md` B2を`[x]`化、中央台帳S11行をT073完了/独立Review待ちへ更新。本sectionのprovenance commitは`git log -1 -- review-ledger.md`で解決

### Round 6 T073独立Review — 2026-08-10 — R11担当

- review target: delta=`5c34db26`（実装）＋`11398d97`（完了記録）
- independent evidence: 独立実行（31 class）**232 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**、BUILD SUCCESS。T073新規 **13/0/0/0**（Service 8、API 5）
- result: **PASS（T073）**。P0=0/P1=0/P2=3/NOTE=5。検証PASSの要点: R4.1比較（worked_minutes vs actual_hours×60、稼働中/終了契約のみ、月重複判定、engineer別合算）、**R4.2非連動（理由確認はm_system_config JSONのみ、testで保存前後のbilling_amount・actual_hoursがSQL同一を実assert）**、閾値境界（480ちょうど=超過・479以内=超過なしを両方向実測）、scope/security（list管理者全件/HR法人/manager組織、confirm assertScope他法人404、営業/要員403、CSRF）、通知（threshold超過かつ未確認のみ、dedupe key冪等、確認済み除外）、i18n 19キー×4バンドル
- new issues: `R6-P2-01`（P2: list()がSQL取得後にJava filterでscope適用。platform-invariants §2.2違反。機能的漏れなし）、`R6-NOTE-01`（閾値境界の向きがdesign.mdに未記載。traceability欠落）、`R6-NOTE-02`（pendingWarningsは全管理者/HRへ通知。§2.4整合・運用判断として明記）
- overall verdict: **PASS（T073）**。P0=0 / P1=0 / P2=3（R2-P2-01, R2-P2-02, R6-P2-01）/ NOTE=5（cross-lane 3＋R6 2）
- next task/Wave: **T074（M 回帰/法務受入）開始可**。R6-P2-01（SQL境界）はT074/MのL4前にfix推奨（P2のため開始を止めない）。cross-lane 3件（R3-06/R4-03/R4-04）は統合担当が解消後、T074のL4全量（mvn test、fresh/legacy MySQL、desktop/390px browser、法務fixture golden file）を実行。次spec/次WaveはS11完了後
- central ledger転記用短文: `R11 Round 6 T073独立Review: 新規13/0/0/0＋回帰232件（唯一FAILはcross-lane NOTE-R4-03）skip 0。R4.2非連動（理由保存前後でbilling_amount/actual_hours不変をSQL実測）、閾値境界480=超過/479=以内、scope（HR法人・manager組織・confirm 404）、通知冪等（dedupe key・確認済み除外）、CSRFを実assert。新規P2: R6-P2-01——list()がSQL取得後にJava filterでscope適用（platform-invariants §2.2違反、機能的漏れなし）。NOTE×2（閾値境界の設計未記載、scheduler通知の宛先運用）。P0=0/P1=0/P2=3/NOTE=5。T074（M）開始可。`

### R6-P2-01 fix delta再Review — 2026-08-10 — R11担当

- review target: delta=`62e3d319`（SQL境界scope適用＋閾値境界の設計記録）＋`ef4ce724`（転記）
- independent evidence: 独立実行（31 class）**234 tests / 1 failure（既知NOTE-R4-03のみ）/ 0 errors / 0 skipped**、BUILD SUCCESS。`AttendanceDiscrepancyServiceTest` **10/0/0/0**（+2: HR・managerのSQL境界除外）
- result: `R6-P2-01=VERIFIED_CLOSED` — list()が`query.in(AttendanceMonth::getEngineerId, scopedEngineerIds)`（空集合は`List.of(-1L)` sentinel）でSQL境界適用（platform-invariants §2.2準拠）。test: HRのlistは他法人month行がSELECT自体から除外（1件のみ）、managerは他組織要員がSQL境界で除外を実assert。`R6-NOTE-01=CLOSED（設計記録済み）` — design.mdに「480ちょうど=超過（確認・通知対象）」＋fixture 479/480/481を追記。`R6-NOTE-02=記録継続`（運用方針として台帳に明記、§2.4整合）
- cross-cutting: scope/security **PASS**（list/confirm/pendingWarningsすべて主体別境界が一致、SQL境界含む）、閾値/非連動 **PASS**（境界の向きが設計に固定、billing不変は既検証）、回帰全green、skip 0
- overall verdict: **PASS（T073完結）**。P0=0 / P1=0 / P2=2（R2-P2-01, R2-P2-02）/ NOTE=4（cross-lane 3＋R6-NOTE-02）
- next task/Wave: **T074（M 回帰/法務受入）開始可**。T074でL4全量（mvn test、fresh/legacy MySQL、desktop/390px browser Demo、法務fixture golden file）を実施し、cross-lane 3件（R3-06/R4-03/R4-04）は統合担当解消後にCI相当L4×1回。S11完了後、次spec（S12 staffing）解放。ATT-GATE-01〜06は本番release gateとして継続管理
- central ledger転記用短文: `R11 R6-P2-01 fix delta再Review: 62e3d319を独立検証——list()がquery.in＋空集合-1 sentinelでSQL境界適用、HR/managerのscope外month行がSELECTから除外されることを実assert。design.mdへ閾値境界（480ちょうど=超過、479/480/481 fixture）を記録。234件中FAILはcross-lane NOTE-R4-03のみ、skip 0。R6-P2-01 VERIFIED_CLOSED、R6-NOTE-01 CLOSED、新規指摘なし。P0=0/P1=0/P2=2/NOTE=4。T074（M）開始可、S11完了後にS12解放。`

### Round 6 R6-P2-01 fix delta — 2026-08-10 — 主担当

- base/head: `11398d9` → fix `62e3d31`（rebase後、HEAD=origin/main、push済み）
- fixes:
  - **R6-P2-01**: list()のscopeをSQL境界へ移行。`LambdaQueryWrapper.in(AttendanceMonth::getEngineerId, scopedEngineerIds)`で適用し、空集合は`List.of(-1L)` sentinelでDB側0件（platform-invariants §2.2）。pendingWarnings（scheduler相当・全件）は対象外
  - **R6-NOTE-01**: design.md §5.4へ閾値境界を追記（`overThreshold := |雇用勤怠 − 契約工数| >= threshold`、既定480分、ちょうど=超過、fixture 479=以内/480=超過/481=超過）
  - **R6-NOTE-02**: 運用方針としてledger §11へ明記（scheduler principal=全件、§2.4「宛先指定通知に組織条件を重ねない」整合。宛先粒度はrelease gateの運用判断）
- direct regression: 新規2 class **15/0/0/0 skip 0**（Service 10＝R6-P2-01のHR他法人除外・マネージャー他組織除外fixture 2件含む、API 5）＋指定回帰 **234/0/0/0 skip 0**（attendance全系＋sync系＋leave系＋approval共通＋migration integrity＋provider matrix＋SystemConfigApi）、`git diff --check` PASS。唯一の既知FAILはNOTE-R4-03（cross-lane・統合担当）
- issue state: `R6-P2-01=FIXED_BY_IMPLEMENTER / independent re-review requested`、`R6-NOTE-01=対応済み（design追記）`、`R6-NOTE-02=対応済み（運用方針明記）`。T074開始可（R11判定）を維持
- rollback: 本番未適用。`62e3d31`をrevertすれば本deltaを戻せる。DDL変更なし
- next Review handoff: R11担当はHR他法人除外・マネージャー他組織除外がSELECT自体で行われること（SQL境界）を独立再実行し、R6-P2-01をVERIFIED_CLOSEDにする。T074（M）は独立再Review後に着手可
