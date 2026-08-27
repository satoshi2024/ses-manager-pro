# gate evidence validation（開発側のhard-stop検証）

## 目的と境界

これは承認、法的判断、Review verdictを生成する文書ではない。`DEV_0_D0` と `FULL_FEATURE_PRODUCTION` を分離し、development-only authorizationの範囲だけを検証可能にし、Full Feature/Productionの証跡欠落時はF1以降を停止するための開発側validatorである。

validatorはローカルのJSON、spec、inventory、git状態だけを読み取る。DB、filesystem、backup/replica、HTTP、外部providerへ接続せず、`providerCallCount=0`、`writeCount=0`である。validatorの出力は独立ReviewのPLAN/IMPLEMENTATION判定や承認の代替ではない。

## 入力契約

### DEV-0/D0

`.kiro/specs/privacy-retention-dsar/dev-gate-evidence.json` は、依頼者が提示した `NF07-DEV-GATE-20260828` のdevelopment-only technical authorizationを機械検証する。`APPROVED_DEV_ONLY` のscopeは `Task 0`、`D0`、`0.3`、`0.5` に限定し、Owner roleは `Project Maintainer（development-only）`、formal Privacy Ownerは `UNASSIGNED_UNTIL_PRE_PRODUCTION` とする。これは法的結論、本番承認、Full Feature PLAN PASSではない。

DEV validatorのexit `0`は `DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW` を意味し、DEV-0/D0 scopeだけの独立Implementation Reviewを依頼できる状態である。実行許可はsynthetic/redacted only、external I/O禁止、providerCall/write 0、destructive operation禁止に限る。

### Full Feature / Production

通常の入力ファイルは、正式責任者・gate担当者が別途管理する次のJSONである。未提供時はファイル自体を作成せず、Full validatorは `DECISION_EVIDENCE_MISSING` として扱う。

```json
{
  "schemaVersion": 1,
  "evidence": [
    {
      "id": "approved-policy-scope",
      "status": "APPROVED",
      "scope": "外部で承認された正確なscope",
      "policyVersion": "外部で承認されたversion",
      "owner": "外部で指定された責任者",
      "purposeLegalBasis": "外部で承認されたpurpose/legal basis",
      "decisionAt": "2026-01-01T00:00:00Z",
      "authority": "承認権限の証跡参照",
      "evidenceRef": "immutable evidence reference",
      "evidenceSha256": "64桁のSHA-256"
    }
  ]
}
```

上の値は形式例であり、このbranchの承認値ではない。`<...>`、`UNKNOWN`、`BLOCKED`、`NOT_SET`、`NOT_PROVIDED`、`TBD`、空値は実在証跡として受理しない。

必須recordは次のとおりである。

| id | 必須確認 |
|---|---|
| `approved-policy-scope` | 正確なscope、policy version、owner、purpose/legal basis、decisionAt、authority、evidenceRef、evidenceSha256 |
| `privacy-owner` | accountable owner、role、authority、decisionAt、evidenceRef、evidenceSha256 |
| `approved-base` | approved branch、完全な64桁SHA、decisionAt、authority、evidenceRef、evidenceSha256 |
| `DG-07` | owner、purpose/legal basis、retention、policy version/trigger、hold開始/解除権限、二者分離、対象別処分、DSAR本人確認、同姓同名resolution、第三者redaction、scope、delivery、deadline、reopen |
| `legal-document-ledger-archive` | 未分類3文書種の解消と、storage削除失敗時のresult evidence |
| `database-backup-recovery` | `PROD-001`〜`PROD-008`各証跡とrestore後tombstone再適用evidence |
| `enterprise-identity-security` | identity retention/owner/運用gateの完了証跡 |
| `recruiting-pipeline` | candidate/resume/rejected/activity retentionの完了証跡 |
| `ai-feedback-learning` | G10 allow-list、DPA、region、training opt-out、ownerの完了証跡 |
| `production-disposition-release` | feature flag既定OFF、approved policy allow-list、法務owner、runbook、monitoring、emergency stop |

## 判定契約

- `DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW` / exit code `0`: DEV-0/D0 authorizationの形式とfail-closed boundaryが確認された状態。DEV scopeだけの独立Reviewを要求できるが、Full Feature PLAN、Production PLAN、法的承認、PRを出力しない。
- `EVIDENCE_PRESENT_REQUIRES_INDEPENDENT_REVIEW` / exit code `0`: Fullの形式上の証跡とcoverageが揃った状態。ただしvalidatorはPLAN PASS、IMPLEMENTATION PASS、法的承認、PR作成を出力しない。
- `HARD_STOP` / exit code `2`: どれかの必要証跡・coverage・git boundaryが欠落または不整合。F1-M、外部provider、本番処分、PRは許可しない。
- `canStartDevOnlyScope`だけがDEV-0/D0の形式検証成功時に`true`となる。`canStartF1M`、`canEnableProductionDisposition`、`canCallExternalProvider`、`canCreatePullRequest`は常に`false`である。

coverageは既存scannerを再実行する。DEV modeは構造coverage（unclassified `0`、missing/extra column/entity/provider `0`）を要求し、policy unknown 78件はFull GateのBLOCKED情報として返す。Full modeはさらに`COVERAGE_EXPLICIT`、policy unknown `0`を要求する。構造coverage exit `0`だけではpolicy承認完了とみなさない。

## 実行と現状

```powershell
pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator.ps1 -GateMode DEV_0_D0
pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator.ps1 -GateMode FULL_FEATURE_PRODUCTION -EvidencePath .\path\to\gate-evidence.json
pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator-test.ps1
```

独立Review worktreeはdetachedになるため、Review側でvalidator testを実行する場合は`-AllowDetachedReviewWorktree`を付ける。detachedを許可しても、対象Headとremote branchの一致、clean worktree、scope制限を別途検証し、一致しない場合はHARD_STOPにする。実装branchでの通常実行はこのswitchを付けず、expected branchを必須とする。

現branchにはDEV decision evidenceだけを配置し、Fullのapproved policy/scope、正式Privacy Owner、approved Base、DG-07等の証跡は配置していない。テストはDEV modeの限定authorization、DEV evidence欠落、Full evidence欠落を分離して検証する。現在のpolicy unknown 78件はFull GateをBLOCKEDに保つため、DEV modeのexit `0`をFullの完了とは扱わない。

## provenance境界

`review-ledger.md`と`review-handoff.md`は実装側metadataだけを記録し、外部Reviewのreviewer、timestamp、finding、verdict、sign-offを自己記録しない。独立Review側がremote Headを固定し、外部側のimmutable evidenceへreviewed Head、reviewer/task ID、timestamp、finding ID、verdictをbindする。
