# 実行台帳（DG-10 決定前）

## Discovery

- [x] T0: 専用 worktree、branch、base、status、remote を検証する。
  - Objective: 通常 checkout を変更しない開始証拠を残す。
  - Evidence: `README.md` の開工判定。通常 checkout は `main`、専用 branch は `codex/scheduled-management-reporting`。
  - Demo: `git worktree list`、`git status --short --branch`、`git remote -v`。
- [x] T1: NF-10/DG-10、platform invariants、既存正本 service/DTO/API/UI/export、DocumentService、outbox、backup/recovery を読了し inventory を作成する。
  - Objective: 集計式を再実装せず、正本と制約を対応付ける。
  - Evidence: `inventory.md`。
  - Demo: section ごとに正本、cutoff/timezone、scope owner、snapshot、document、delivery を追跡できる。
- [x] T2: 仮の sample snapshot spec を作成し、画面/export/report 共通契約、immutability、freshness、partial failure、recipient guard を示す。
  - Objective: DG-10 の議論用の最小契約を用意する。
  - Evidence: `sample-snapshot-spec.md`。
  - Demo: JSON 例に actual/forecast、cutoff、timezone、data freshness、scope、source hash、section status が存在する。

## Implementation（DG-10 待ち）

- [ ] F1: template/version/schedule/run/snapshot/delivery の DDL。`BLOCKED: DG-10`。
- [ ] F2: explicit system principal/scope の snapshot orchestration。`BLOCKED: DG-10`。
- [ ] A1: template/preview/run UI。`BLOCKED: DG-10`。
- [ ] B1: PDF/XLSX/CSV と DocumentService 登録。`BLOCKED: DG-10`。
- [ ] B2: schedule、outbox、link/re-auth、retry、DLQ/manual replay。`BLOCKED: DG-10`。
- [ ] M: contract test、月末境界、desktop/390px、restore、配布障害訓練、base/head 証拠。`BLOCKED: DG-10`。

F1 以降の checkbox は DG-10 と approved report/recipient/Owner が確定し、承認済み plan/spec/tasks が公開された後にのみ更新する。
