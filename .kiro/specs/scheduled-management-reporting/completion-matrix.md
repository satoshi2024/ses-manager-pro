# 完了対応表 / Review handoff ledger

## 状態

`DISCOVERY_ONLY / BLOCKED_DG-10`

指定された approved scope、Owner、Base、Base branch はプレースホルダーのままであり、DG-10 も未決定である。よって「approved plan/spec/tasks」としては引き渡さず、承認待ちの draft として引き渡す。

## 完了対応表

| Task | 対応要求 | 状態 | 成果物 / 証拠 | commit / remote |
|---|---|---|---|---|
| T0 | 開工境界、通常 checkout 非変更 | 完了 | `README.md`、worktree/branch/status/remote/base の観測値 | discovery commit 後に記録 |
| T1 | NF-10 inventory、正本再利用、scope/time/document/outbox/backup 対応 | 完了 | `inventory.md`、`requirements.md`、`design.md` | discovery commit 後に記録 |
| T2 | sample snapshot spec | 完了 | `sample-snapshot-spec.md`、actual/forecast、cutoff、timezone、freshness、scope、source hash、section status | 追加後に記録 |
| F1〜F2 | DDL / orchestration | DG-10 待ち | 未着手 | なし |
| A1 / B1 / B2 | UI / document / delivery | DG-10 待ち | 未着手 | なし |
| M | test / restore / drill / base-head evidence | DG-10 待ち | 未着手 | なし |

## Review に渡すもの

- 実装対象 branch: `codex/scheduled-management-reporting`
- 観測 base: `f131f51c50dbfb68ffc8e71878da52947560c80e`
- 最終 remote head: Discovery 完了後にこの表へ追記
- Review 入力: 本ディレクトリの draft requirements/design/tasks、inventory、sample snapshot
- Review 判定: DG-10 が未決定のため、実装 PASS/PR 作成へ進めない。承認後に PLAN Review、次に IMPLEMENTATION Review を独立実施する。

## まだ証拠化していないもの

M の month-end boundary、desktop/390px preview、document restore、delivery incident drill、base/head の最終比較は、実装未着手のため未実施である。sample snapshot の commit は実装完了を意味しない。
