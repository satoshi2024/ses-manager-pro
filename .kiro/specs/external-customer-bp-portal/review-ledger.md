# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | （T081 commit） | — | — | — | T081（0. G3/G8と公開field inventory）完了。T082以降はこのHeadから続行 |

## Issue Register

（OPEN項目なし。T081はdocs-onlyのためproduction影響なし）

## Review Packet（T081分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T081（0. G3/G8と公開field inventory）
- base commit: `009b69654035dfb04b991165d1e2ee1c795db087`（HEAD=origin/main）
- changed files:
  - `.kiro/specs/external-customer-bp-portal/field-inventory.md`（新規）
  - `.kiro/specs/external-customer-bp-portal/review-ledger.md`（新規）
  - `.kiro/specs/customer-product-expansion-2026/decision-log.md`（G8行の状態更新＋「G8 決定記録」節の追記）
- requirements trace: 前提節（G3/G8）→ field-inventory §1 / R1.3・R2.1・R3.2・R3.3 → §2〜§3 / R4.3 → §4 / R4.4 → T-6 / R4.5 → T-10
- migration: 本taskは変更なし（V104はT082で作成）
- test evidence: L0。`git diff --check` exit 0。matrix全画面×全fieldに公開可否を付与（§2/§3）、
  公開文書種別がG8 allow-list＋R2.1と一致（§1.2）
- Demo: 社内security・support承認の対象はfield matrixとthreat model（§2〜§5）。規約の外部法務承認はM/本番gate
- skipped/unverified: なし
- known issue IDs: なし
- out-of-scope changes: なし（production code変更なし）
- rollback: 文書のみのため、該当commitのrevertで原状復帰
- requested verdict: intermediate（T081完了確認）

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md（新規）, review-ledger.md（新規）, decision-log.md（G8記録） | L0: `git diff --check` exit 0 | matrix/threat modelレビュー可能状態 | （T081 commit） | なし（docs-only） |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
