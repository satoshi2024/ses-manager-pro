# 17spec中央実行台帳

## 1. 運用ルール

本台帳を対話管理の唯一の入口とする。通常は1specにつき主実装対話1つ、独立Review対話1つを使用し、
115個の原子taskごとに対話を作らない。原子taskは各specの `tasks.md` と `review-ledger.md` で追跡する。

- `NOT READY`: decisionまたは先行spec待ち。対話を開始しない。
- `READY`: 開始条件を満たし、主実装対話を開始できる。
- `IN PROGRESS`: 主実装対話でtaskを順次実行中。
- `REVIEW`: 実装を止め、独立Review対話で確認中。
- `FIX`: Review指摘を元の実装対話で修正中。
- `PASS`: Review合格。次spec/Waveを開始可能。
- `DEFERRED`: 発注者決定により現行roadmap外。完了と同義ではない。

## 2. 中央台帳

| # | Wave | spec | カタログtask | 現在状態 | 開始条件/次のaction | 実装対話 | Base/Head | Review | 次へ進む条件 |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | 0 | `multi-company-tenant-isolation` | T001〜T007 | T001 `COMPLETED`（発注者受領）、T002〜T007 `DEFERRED` | 独立DBを正式採用。V59は作成せず、V60以降適用後に補写しない。共有DB再承認時は当時latest+1で再計画 | — | — | T001は再Review対象外。R01は将来T002〜T007再開時だけ使用 | current-mode Gateを満たした記録を保持 |
| 2 | 0 | `organization-management-accounting` | T008〜T013 | `FIX`（P0/P1実装・自動検証済み、全量833件成功、独立Review FAIL：外部ゲート未検証） | Docker MySQL空庫/legacy smoke、desktop/390px実ブラウザDemoを完了し、外部ゲート後に再Reviewする。完了まではenterpriseを開始しない | S02 organization-management-accounting 実装 | Base `601177a14689b6fc12cf79482224e0467a7e00ba` / Head `62a1f8a25b2a0638398cbb477bb10a58dba5afae` + 未コミット修正差分 | R02 第十一次独立再Review: FAIL。P0/P1ロジックはPASS、自動833: 0 failures / 0 errors / 6 skipped、Docker/Node/Browser未検証 | P0/P1修正、Docker MySQL、desktop/390px Demo、独立再ReviewをすべてPASS。enterpriseはその後のみ開始 |
| 3 | 0 | `enterprise-identity-security` | T014〜T020 | `NOT READY` | organization PASS、G1決定後S03 |  |  |  | R03 PASS |
| 4 | 0 | `legal-document-ledger-archive` | T021〜T027 | `NOT READY` | identity PASS、G2決定後S04 |  |  |  | R04 PASS |
| 5 | 0 | `productivity-search-saved-view` | T028〜T033 | `NOT READY` | archive PASS後S05 |  |  |  | R05 PASSでWave 0完了 |
| 6 | 1 | `bp-company-master-procurement-compliance` | T034〜T040 | `NOT READY` | Wave 0 PASS、G2決定後S06。CRMと並行可 |  |  |  | R06 PASS |
| 7 | 1 | `approval-workflow-internal-control` | T041〜T047 | `NOT READY` | BP/CRM PASS、G7方針記録後S07 |  |  |  | R07 PASSでWave 1完了 |
| 8 | 1 | `crm-contact-opportunity` | T048〜T053 | `NOT READY` | Wave 0 PASS後S08。BPと並行可、V64→V65順merge |  |  |  | R08 PASS |
| 9 | 2 | `order-acceptance-workflow` | T054〜T059 | `NOT READY` | approval PASS後S09 |  |  |  | R09 PASS |
| 10 | 2 | `dispatch-outsourcing-compliance-ledger` | T060〜T066 | `NOT READY` | order PASS、G2確定後S10。attendanceと並行可 |  |  |  | R10 PASS |
| 11 | 2 | `attendance-leave-overtime-compliance` | T067〜T074 | `NOT READY` | order PASS、G6確定後S11。dispatchと並行可 |  |  |  | R11 PASS |
| 12 | 2 | `staffing-capacity-planning` | T075〜T080 | `NOT READY` | dispatch/attendance PASS後S12 |  |  |  | R12 PASSでWave 2完了 |
| 13 | 3 | `external-customer-bp-portal` | T081〜T087 | `NOT READY` | Wave 2 PASS、G3/G8方針後S13 |  |  |  | R13 PASS、security chain先行merge |
| 14 | 3 | `engineer-self-service-portal-v2` | T088〜T093 | `NOT READY` | external portal security merge、G9方針後S14 |  |  |  | R14 PASS |
| 15 | 3 | `accounting-payment-integration` | T094〜T101 | `NOT READY` | portal/order/BP/archive PASS、G4/G9方針後S15 |  |  |  | R15 PASS |
| 16 | 3 | `jp-pint-digital-invoice` | T102〜T108 | `NOT READY` | accounting PASS、G5決定後S16 |  |  |  | R16 PASSでWave 3完了 |
| 17 | 4 | `ai-feedback-learning` | T109〜T115 | `NOT READY` | CRM/proposal/staffing/outcome完了、G10方針後S17 |  |  |  | R17 PASSでroadmap完了 |

## 3. 1specの状態遷移

```text
NOT READY → READY → IN PROGRESS → REVIEW ─┬→ PASS
                                           └→ FIX → REVIEW

発注者による延期: NOT READY/IN PROGRESS → DEFERRED
再開時: DEFERRED → decision・採番・依存再確認 → READY
```

## 4. 対話命名例

- 実装: `S02 organization-management-accounting 実装`
- Review: `R02 organization-management-accounting Review`
- 修正は新規対話を作らず、同じS02へR02の指摘だけを返す。
- 再Reviewも新規対話を作らず、同じR02へ修正commit/diffだけを渡す。
