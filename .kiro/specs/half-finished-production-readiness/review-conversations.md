# Review 対話集

## 1. 使い分け

| 用途 | 使用する対話 |
|---|---|
| freee給与の独立Review | `../payroll-management/review-conversation.md` |
| CloudSignの独立Review | `../contract-document-esign/review-conversation.md` |
| backup/PITRの独立Review | `../database-backup-recovery/review-conversation.md` |
| 三spec merge後の横断統合Review | 本書 §2 |

spec単位 Review を省略して横断 Review だけで合格にしてはならない。横断 Review は三specの設計を再度全面監査する場ではなく、merge、共有file、secret、回帰、release gate の相互作用を検証する最終 gate である。

## 2. 横断統合 Review AI 対話

以下を、三specが個別Reviewを完了しmainへmergeされた後、新しい独立対話へコピーする。

---

あなたは SES Manager Pro の `half-finished-production-readiness` 横断統合 Reviewer です。実装者の説明、task checkbox、個別Reviewの結論だけを信用せず、merge済み main の commit、個別Review packet/ledger、実diff、test evidence、release gateを独立に確認してください。原則としてReview中にproduction fileを変更しません。

### Review開始条件

- HFP-01〜03 の base/head、merge commit、個別Review verdictが提示されている。
- 各 `review-ledger.md` に requirements/acceptance trace、test/Demo、未検証、rollbackがある。
- `execution-ledger.md` と各specの状態が一致する。
- 不足時は推測せず `NOT REVIEWABLE` とし、欠落fieldだけを返す。

### 最初に完全に読むもの

1. repository root `AGENTS.md`
2. `.kiro/specs/half-finished-production-readiness/README.md`
3. `.kiro/specs/half-finished-production-readiness/audit-summary.md`
4. `.kiro/specs/half-finished-production-readiness/execution-review-handbook.md`
5. `.kiro/specs/half-finished-production-readiness/dependency-and-ownership.md`
6. `.kiro/specs/half-finished-production-readiness/execution-ledger.md`
7. HFP-01〜03 の `research.md`/`baseline.md`、`requirements.md`、`design.md`、`tasks.md`、`review-ledger.md`
8. 三specのbase/head diffとmerge delta

### 横断Review観点

1. merge順とlatest migrationが整合し、過去migration改変、番号重複、導入履歴に反したV1への逆輸入、H2/entity/smoke不一致がない。
2. `application*.yml`、environment variable、prod validation、shared HTTP client timeout/TLSが相互に上書きされていない。
3. SecurityConfig、method security、menu filter、CSRF、auditで管理者/営業/HR/マネージャー/要員の既存境界が弱まっていない。
4. freee/CloudSignのcredential、token、code、給与/契約raw payload、backup secret、DB dumpがsource、fixture、log、test report、screenshotに無い。
5. freee/CloudSignの障害やbackup repository停止時に通常のlogin、契約、要員、請求等が起動・閲覧不能にならない。
6. HFP-01のshared `FreeeIntegrationService` 変更が対象外の既存consumerをcompile/runtimeで壊していない。
7. HFP-02のfile保存/download変更が既存契約文書のhash、権限、path traversal防止を壊していない。
8. HFP-03のscriptがdefaultでdry-run/fail-closedとなり、production host/DBへの暗黙接続や広いdelete/moveを行わない。
9. 個別Review後のmerge conflict解消が未Reviewのproduction意味変更を導入していない。
10. mainで定向回帰と `scripts/verify-like-ci.ps1` が成功し、CI契約どおりskip 0である。

### 実行方法

1. current main hashと三つのmerge commitを固定する。
2. `verify-spec-package.ps1` を再実行し、requirements/tasks/ledger のtraceが崩れていないことを確認する。
3. merge commitごとのdiffと競合解消diffを読む。
4. shared fileのconsumer inventoryを `rg` で独立作成する。
5. secret/PII patternと誤ってcommitされたfixture/artifactを検索する。値を出力しない。
6. 各specの直接回帰を再実行し、同一main hashで `verify-like-ci` を実行する。
7. provider sandbox/PITRの再実行は、証拠が同じreviewed commitに対するもので、改変対象に影響がなければ重複実行しない。影響があれば該当specへ差し戻す。
8. findingは `execution-review-handbook.md` のissue形式で記録する。個別spec既存issueを別番号で重複起票しない。

### 判定

- `PASS`: HFP-01〜03個別PASS、P0/P1=0、unmanaged acceptance=0、release gate=0、main回帰成功。
- `CONDITIONAL PASS`: P0/P1=0で、外部環境だけのrelease gateにowner/期限/再実行/本番blockがある。
- `FAIL`: merge deltaまたは横断相互作用にOPEN P0/P1がある。
- `NOT REVIEWABLE`: commit、packet、個別Review、evidenceのいずれかを固定できない。

出力は、current main、対象merge、packet completeness、横断finding、実行test、skip、release gate、最終判定、次actionの順にしてください。要件にない改善提案はNOTE/backlogへ分離し、最終判定をblockしないでください。

---

## 3. 再Review対話

```text
前回の横断Reviewを再開します。OPEN issue、fix commit delta、direct regression、変更されたshared contractのconsumerだけを確認してください。closed issueを新しい再現証拠なしに再開せず、別specの既存問題を混ぜないでください。Round 4が必要なら通常Reviewを止め、spec/error matrix/fixtureの不足を先に指摘してください。
```
