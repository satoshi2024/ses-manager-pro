# privacy-retention-dsar 実施計画

## 2026-11-14 production bridge 段階計画（W3-04 NF07-P）

依頼者の決定 Q-1（段階化）と Q-8（署名前実装可: 値UNKNOWN・flag OFF・未知は一律拒否）により、F1〜M の実装着手は署名を待たない。第1期は W3-14「F1 + PERM」→ F2 provider / A1 Journal adapter → A1 保全hold → A2 DSAR（半手動）、署名後は B1 自動処分・B2 recovery・M release とする。詳細は [`production-bridge-phased-plan.md`](production-bridge-phased-plan.md)。下記 approval status の `FULL_FEATURE_PRODUCTION PLAN` は **本番承認** の状態として `BLOCKED` を維持する（本番開放・処分・実交付・外部送信は引き続き禁止）。実装着手の可否だけが変わった。

## 2026-10-01 追加技術increment

SYNTHETIC_FOUNDATIONを旧DEV-0/D0と分けて計画する。依頼者による技術方式・隔離開発の委任が入力であり、
正式Privacy OwnerやDG-07を代行していない。正本`synthetic-foundation.md`、新requirements SF-R1〜6、
計画固定Head `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b`はAstraの技術Plan Review PASS。
SF-W0完了。SF-W1は実装済みで、実装側記録では固定Head `6ec4ca28` をAstraが限定CODE PASS（18/0/0/0）としたが、正式verdictは外部記録で確認する（[`status-alignment.md`](status-alignment.md) C-04）。初期base `1443fb68943849a3260d7ebe005259f3eb609d60`。
下記の古いbase/件数/実装scopeは当時の記録であり、現行coverageや新incrementの承認と混同しない。
B0/V182時点（2026-10、execution-ledger `:558`）のscanは253 migration table、244 entity table、699 provider候補、未分類73 table、欠落column1077、
欠落provider276、missing entity68でBLOCKED_COVERAGE_INCOMPLETE。これは現行値ではない。2026-11-14時点の基準 `1be03bea`（V183〜V186を含む）に対するscanは未実施であり、現行coverageは **UNKNOWN（BLOCKED想定）** とする（C-15）。policy unknownの正式判定は別途必要。
NF03/NF08のpolicy依存は継続し、実retention/外送を未承認のまま有効化しない。

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
| F1 | catalog/policy/hold/request/job DDL | versioned policy、hold、case/action/job | DG-07とapproved scope/owner、migration approval | 2026-11-14: 第1期（W3-14）。実装着手可（Q-8、値UNKNOWN・flag OFF）。disposition job表はB1へ移す。本番開放はBLOCKED |
| F2 | provider/search/dry-run | DB/file/AI/backup/replica provider、scope/redaction | F1、external provider scope/security approval | 2026-11-14: 第1期。read-onlyのみ実装着手可。外部providerは登録しない。本番開放はBLOCKED |
| A1 | dashboard/hold/approval | hold表示、二者承認、SoD、audit | F2、legal/HR/Privacy owner approval | 2026-11-14: 第1期（Journal adapter + hold開始）。hold解除は署名後。本番開放はBLOCKED |
| A2 | DSAR case/export/redaction | identity resolution、第三者redaction、delivery/appeal | A1、期限/identity/export policy approval | 2026-11-14: 第1期（半手動）。前提はF2（A1は表示のみ）。本番開放はBLOCKED |
| B1 | disposition batch | flag OFF、policy allow-list、CAS/idempotency/retry/partial failure/cancel | A2、backup/restoreと処分方式のgate | BLOCKED（署名後に着手） |
| B2 | recovery/evidence | DB+binary restore、hash/scope/hold再検証、evidence | B1、backup/recovery gate | BLOCKED（署名後に着手） |
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
