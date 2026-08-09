# Review Ledger — 雇用勤怠・休暇・時間外労働

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `attendance-leave-overtime-compliance` |
| handbook | `v2.0` |
| state | `IN PROGRESS` |
| base | `5e29f39c96da85b29a0fe881326d979896a595d0` |
| head | `93c1ac638e4672c8c4bf60421d1482e4ebc06949`（T067文書commit） |
| merge | `unmerged` |
| latest review | `未実施（T067完了後にR11開始）` |
| verdict | `中間記録` |
| issue count | `P0=0 / P1=0 / P2=0 / NOTE=0` |
| next action | `T068着手前にmerge済みdb/migrationの最新とV83衝突を再確認する` |

本台帳は、T067のtask実装とその証拠をappend-onlyで管理する。T067は文書のみであり、production code・DDL・migrationは変更しない。

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — | — |

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — |

## 4. 最新Review Packet

```text
- handbook version: v2.0
- spec/tasks: attendance-leave-overtime-compliance / T067のみ
- base/head/merge status: 5e29f39 / 93c1ac6 / 未merge（main上のT067文書commit）
- changed files by task: source-matrix-and-agreement-inventory.md、tasks.md、spec-execution-ledger.md、本台帳
- requirements/AC trace: 最重要境界、R1.3、R2.1/R2.2、R3.2/R3.4、R4.2、R5
- migration state: 実適用最新V81、予約V83、V82/V83未作成、V59/V72永久欠番
- test evidence: L0 PASS（文書整合チェック、`git diff --check` exit 0）
- Demo evidence: source matrixと未確認事項のHR提示資料を作成。HR/法人資料の確認は未実施
- skipped/unverified: 法人一覧、36協定書、就業規則、法定休日曜日、勤務区分、休暇残数の正、適用除外者
- known issue IDs: release gate ATT-GATE-01〜ATT-GATE-06
- out-of-scope: Java/HTML/JS/SQL、migration、V1/H2/entity、calculator、UI/API/provider
- rollback: T067文書変更をrevertする。production data変更なし
- requested verdict: intermediate（独立Reviewはspec全task合流後）
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 最重要境界 / R1.3 / R4.2 | 雇用勤怠、客先請求工数、freeeの責任境界を混同しない | source matrix §1 | L0文書整合 | HR提示資料で境界を説明 | HR承認未実施 | 中間 |
| R1.2 / R3.2 | カレンダー・法人別協定へ未確認値を推測投入しない | source matrix §3/§5/§9 | L0未確認明記 | 法定休日曜日・協定一覧テンプレートを提示 | 就業規則・協定書未入手 | 中間 |
| R2.1 / R2.2 | 休暇種別と残数の正を後続実装で取り違えない | source matrix §4 | L0種別/正の確認状況 | 休暇種別一覧をHRへ提示 | 種別ごとの正未確認 | 中間 |
| R3.2 / R3.4 | 適用除外者を役職名の推測で誤判定しない | source matrix §6 | L0対象者未確認を明記 | 管理監督者一覧テンプレートを提示 | HR個別確認未実施 | 中間 |
| R5 | 確定値未入手でも判定不能を適合と誤認しない | findings F-1〜F-6、§10 | L0 | fail-closed/release gate区分を提示 | release gate未達 | 中間 |

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
| fresh | V81 → reserved V83 | T068で実施 | 未実施 | N/A（T067はmigrationなし） | — |
| legacy | published V81 → V83 | T068で実施 | 未実施 | N/A（T067はmigrationなし） | — |
| partial/backfill/repair | V83 | T068で実施 | 未実施 | N/A（T067はmigrationなし） | — |

## 7. Test Evidence

| level | command | environment | tests | failures | errors | skipped | exit | commit | executor |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| L0 | PowerShell inline文書整合チェック、`git diff --check` | Windows PowerShell / worktree | 1 | 0 | 0 | 0 | 0 | `93c1ac6` | 主担当 |

skipはT067の文書L0には該当なし。Docker/MySQL/Maven/Node/browserはproduction code・DDL・UIを変更しないため本taskでは実行対象外で、T074/Mまたは該当taskへ繰り越す。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|
| T067-D1 | HR/発注者、現行DB・spec資料 | 文書Demo | source matrix、法人別36協定一覧、法定休日、勤務区分、休暇種別、適用除外者一覧を提示 | 本システム正の境界と未確認項目が明示され、推測値がない | 資料は提示可能。HR/法人資料の受領と承認は未実施 | `source-matrix-and-agreement-inventory.md` §1〜§8 | CONDITIONAL（本番release gate） |

## 9. Release Gate Register

| gate ID | 未確認事項 | owner | 合格条件 | 影響 | 期限/実施時点 |
|---|---|---|---|---|---|
| ATT-GATE-01 | 法人の実数・名称 | 発注者/HR | 法人一覧を確定 | V83法人別行 | F1 seed前 |
| ATT-GATE-02 | 法人別36協定書・特別条項・上限・起算月 | HR/各法人 | 協定書を確認し`m_overtime_agreement`へ登録 | calculator判定 | 本番締め前 |
| ATT-GATE-03 | 法定休日・所定休日の曜日 | HR | 就業規則とcalendarを突合 | 休日労働算入 | 本番締め前 |
| ATT-GATE-04 | 勤務区分の実運用 | HR | 就業規則とwork_typeを確定 | F1 model | F1実装後〜本番前 |
| ATT-GATE-05 | 休暇残数の正 | HR | 種別・法人ごとに本システム/外部を確定 | A2申請可否 | A2着手前 |
| ATT-GATE-06 | 管理監督者・適用除外者 | HR | 個別対象者を確定し構造化フラグへ反映 | F2非判定 | F2/M・本番前 |

上記はT067の開発着手条件ではない。未確認時は判定不能findingを出し、本番締め・本番releaseをfail-closedにする。

## 10. T067完了証跡（1行/Task）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T067 | 最重要境界、R1.2/R1.3、R2.1/R2.2、R3.2/R3.4、R4.2、R5 | `source-matrix-and-agreement-inventory.md`、本台帳、`tasks.md`、中央台帳 | L0 PASS、1/0/0/0、`git diff --check` exit 0 | T067-D1、資料提示可能。HR確認はrelease gate | `93c1ac6`（現台帳同期は後続の文書provenance commit） | 法人/協定/就業規則/適用除外者未確認。既定値で適合にせず判定不能として管理 |

## 11. Round履歴

### Round 0 — 2026-08-09 — 主担当中間記録

- base/head: `5e29f39` / `93c1ac6`
- scope: T067のみ、文書整合と開始条件
- reviewed issue IDs: なし
- new issue IDs: なし。release gateはATT-GATE-01〜06として別管理
- independently executed tests: L0文書整合チェック、`git diff --check`（PASS）
- verdict: T067完了（独立Review待ち。release gateは未達のまま管理）
- ledger/central synchronization: tasks.md・中央台帳・本台帳はT067完了記録へ更新済み。現行台帳のprovenance同期commitは`git log -1 -- <path>`で解決する
