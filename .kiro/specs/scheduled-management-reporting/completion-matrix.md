# 完了対応表 / Review handoff ledger

## 状態

`APPROVED / IMPLEMENTING`

2026-08-28にNF-10/DG-10が正式承認された。Ownerは管理者（経営管理責任者）、Base branchは`origin/main`、Base policyは再開時fetchの最新`origin/main`。今回の承認Baseは`455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。

## 完了対応表

| Task | 対応要求 | 状態 | 成果物 / 証拠 | commit / remote |
|---|---|---|---|---|
| T0 | 開工境界、通常 checkout 非変更 | 完了 | `README.md`、worktree/branch/status/remote/base の観測値 | `868265384e91960dfa71a279173f9de30e9a128d` |
| T1 | NF-10 inventory、正本再利用、scope/time/document/outbox/backup 対応 | 完了 | `inventory.md`、`requirements.md`、`design.md` | `868265384e91960dfa71a279173f9de30e9a128d` |
| T2 | sample snapshot spec | 完了 | `sample-snapshot-spec.md`、actual/forecast、cutoff、timezone、freshness、scope、source hash、section status | `868265384e91960dfa71a279173f9de30e9a128d` |
| T3 | 最新Base取り込みとapproved plan/spec/tasks昇格 | 完了 | `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`、中央traceability、承認済みspec | `a86af3f30f89feff28e88bf4dda5e10974852cdd` |
| F1 | template/version/schedule/run/snapshot/delivery DDL | 完了 | `V112__scheduled_management_reporting.sql`、H2 schema、6 entity/mapper | `9b342c79d8495ce52e81d1c2a862d603f3b8581a`。compile成功、AttendanceSchemaTest 6/6 |
| F2 | snapshot orchestration | 着手前 | 未着手 | — |
| A1 / B1 / B2 | UI / document / delivery | 着手前 | 未着手 | — |
| M | test / restore / drill / base-head evidence | 着手前 | 未着手 | — |

## Review に渡すもの

- 実装対象 branch: `codex/scheduled-management-reporting`
- 観測 base: `f131f51c50dbfb68ffc8e71878da52947560c80e`
- 承認Base: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 取り込み後Head: `a86af3f30f89feff28e88bf4dda5e10974852cdd`
- Review 入力: 本ディレクトリの承認済みrequirements/design/tasks、inventory、sample snapshot
- Review 判定: Plan self-review後に独立PLAN Reviewを実施し、PLAN PASS後に実装を継続する。Implementation ReviewはM完了後に実施する。

## まだ証拠化していないもの

M の month-end boundary、desktop/390px preview、document restore、delivery incident drill、実装完了時のbase/head比較、required test gatesは未実施である。
