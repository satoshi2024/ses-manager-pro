# Review Ledger — 雇用勤怠・休暇・時間外労働

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `attendance-leave-overtime-compliance` |
| handbook | `v2.0` |
| state | `IMPLEMENTATION / REVIEW` |
| base | `5e29f39c96da85b29a0fe881326d979896a595d0` |
| head | T068実装Head=`b327b1b`（V83はlocal実装のみ、V82 merge/apply後に進める）。Packet/current merged Head=`be2fb19`（`8edcaa6`を含む）、R11 fix内容Head=`2299fbc`。台帳provenanceは`git log -1 -- review-ledger.md`で解決 |
| merge | `be2fb19`がcurrent merged baseline（dispatch側の後続文書commitを含む）。T068の`b327b1b`/V83は未merge・未本番適用、dispatch V82を先行する |
| latest review | `R11 Round 1 fix delta再Review / T068実装証跡 2026-08-09` |
| verdict | `T068 COMPLETED_UNREVIEWED`。R1-P2-01は`FIXED_BY_IMPLEMENTER`（独立再Review待ち） |
| issue count | `P0=0 / P1=0 / P2=1 / NOTE=0` |
| next action | `Packet/current merged Head=be2fb19を実Gitで再確認し、R11 P2-01 fix deltaとT068 b327b1bを分離して独立Reviewへ提出。V83のmerge/applyはV82後` |

本台帳は、T067/T068のtask実装とその証拠をappend-onlyで管理する。T068はDDL/entity/H2/smokeの実装を含むが、V83のmerge/applyはV82後とする。

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| attendance-leave-overtime-compliance-R1-P2-01 | P2 | handbook §9 Base/Head/merge固定 | `review-ledger.md:10-16,41` | Packetだけではcurrent merged Headを解決できない | Review範囲の誤認 | T067成果commitとPacket/current merged Headを分離 | `git rev-parse`、branch containment | FIXED_BY_IMPLEMENTER | `git log -1 -- review-ledger.md`で解決 | 独立再Review待ち |

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
- spec/tasks: attendance-leave-overtime-compliance / T067・T068（T068のみ完了扱い、T069以降未着手）
- base/head/merge status: original `5e29f39` → T067成果`93c1ac6` → Packet/current merged Head`509bdb7` → R11 fix内容Head`2299fbc` → Packet/current merged Head`8edcaa6` → dispatch文書更新後のPacket/current merged Head`be2fb19` → T068 local実装Head`b327b1b`。台帳provenanceは`git log -1 -- review-ledger.md`で解決
- changed files by task: T067成果文書/台帳、T068のV1/V83、H2 replay、engineer-schema-h2、application-test.yml、7 entity/mapper、定向test、MySQL smoke、migration consistency test、tasks/design/source matrix、中央台帳
- requirements/AC trace: 最重要境界、R1.1〜R1.4、R2.1/R2.2、R3.2/R3.4、R4.2、R5
- migration state: 実適用最新V81、dispatch V82は未merge、attendance V83は`b327b1b`でlocal実装済み・未merge/未本番適用、V59/V72永久欠番。B2 merged commit=`4488ba8`
- test evidence: T067 R1 fix L0 `1/0/0/0` PASS。T068 `AttendanceSchemaTest 5/0/0/0`、`MigrationScriptIntegrityTest + SpecDispatchConsistencyTest 35/0/0/0`、Docker MySQL smoke `1/0/0/0` PASS。MySQL smokeはV82未適用の一時containerのみ
- Demo evidence: H2/MySQLでcalendar、NULL/0、外部source重複拒否、月初制約、overtime config 9 keyを実測。UI `/system-config`表示編集と締め済み更新拒否は後続A1/T070で実施
- skipped/unverified: V82 merge/apply順、法人一覧、36協定書、就業規則、法定休日曜日、勤務区分、休暇残数の正、適用除外者、service-levelの締め済み変更拒否、休暇ledger（正本確定後に条件付き）
- known issue IDs: R1-P2-01（FIXED_BY_IMPLEMENTER、独立再Review待ち）、release gate ATT-GATE-01〜ATT-GATE-06。P1-01/P1-02/P2-02/P2-03はVERIFIED_CLOSED
- out-of-scope: calculator、月次状態service/UI/API、休暇approval、provider sync、差異通知、M/L4、V83のV82前merge/apply
- rollback: T068実装commit `b327b1b`をrevert（V83未適用のためproduction data変更なし）。T067文書は既存fix履歴をrevert
- requested verdict: T068 COMPLETED_UNREVIEWED / R11 P2-01 FIXED_BY_IMPLEMENTER（独立Reviewを依頼）
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 最重要境界 / R1.3 / R4.2 | 雇用勤怠、客先請求工数、freeeの責任境界を混同しない | source matrix §1 | L0文書整合 | HR提示資料で境界を説明 | HR承認未実施 | 中間 |
| R1.2 / R3.2 | カレンダー・法人別協定へ未確認値を推測投入しない | source matrix §3/§5/§9 | L0未確認明記 | 法定休日曜日・協定一覧テンプレートを提示 | 就業規則・協定書未入手 | 中間 |
| R2.1 / R2.2 | 休暇種別と残数の正を後続実装で取り違えない | source matrix §4 | L0種別/正の確認状況 | 休暇種別一覧をHRへ提示 | 種別ごとの正未確認 | 中間 |
| R3.2 / R3.4 | 適用除外者を役職名の推測で誤判定しない | source matrix §6 | L0対象者未確認を明記 | 管理監督者一覧テンプレートを提示 | HR個別確認未実施 | 中間 |
| R5 | 確定値未入手でも判定不能を適合と誤認しない | findings F-1〜F-6、§10 | L0 | fail-closed/release gate区分を提示 | release gate未達 | 中間 |
| R1.1/R1.2/R1.3 | 雇用勤怠を分単位で記録し、calendar/source/NULL・0/外部冪等をDDLで固定する | V1/V83、`m_work_calendar*`、`t_employee_attendance`、`t_attendance_month` | `AttendanceSchemaTest`、MySQL smoke | calendar日を投入しNULL/0とsource重複拒否を確認 | 締め済み更新拒否はT070 | T068実装済み・独立Review待ち |
| R2.1/R2.2 | 休暇申請の期間・分・approval参照列を持ち、残数正本未確認を後続でfail-closed扱いできる | `t_leave_request`、残数ledgerは正本確定後の条件付き | schema replay、migration integrity | 休暇DDLの列・期間CHECKを確認 | 外部正/内部正の業務挙動はT071 | T068実装済み・独立Review待ち |
| R3.2/R3.4 | 法人別協定、月初起算、適用除外者UNKNOWN、follow-upを保持する | `m_overtime_agreement`、`t_overtime_followup`、`t_engineer.overtime_exempt_flag(NULL=未確認)` | 月初CHECK、config 9 key、MySQL smoke | invalid `valid_from`拒否とconfig seedを確認 | calculator UNKNOWN/findingはT069 | T068実装済み・独立Review待ち |

## 6. 横断契約

### 6.1 Scope consumer inventory

| consumer | endpoint/job | population source | DataScope | organization | tenant | empty-set | test |
|---|---|---|---|---|---|---|---|
| T067文書 | なし（文書のみ） | 既存migration/specの棚卸し | N/A | N/A | 独立DB前提 | N/A | L0 |
| 後続attendance | 勤怠・休暇・warning・通知・scheduler | design.md §5.3の決定表 | 本spec決定表 | HR法人 / manager組織∩DataScope / 本人自己のみ | 独立DB | 後続taskでSQL境界へ適用 | T068以降 |

### 6.2 Temporal/NULL matrix

| field/concept | current | history | snapshot | explicit NULL | missing history | asOf rule | boundary test |
|---|---|---|---|---|---|---|---|
| 勤務カレンダー/法定休日 | 未確認のため未確定 | F1でvalid_from/to | 月次確定時固定 | 所定なしと0分を区別 | 協定/規程なしはfinding | 勤務日時点の有効版 | F1/F2 |
| 36協定 | 法人別資料未入手 | F1でvalid_from/to | 判定結果をfollowupへ | 協定行なしは協定未締結・判定不能 | 既定値で適合にしない | 対象月時点 | F2 |
| 休暇残数 | 正本未確認 | F1/A2で台帳または外部参照 | N/A | 外部正は参照のみ | 不明でも外部正モードは申請拒否しない | 申請日時点 | A2 |

### 6.3 Transaction/cache matrix

| mutation | CAS/UNIQUE | transaction | cache event | commit | rollback | concurrent test |
|---|---|---|---|---|---|---|
| T067文書 | N/A | N/A | N/A | 文書commit後 | git revert可能 | N/A |
| 後続勤怠/同期/締め | design.md §5.4の状態CAS、外部source unique | 後続taskで定義 | 後続taskで定義 | 締め・承認後に外部呼出し | 締め済み外部更新はfinding | T068〜T073 |

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

T067 L0にskipはない。T068はH2/MavenとDocker/MySQL smokeを実行済み。browser/UI、締めservice、Node、L4全量は担当範囲外またはT070/T074/Mへ繰り越す。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|
| T067-D1 | HR/発注者、現行DB・spec資料 | 文書Demo | source matrix、法人別36協定一覧、法定休日、勤務区分、休暇種別、適用除外者一覧を提示 | 本システム正の境界と未確認項目が明示され、推測値がない | 資料は提示可能。HR/法人資料の受領と承認は未実施 | `source-matrix-and-agreement-inventory.md` §1〜§8 | CONDITIONAL（本番release gate） |
| T068-D1 | 開発/Review担当 | H2 + MySQL 8.0 ephemeral | calendar日へNULL/0を登録、freee source重複、月初外協定、config全keyを検証 | NULL/0を区別、重複/月初外を拒否、9 key seed | 1週間の画面Demo、締め済み拒否、`/system-config`表示編集はT070/M | `AttendanceSchemaTest`、`FlywayAttendanceSchemaSmokeTest` | PASS（DDL Demo） |

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
