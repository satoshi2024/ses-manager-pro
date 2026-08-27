# 実行台帳（NF-10 / DG-10 承認済み）

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
- [x] T3: 承認済みscope、DG-10、Baseをrequirements/design/tasksへ反映し、Plan self-reviewで矛盾を解消する。
  - Objective: F1開始前に実装境界と受入条件を固定する。
  - Evidence: `README.md`、`requirements.md`、`design.md`、`completion-matrix.md`、中央traceability。
  - Demo: schedule権限、actual/forecast、7年保持、retry不変性、partial/failed配布停止、recipient scope、outbox/link/re-auth、ServiceDesk除外が一貫して記載されている。

## Implementation

- [ ] F1: template/version/schedule/run/snapshot/delivery の DDL。最新migration+1、V1/H2同期、shape test、7年保持を実装する。
- [ ] F2: explicit system principal/scope の snapshot orchestration。管理者/マネージャーscope、速報/確定、retry不変性、partial/failed停止を実装する。
- [ ] A1: template/preview/run UI。管理者有効化、recipient preview、actual/forecast、dataAsOf/freshness、Asia/Tokyoを表示する。
- [ ] B1: PDF/XLSX/CSV と DocumentService 登録。同一snapshot、hash/version/CLEAN、7年保持、scope/access auditを実装する。
- [ ] B2: schedule、outbox、link/re-auth、retry、DLQ/manual replay。站内通知＋期限付きlink、生成/download scope、再認証を実装する。
- [ ] M: contract test、月末境界、desktop/390px、restore、配布障害訓練、base/head 証拠。required gatesをskip 0で実施する。

各完了taskは独立commitしてremoteへpushし、completion matrixへBase/Head、テスト、Demo、rollbackを記録する。実装対話ではPRを作成しない。
