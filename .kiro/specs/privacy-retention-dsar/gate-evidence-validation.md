# gate evidence validation（開発側のhard-stop検証）

## 目的と境界

これは承認、法的判断、Review verdictを生成する文書ではない。approved policy/scope、Privacy owner、approved Base、DG-07、外部/社内gateの実在証跡が提供された場合に、その証跡の必須項目を機械的に検査し、欠落時にF1以降を停止するための開発側validatorである。

validatorはローカルのJSON、spec、inventory、git状態だけを読み取る。DB、filesystem、backup/replica、HTTP、外部providerへ接続せず、`providerCallCount=0`、`writeCount=0`である。validatorの出力は独立ReviewのPLAN/IMPLEMENTATION判定や承認の代替ではない。

## 入力契約

通常の入力ファイルは、承認・gate担当者が別途管理する次のJSONである。未提供時はファイル自体を作成せず、`DECISION_EVIDENCE_MISSING`として扱う。

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

- `HARD_STOP` / exit code `2`: どれかの承認・gate・coverage・git boundaryが欠落または不整合。F1-M、外部provider、本番処分、PRは許可しない。
- `EVIDENCE_PRESENT_REQUIRES_INDEPENDENT_REVIEW` / exit code `0`: 形式上の証跡とcoverageが揃った状態。ただしvalidatorはPLAN PASS、IMPLEMENTATION PASS、法的承認、PR作成を出力しない。
- `canStartF1M`、`canEnableProductionDisposition`、`canCallExternalProvider`、`canCreatePullRequest`は、現実装では常に`false`である。PASS後の手続きは権限者と独立Review側が決める。

coverageは既存scannerを再実行し、`COVERAGE_EXPLICIT`、unclassified `0`、policy unknown `0`、missing/extra column/entity/provider `0`を要求する。構造coverage exit `0`だけではpolicy承認完了とみなさない。

## 実行と現状

```powershell
pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator.ps1
pwsh -NoProfile -File .\tools\privacy-retention-dsar\gate-evidence-validator-test.ps1
```

現branchには承認JSONを配置していない。missing fixtureを用いたテストは、承認証跡欠落、policy unknown 78件、gate欠落、provider/write 0を確認し、`HARD_STOP`で終了する。これにより、この文書やvalidator自体が承認証跡を偽造することを防ぐ。

## provenance境界

`review-ledger.md`と`review-handoff.md`は実装側metadataだけを記録し、外部Reviewのreviewer、timestamp、finding、verdict、sign-offを自己記録しない。独立Review側がremote Headを固定し、外部側のimmutable evidenceへreviewed Head、reviewer/task ID、timestamp、finding ID、verdictをbindする。
