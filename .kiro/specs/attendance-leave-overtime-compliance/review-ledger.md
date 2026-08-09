# Review Ledger — 雇用勤怠・休暇・時間外労働

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `attendance-leave-overtime-compliance` |
| handbook | `v2.0` |
| state | `FIX / REVIEW` |
| base | `5e29f39c96da85b29a0fe881326d979896a595d0` |
| head | T070 code Head=`cc7c15c60bc26bf7b19fbca7759b6e65f572a725`、evidence/current merged Head=`4789c192a733d2d64b13c9941ab53e3780aefbe9`（`HEAD=origin/main`、worktree clean） |
| merge | `cc7c15c`、証跡同期`4789c19`、T068/V83=`b327b1b`、T069=`d395797`はいずれもcurrent `origin/main`の祖先。V82はtreeに存在せず、V83の本番適用有無は未検証 |
| latest review | `R11 Round 2 / T070独立Review 2026-08-09` |
| verdict | `FAIL`。T070にP1×4、V82前にV83がmainへ入ったmigration順序にP1×1。R1-P2-01は現行GitとPacketが再び不一致のため`OPEN`のまま。T070の実ブラウザDemoはP2 |
| issue count | `P0=0 / P1=5 / P2=3 / NOTE=0` |
| next action | 元の実装対話でR2-P1-01〜05を修正し、再現test＋直接回帰（共有scope/migrationはL3）を提出する。T071はR2-P1-02〜04の共有calendar/scope契約がVERIFIED_CLOSEDになるまで開始しない。R1-P2-01とR2-P2-01〜02だけでは次taskを止めない |

本台帳は、T067〜T069のtask実装とその証拠をappend-onlyで管理する。T068はDDL/entity/H2/smoke、T069はcalculator/asOf協定解決/fail-closed入力の実装を含むが、V83のmerge/applyはV82後とする。

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| attendance-leave-overtime-compliance-R1-P2-01 | P2 | handbook §9 Base/Head/merge固定 | `review-ledger.md:10-16,40-50` | `HEAD=origin/main=4789c19`かつ`b327b1b`が祖先なのに、Packet/台帳がV83とT070を未mergeと記録 | Review範囲・migration配備判断の誤認 | code Head、evidence Head、merged/apply状態を実Gitと環境証拠で分離 | `git rev-parse`、`merge-base --is-ancestor`、tree内V82/V83 | OPEN | — | Round 2でFIXEDを不受理 |
| attendance-leave-overtime-compliance-R2-P1-01 | P1 | design §1、tasks T068 migration順、handbook §4 | `design.md:5`; `tasks.md:33`; `src/main/resources/db/migration/V83__attendance_leave_overtime_compliance.sql:1` | V81 DBへcurrent mainを配備するとV82不在のままV83が適用対象になる | 後日V82追加時にout-of-order/validate失敗し得る | current mainを配備停止し、V82を先に取り込む。V83適用済み環境があれば順方向の再採番/復旧計画を固定 | V81→V82→V83 legacy/fresh、適用済み環境inventory | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P1-02 | P1 | R1.1/R1.2/R3.1/R5、design §2/§5.1、overtime-rules §1.2 | `AttendanceServiceImpl.java:276-301,304-321` | 所定480分の通常日に09:00〜23:00、休憩0分を本人保存 | 法定時間外・深夜が0になり36協定判定入力を誤る | 勤務日asOfのcalendarを解決するAttendanceCalculatorで法定内/外・所定/法定休日・深夜を分計算し、clientのworkTypeを法定区分の正にしない | 8h境界、22時境界、跨夜、所定/法定休日、calendar NULL/0、週40h | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P1-03 | P1 | T070 Objective、design §5.3 HR法人scope、R5権限外閲覧拒否 | `AttendanceServiceImpl.java:68-77,174-177,195-229,332-354,421-430` | 同一DBに法人A/Bがあり、法人A担当HRが管理一覧または法人B要員をclose | 法人Bの勤怠PIIを閲覧・締め可能 | HRの担当法人をserver側で解決し、月次/dayへlegal entity・organization snapshotを保存、list/actionをSQL境界で同じ法人集合へ制限 | HR A→B list/detail/close拒否、NULL/未知法人fail-closed、管理者全件 | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P1-04 | P1 | T070 Objective、design §5.3 manager scope、platform-invariants §1/§2 | `AttendanceServiceImpl.java:75-77,421-431`; `EngineerAccountLinkMapper.java:17-39` | scope無効時、または対象月後に要員が組織異動済みの過去月をmanagerが照会/承認 | scope無効時は0件、過去月は現在所属で誤許可/誤拒否 | `hasFullAccess()`の無制限sentinelを先に扱い、対象月末の履歴所属をCASE/共通ResolverでSQL解決する | scope on/off、空集合、直属追加、異動前/当日/後、履歴なし/ありNULL | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P1-05 | P1 | G6手動修正統制、R1.4、design §5.4 締め済み再open | `AttendanceApiController.java:69-72`; `AttendanceServiceImpl.java:180-193`; `AttendanceMonth.java:41` | 管理者が理由なしでreopen APIをPOST | 申請/承認理由を残さず締め済みsnapshotを編集可能状態へ進められる | reason必須のcommand、承認境界、version/state CAS、業務監査への保存を追加し、空理由・自己完結を拒否 | reason必須、申請者≠承認者、二重reopen、rollback、監査再読 | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P2-01 | P2 | tasks T070 mobile 390px、shared-standards §5、handbook §7 | `AttendanceUiContractTest.java:11-30`; `review-ledger.md:122,186` | 390px Demo証拠を確認するとHTML文字列assertのみ | 折返し・操作性・拒否表示を実ブラウザで未確認 | desktop/390pxで入力・状態遷移・二重click・reload・戻る・拒否表示を実測し証跡化 | T070 browser direct Demo（Mの全UI回帰とは分離可） | OPEN | — | — |
| attendance-leave-overtime-compliance-R2-P2-02 | P2 | shared-standards §3「全件取得APIを新設しない」、性能受入 | `AttendanceServiceImpl.java:195-229` | HR/管理者が要員数の多い法人で月次一覧をGET | 全要員＋全日次を1レスポンス/メモリへ展開し、上限・pagingがない | 月次summaryを安全なpagingで取得し、日次detailを必要時に同じscopeで取得 | 0/1/1000/1001要員、31日、scope別page/count/detail | OPEN | — | — |

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|
| attendance-leave-overtime-compliance-R1-P1-01 | VERIFIED_CLOSED | active文書の旧採番/B2 provenance | `3b03a94` | R11再Review: V83、V81/V82/V83、`4488ba8`、B2完了/R11範囲を確認 | R1 fix delta再Review | 新しい予約変更があれば再Review |
| attendance-leave-overtime-compliance-R1-P1-02 | VERIFIED_CLOSED | release gate期限と実装契約の不足 | `3b03a94` | R11再Review: ATT-GATE-05/06、内部正/外部正、UNKNOWN/findingを確認 | R1 fix delta再Review | gate契約変更時 |
| attendance-leave-overtime-compliance-R1-P2-02 | VERIFIED_CLOSED | V5初期形のみ記載 | `3b03a94` | R11再Review: V5 `DECIMAL(5,1)`/V39 `DECIMAL(6,2)`を確認 | R1 fix delta再Review | 現行migration変更時 |
| attendance-leave-overtime-compliance-R1-P2-03 | VERIFIED_CLOSED | L0 commandの再現情報不足 | `2299fbc` | R11再Review: script実行1/0/0/0、exit 0を確認 | R1 fix delta再Review | script契約変更時 |

## 4. 最新Review Packet

```text
- handbook version: v2.0
- spec/tasks: attendance-leave-overtime-compliance / T067〜T070（T070のみ今回完了扱い、T071以降未着手）
- base/head/merge status: original `5e29f39` → T067成果`93c1ac6` → Packet/current merged Head`509bdb7` → R11 fix内容Head`2299fbc` → Packet/current merged Head`8edcaa6` → dispatch文書更新後のPacket/current merged Head`be2fb19` → T068 local実装Head`b327b1b` → T069 local実装Head`d395797` → Packet/current merged Head`1fd0f74` → T070 local実装Head`cc7c15c`。台帳provenanceは`git log -1 -- review-ledger.md`で解決
- changed files by task: T067成果文書/台帳、T068のV1/V83、H2 replay、engineer-schema-h2、application-test.yml、7 entity/mapper、MySQL smoke、migration consistency test、T069のcalculator/協定asOf resolver/UNKNOWN finding/定向test、T070の本人/管理API・service・DTO・画面・JS・SecurityConfig・sidebar・4言語i18n・定向test、tasks/design/source matrix、中央台帳
- requirements/AC trace: 最重要境界、R1.1〜R1.4、R2.1/R2.2、R3.1/R3.2/R3.4、R4.2、R5、T070のR1.1/R1.2/R1.3/R1.4/R3.3/R5
- migration state: 実適用最新V81、dispatch V82は未merge、attendance V83は`b327b1b`でlocal実装済み・未merge/未本番適用、V59/V72永久欠番。B2 merged commit=`4488ba8`
- test evidence: T067 R1 fix L0 `1/0/0/0` PASS。T068 `AttendanceSchemaTest 5/0/0/0`、`MigrationScriptIntegrityTest + SpecDispatchConsistencyTest 35/0/0/0`、Docker MySQL smoke `1/0/0/0` PASS。T069 calculator/resolver/DDL直接回帰 `70/0/0/0` PASS。T070 `AttendanceApiControllerTest 7/0/0/0`、`AttendanceWorkflowServiceTest 1/0/0/0`、`AttendanceUiContractTest 2/0/0/0`、`MessageBundleConsistencyTest 4/0/0/0`、`JsSyntaxCheckTest 1/0/0/0`、`RoleNavigationVisibilityTest 2/0/0/0`をPASS。MySQL smokeはV82未適用の一時containerのみ
- Demo evidence: H2/MySQLでcalendar、NULL/0、外部source重複拒否、月初制約、overtime config 9 keyを実測。T069はfixture境界、対象月asOf、法人別上限優先、協定なし/適用除外不明/履歴不足のfindingを実測。T070は本人入力→提出→差戻し→再提出→承認→締め、締め後編集拒否、営業画面/API拒否、CSRF、390px markupを定向確認
- skipped/unverified: V82 merge/apply順、法人一覧、36協定書、就業規則、法定休日曜日、勤務区分、休暇残数の正、適用除外者、HR法人の実資料突合、freee/休暇/差異通知、L4全量。T070はテナントDBを法人scope境界として扱い、法人別実資料の受入はATT-GATE-01〜06に残す
- known issue IDs: R1-P2-01（FIXED_BY_IMPLEMENTER、独立再Review待ち）、release gate ATT-GATE-01〜ATT-GATE-06。P1-01/P1-02/P2-02/P2-03はVERIFIED_CLOSED
- out-of-scope: 休暇approval、provider sync、差異通知、warning通知scheduler、M/L4、V83のV82前merge/apply
- rollback: T070 code commit `cc7c15c`をrevertし、必要ならT069 `d395797`、T068 `b327b1b`を順にrevert（V83未適用のためproduction data変更なし）。T067文書は既存fix履歴をrevert
- requested verdict: T070 COMPLETED_UNREVIEWED / R11 P2-01 FIXED_BY_IMPLEMENTER（current merged Head同期済み、独立Reviewを依頼）
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 最重要境界 / R1.3 / R4.2 | 雇用勤怠、客先請求工数、freeeの責任境界を混同しない | source matrix §1 | L0文書整合 | HR提示資料で境界を説明 | HR承認未実施 | 中間 |
| R1.2 / R3.2 | カレンダー・法人別協定へ未確認値を推測投入しない | source matrix §3/§5/§9 | L0未確認明記 | 法定休日曜日・協定一覧テンプレートを提示 | 就業規則・協定書未入手 | 中間 |
| R2.1 / R2.2 | 休暇種別と残数の正を後続実装で取り違えない | source matrix §4 | L0種別/正の確認状況 | 休暇種別一覧をHRへ提示 | 種別ごとの正未確認 | 中間 |
| R3.2 / R3.4 | 適用除外者を役職名の推測で誤判定しない | source matrix §6 | L0対象者未確認を明記 | 管理監督者一覧テンプレートを提示 | HR個別確認未実施 | 中間 |
| R5 | 確定値未入手でも判定不能を適合と誤認しない | findings F-1〜F-6、§10 | L0 | fail-closed/release gate区分を提示 | release gate未達 | 中間 |
| R1.1/R1.2/R1.3 | 雇用勤怠を分単位で記録し、calendar/source/NULL・0/外部冪等をDDLで固定する | V1/V83、`m_work_calendar*`、`t_employee_attendance`、`t_attendance_month` | `AttendanceSchemaTest`、MySQL smoke | calendar日を投入しNULL/0とsource重複拒否を確認 | 締め済み更新拒否はT070でservice確認、V83適用はV82後 | T068実装済み・独立Review待ち |
| R2.1/R2.2 | 休暇申請の期間・分・approval参照列を持ち、残数正本未確認を後続でfail-closed扱いできる | `t_leave_request`、残数ledgerは正本確定後の条件付き | schema replay、migration integrity | 休暇DDLの列・期間CHECKを確認 | 外部正/内部正の業務挙動はT071 | T068実装済み・独立Review待ち |
| R3.2/R3.4 | 法人別協定、月初起算、適用除外者UNKNOWN、follow-upを保持する | `m_overtime_agreement`、`t_overtime_followup`、`t_engineer.overtime_exempt_flag(NULL=未確認)` | 月初CHECK、config 9 key、MySQL smoke | invalid `valid_from`拒否とconfig seedを確認 | calculator UNKNOWN/findingはT069 | T068実装済み・独立Review待ち |
| R3.1/R3.2/R3.4/R5 | 6ルールの境界と法人/asOf協定を正しく判定し、正本・適用区分・必須履歴の不足を適合にしない | `OvertimeComplianceCalculator`、`OvertimeAgreementResolver`、`OvertimeAgreementSnapshot.from`、`OvertimeRule`のUNKNOWN finding | 公式fixture 27、resolver integration 3、DDL/dispatch直接回帰を含む70/0/0/0 | T069-D1で45h/360h/80h境界、月100hの`>=`、法人別上限優先、協定なし/適用除外不明/履歴不足を確認 | 法人別協定・法定休日・適用除外者資料はATT-GATE-02/03/06で未確認 | COMPLETED_UNREVIEWED |
| R1.1/R1.2/R1.3/R1.4/R3.3/R5 | 本人入力、月次提出、上長差戻し/承認、HR締めを同一状態CAS・scope・CSRF境界で扱う | `AttendanceServiceImpl`、本人/管理API、DTO、画面/JS、SecurityConfig/sidebar、4言語message | reviewer再実行17/0/0/0。ただし法定時間区分、HR法人scope、manager asOf/full-access、reopen理由/承認競合をassertしない | 実ブラウザ未実施。逐次状態遷移・営業403・CSRF・markup静的確認のみ | R2-P1-02〜05、R2-P2-01 | **FAIL** |

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
