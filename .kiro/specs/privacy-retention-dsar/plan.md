# privacy-retention-dsar 実施計画

## approval status

この計画は二段階Gateで管理する。`NF07-DEV-GATE-20260828`（依頼者によるdevelopment-only technical authorization）により、**DEV-0/D0 PLAN: `APPROVED_DEV_ONLY`** とする。**FULL_FEATURE_PRODUCTION PLAN: `BLOCKED`** のまま維持する。これは法的結論、本番承認、正式Privacy Ownerの指定、Full Feature PLAN PASSではない。

| gate | status | scope / owner | hard stop |
|---|---|---|---|
| DEV-0/D0 | `APPROVED_DEV_ONLY` | Task 0、D0、0.3、0.5。Project Maintainer（development-only） | synthetic/redacted only、external I/O=禁止、providerCall/write=0、destructive operation=禁止 |
| Full Feature / Production | `BLOCKED` | F1-M、本番処分、正式Privacy Owner、DG-07/外部gate | 78 table policy、DG-07、document、backup/recovery、identity、recruiting、AI G10、本番運用gateの実在証跡まで停止 |

正式Privacy Ownerは `UNASSIGNED_UNTIL_PRE_PRODUCTION` のままである。approved policy/scope、approved Base branch/SHA、正式ownerの証跡を推測で補完しない。DEV-0/D0 PLANはこの明示Decisionの範囲に限りPASS相当として独立Reviewへ渡すが、Full Feature PLANおよびProductionはPASSにしない。

技術上の比較境界は `origin/main@f131f51c50dbfb68ffc8e71878da52947560c80e`、開始時merge-baseは `0333b0a4afadef42639bad27e1ae443758f9804f` と記録する。ただしこれは承認済みBaseではない。approved Base evidenceが到着するまでFull Feature/Production PLAN PASSにしない。DEV-0/D0の独立Review範囲は、この明示SHAからremote feature Headへのdiffとして再現する。

## ordered increments

| 順序 | increment | 成果 | 開始条件 | 現在 |
|---|---|---|---|---|
| 0 | legal/PII inventory | table/column/file/AI payload catalog、owner/purpose/trigger/retention/hold/disposition/provider | DEV-0/D0 Gate、読み取り可能なrepo evidence、全migration/entity/providerのcoverage hash | DEV-ONLY APPROVED（0.4 policy closureは未完。unclassified 0、policy UNKNOWN/BLOCKED 78、fail-closed） |
| D0 | dry-run | redacted snapshotのcandidate/blocked/unknown、no-write evidence | DEV-0/D0 Gate。実データ/provider接続なし | DEV-ONLY APPROVED（offline） |
| F1 | catalog/policy/hold/request/job DDL | versioned policy、hold、case/action/job | DG-07とapproved scope/owner、migration approval | BLOCKED |
| F2 | provider/search/dry-run | DB/file/AI/backup/replica provider、scope/redaction | F1、external provider scope/security approval | BLOCKED |
| A1 | dashboard/hold/approval | hold表示、二者承認、SoD、audit | F2、legal/HR/Privacy owner approval | BLOCKED |
| A2 | DSAR case/export/redaction | identity resolution、第三者redaction、delivery/appeal | A1、期限/identity/export policy approval | BLOCKED |
| B1 | disposition batch | flag OFF、policy allow-list、CAS/idempotency/retry/partial failure/cancel | A2、backup/restoreと処分方式のgate | BLOCKED |
| B2 | recovery/evidence | DB+binary restore、hash/scope/hold再検証、evidence | B1、backup/recovery gate | BLOCKED |
| M | release | production enablement候補 | independent ReviewのPLAN/IMPLEMENTATION双方PASS、全外部/社内gate | BLOCKED |

## hard stop

- 法定保持・legal hold・監査保持・active business blocker・scope不明はfail-closed。
- 同姓同名は自動統合せず、verified identityと人のresolutionを要求する。
- 第三者情報をexportしない。redaction不能ならblocked。
- scope外provider、実AI、CloudSign、freee、mailbox、backup/replicaへ呼出ししない。
- 本番処分flagは、release後も既定OFF。承認済みpolicy allow-listだけを明示的に有効化する。
- coverage未完了、明示provider未登録、source hash不一致、result evidence欠落は `UNKNOWN/BLOCKED` とし、候補化しない。
- legal-document-ledger-archiveの未分類3文書、storage削除失敗時のresult evidence、database-backup-recovery PROD-001〜008とrestore後tombstone再適用、identity、recruiting、AI G10 gate、法務owner、runbook、monitoring、emergency stopを未完了のblocking条件として扱う。

## handoff

Reviewへはこのplan、requirements/design/tasks、inventory、dry-run、completion mapping、review ledger、DEV gate evidence、最終remote Headを渡す。今回の独立Review依頼scopeはDEV-0/D0のみである。実装対話ではPRを作成しない。Full Feature/ProductionのPLAN PASSおよびPR作成は行わない。
