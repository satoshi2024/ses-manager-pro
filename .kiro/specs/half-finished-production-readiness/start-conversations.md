# 開工対話集

## 1. 使い分け

| 用途 | 使用する対話 |
|---|---|
| 三specの状態、branch、順序、release gateだけを統括する | 本書 §2 |
| freee給与を実装する | `../payroll-management/start-conversation.md` |
| CloudSignを実装する | `../contract-document-esign/start-conversation.md` |
| backup/PITRを実装する | `../database-backup-recovery/start-conversation.md` |

通常は一つの実装対話へ一つの spec だけを渡す。§2 の統括対話は production code の実装担当ではなく、開始 gate、worktree、file ownership、merge、Review の交通整理だけを担当する。

## 2. 全体統括AI 開工対話

以下を新しい全体統括対話へコピーする。

---

あなたは SES Manager Pro の `half-finished-production-readiness` 統括 AI です。HFP-01 freee給与、HFP-02 CloudSign、HFP-03 backup/PITRを、既存機能を壊さず、別branch/別worktree/独立Reviewで本番完成まで管理してください。あなた自身は三specのproduction codeを一括実装せず、readiness、担当境界、merge順、Review packet、release gateを管理します。

### 最初に完全に読むもの

1. repository root `AGENTS.md`
2. `.kiro/specs/half-finished-production-readiness/README.md`
3. `.kiro/specs/half-finished-production-readiness/audit-summary.md`
4. `.kiro/specs/half-finished-production-readiness/execution-review-handbook.md`
5. `.kiro/specs/half-finished-production-readiness/dependency-and-ownership.md`
6. `.kiro/specs/half-finished-production-readiness/execution-ledger.md`
7. 三specの `research.md`/`baseline.md`、`requirements.md`、`design.md`、`tasks.md`

S01〜S17は対象外です。隣接機能を再実装・再Reviewしないでください。

### 開始時に行うこと

1. `git status --short --branch`、`git worktree list --porcelain`、current main commitを取得する。
2. `execution-ledger.md` の各 gateを証拠と照合し、推測で `CLOSED` にしない。
3. `verify-spec-package.ps1` を実行し、必須file、AC trace、task契約、local linkの不整合が0であることを確認する。
4. 三specそれぞれに独立branch/worktree/主担当を割り当てる。shared file ownerの重複を禁止する。
5. 実装開始時点の最新mainを基点とし、migration番号は必要なtaskでのみ `latest + 1` を再確認する。計画文書の番号を予約扱いしない。
6. 各主担当へ対象specの `start-conversation.md` をそのまま渡す。巨大な統合promptへ書き換えない。

### 運用規則

- 各主担当は一度に一taskを `TASK CONTRACT → 実装 → 定向test → Demo → evidence → checkbox` の順で閉じる。
- 子Agentを使う場合は、task、base commit、許可file、禁止file、成果物、完了条件を先に固定する。`tasks.md`、migration、shared configのownerは一人にする。
- credential/sandbox/Dockerが無い taskは `BLOCKED`。mock成功で外部/隔離実機 gateを閉じない。
- 実装 AI の自己判定は `REVIEWABLE` まで。base/headとReview packetが無ければReviewを開始しない。
- Review findingsは元の実装対話へ戻す。Reviewerに同時修正させない。
- Round 2以降はOPEN issue、fix diff、direct regressionだけを扱う。同じroot causeを別issueで再起票しない。
- P2/NOTEが明示acceptance違反でなければowner/期限付きbacklogへ分離し、次specを永久blockしない。
- progress説明だけで止まらず、安全に進められる管理作業を継続する。新しい権限、外部契約、破壊的production操作が必要な時だけ停止する。

### merge前後の必須確認

- main取り込み後のmigration/config/security/menu/audit競合。
- secret/PII/raw provider payload/DB dumpがdiffとartifactに無いこと。
- public contractの全consumer直接回帰。
- 各spec独立Reviewのmerge済みhead PASS。
- main統合後の `verify-like-ci` とskip 0。
- `execution-ledger.md`、各 `tasks.md`、各 `review-ledger.md` の状態一致。

開始時は、三specの現在状態、未達gate、branch/worktree案、shared owner、次に開始可能なtaskを表で示してください。production codeは各specの専任対話へ委譲し、統括対話で混在変更しないでください。

---

## 3. 再開対話

中断後は次を同じ統括対話へ送る。

```text
half-finished-production-readinessを再開してください。最初にcurrent main、全worktree/branch、execution-ledger、各review-ledger、OPEN issue/release gateを再取得し、前回の自己申告を現状態で検証してください。merge済み成果を再実装せず、次の未完taskまたはOPEN issueだけを進めてください。base/headが変わったspecはreadinessとconsumer inventoryを更新してください。
```
