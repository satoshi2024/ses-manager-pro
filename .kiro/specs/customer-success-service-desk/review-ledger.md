# Implementation & Review Ledger — customer-success-service-desk (NF-02)

## 1. メタデータ

| 項目 | 値 |
|---|---|
| Feature | NF-02 `customer-success-service-desk` |
| Worktree | `C:\work\ses-customer-success-service-desk` |
| Branch / remote | `codex/customer-success-service-desk` / `origin/codex/customer-success-service-desk` |
| Base branch / commit | `origin/main` / `bd2bfca6aecab365f4fbbf4916ddb4f393614d27` |
| 公式Status | **DISCOVERY**（Owner未定、DG-02未APPROVED）。IMPLEMENTING/REVIEWINGではない |
| Owner | 未定（開工プレースホルダ `<OWNER>` 未置換） |
| Approved scope | 未指定（`<APPROVED_SCOPE>` 未置換） |
| Review開始 | **NO**（PLAN未APPROVED。先行WIPがあってもReview対象にしない） |
| PR | 実装対話では作成しない |

---

## 2. Decision Gate DG-02

| 論点 | 状態 | 記録場所 |
|---|---|---|
| portal起票対象契約と利用者 | **PROPOSED** | `inventory.md` DG-02-A |
| SLA営業時間・休日・pause・priority | **PROPOSED** | `inventory.md` DG-02-B |
| INTERNALと公開commentの分離 | **PROPOSED** | `inventory.md` DG-02-C |
| health要因・重み・更新判断への使い方 | **PROPOSED** | `inventory.md` DG-02-D |

公式 `2026-08-27-post-acceptance-traceability.md` のDG-02本文は未決定のまま。提案をAPPROVEDへ昇格するのはOwnerの明示判断。

---

## 3. Task台帳

| Task | Requirements | Base | Head | 変更file | Tests | Demo | 未検証 | Rollback | Review ready |
|---|---|---|---|---|---|---|---|---|---|
| 0 | CS-R* 前提、DG-02提案 | `bd2bfca6` | `ab771b44` | `inventory.md`, requirements, design, tasks, review-ledger, 台帳DISCOVERY | L0 文書照合 | inventoryと提案表 | Owner承認、KPI baseline実測 | spec revert | NO（specのみ） |
| F1 | CS-R1/R2/R3 DDL | — | — | 未着手（WIPあり・未承認） | — | — | APPROVED待ち | 新テーブルDROP | NO |
| F2 | CS-R2 calculator/scope | — | — | 未着手扱い | — | — | 同上 | flag OFF | NO |
| A1 | CS-R1 内部UI | — | — | 未着手扱い | — | — | 同上 | menu削除 | NO |
| A2 | CS-R5 portal | — | — | 未着手扱い | — | — | 同上 | permission未付与 | NO |
| B1 | CS-R2 scheduler | — | — | 未着手扱い | — | — | 同上 | scheduler OFF | NO |
| B2 | CS-R4 health/export | — | — | 未着手扱い | — | — | 同上 | DTO欄空 | NO |
| M | CS-R6 gate | — | — | 未着手扱い | — | — | 同上 | runbook | NO |

### 先行WIP（参考・完了ではない）

以前の会話が APPROVED 前に commit `22d35cc3`〜`eb912340` を push済み。`inventory.md` §8 と `design.md` §9 のギャップが残る。本台帳はそれを COMPLETED と記録しない。

---

## 4. Review finding

（独立Review開始後に記入。実装対話では空。）

| Finding ID | Severity | Requirement | Evidence | Reproduction | Impact | Minimum fix | Regression | Status | Fix commit |
|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — |

---

## 5. Release gate（現状）

- [ ] requirements/design/tasks が **Owner APPROVED**
- [x] 専用worktree / branch `codex/customer-success-service-desk`（通常checkout非使用）
- [ ] DG-02 公式台帳が APPROVED
- [ ] F1〜M が成功条件で `[x]`
- [ ] Base/Head固定、remote一致
- [ ] PLAN PASS → IMPLEMENTATION PASS の独立Review
- [ ] PRはReview PASS後のみ

---

## 6. 独立Reviewへ渡すもの（Task 0 時点）

- approved plan: **無し**（本specは提案）
- requirements / design / tasks / inventory / 本ledger
- 完了対応表: Task 0 のみ
- remote Head: `ab771b445d9bacfc53c8a52f078f1e85ec5cd22c`
- 実装diff: Review対象外（未APPROVED）
