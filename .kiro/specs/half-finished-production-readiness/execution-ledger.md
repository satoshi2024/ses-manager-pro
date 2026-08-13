# 中央実行 ledger

## 1. 現在状態

- 計画基点: `99fbed8294dd1a6c320b4413b832f7c7b9292da1`
- 計画 branch: `codex/half-finished-readiness-specs`
- 最終更新日: 2026-08-13

| ID | spec | spec状態 | implementation状態 | 開始 gate | base/head | Review | 次 action |
|---|---|---|---|---|---|---|---|
| HFP-01 | payroll-management | READY | NOT_READY | freee test company/API spike | `be98790c` / plan `9c8141d8` | rebase後commit固定Review PASS | gate確認後、HFP-01-001を実行 |
| HFP-02 | contract-document-esign | READY | NOT_READY | CloudSign正式API/sandbox spike | `be98790c` / plan `9c8141d8` | rebase後commit固定Review PASS | gate確認後、HFP-02-00を実行 |
| HFP-03 | database-backup-recovery | READY | NOT_READY | 隔離MySQL/repository/RPO-RTO決定 | `be98790c` / plan `9c8141d8` | rebase後commit固定Review PASS | gate確認後、HFP-03-001を実行 |

`spec状態` は本計画 branch の独立文書 Review 後に `READY` へ変更する。production 実装の開始 gate が未達なら、spec が READY でも implementation は NOT READY のままとする。

## 2. 固定 decision

| decision ID | 決定 | 根拠 | 変更条件 |
|---|---|---|---|
| HFP-D001 | 対象は freee給与、CloudSign、backup/PITR の3件だけ | 2026-08-12 source/spec監査 | 新しい再現証拠と発注者承認 |
| HFP-D002 | S01〜S17は対象外 | 発注者明示指示 | 発注者の明示変更 |
| HFP-D003 | freee給与一期は読み取り専用で金額を永続化しない | 既存要件とfreee API能力 | privacy/retentionを定めた別spec |
| HFP-D004 | provider adapterは公式契約とsandbox spikeの後に実装 | 誤endpoint/誤文書送信防止 | 変更不可 |
| HFP-D005 | 三specは別branch/worktree/主担当、独立Review | shared diffと判定の分離 | coordinatorが理由を記録 |
| HFP-D006 | Reviewは有限差分方式、P2/NOTEは要件違反でなければ次工程を永久blockしない | 指摘の重複と後付け要件防止 | 変更不可 |
| HFP-D007 | PITRのproduction applyは本実装/ReviewのDemo対象外。隔離環境のみで破壊的操作を行う | 本番data保護 | 正式change ticketと二者承認 |

## 3. Release gate register

| gate ID | spec | 条件 | owner | 期限 | 証拠 | block範囲 | 状態 |
|---|---|---|---|---|---|---|---|
| HFP-G01 | HFP-01 | freee test companyで users/me・employee・salary・bonus を取得 | 未設定 | 未設定 | mask済みspike記録 | provider adapter/最終PASS | OPEN |
| HFP-G02 | HFP-02 | CloudSign正式API文書、credential、sandbox送受信を確認 | 未設定 | 未設定 | mask済みspike記録 | provider adapter/最終PASS | OPEN |
| HFP-G03 | HFP-03 | 隔離MySQL 8 + repository + uploads fixtureでPITR可能 | 未設定 | 未設定 | drill artifact | 最終PASS | OPEN |
| HFP-G04 | ALL | merge後 `verify-like-ci` zero failure/error/skip | 未設定 | merge時 | command log | 全体release | OPEN |

## 4. spec完了 packet

各主担当は実装完了時に次を一行ずつ追記し、既存行を書き換えて履歴を消さない。

```text
### <date> <HFP-ID> REVIEWABLE
- base/head:
- completed task IDs:
- requirements/acceptance trace:
- changed files:
- test evidence:
- sandbox/isolated Demo evidence:
- skipped/unverified:
- open gates/issues:
- rollback:
- requested review:
```

## 5. Review履歴

独立 Reviewer が次を追記する。

```text
### <date> <HFP-ID> Review Round <n>
- base/head:
- packet completeness:
- verdict:
- P0/P1/P2/NOTE count:
- open issue IDs:
- verified evidence:
- release gate changes:
- next action:
```

## 6. 全体完了条件

- HFP-01〜03 の各 specが merge済み head で `PASS`。
- HFP-G01〜04 が `CLOSED`。
- 未管理 acceptance、OPEN P0/P1、秘密情報混入が0。
- main統合後の直接回帰と `verify-like-ci` が成功。
- 本 ledger と各 `tasks.md` / `review-ledger.md` の状態が一致。
