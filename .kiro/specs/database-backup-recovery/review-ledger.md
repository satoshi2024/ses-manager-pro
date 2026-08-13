# Review Ledger — HFP-03 正式データバックアップ・PITR

> この ledger は追記式で使用する。checkbox や自己申告だけで PASS にしない。secret、raw dump、個人データ、秘密 URL は記録しない。

## 1. Run metadata

| 項目 | 値 |
|---|---|
| Run ID | NOT_SET |
| Base commit | NOT_SET |
| Reviewed commit/diff | NOT_SET |
| Merge status / merge commit | PRE_MERGE / N/A |
| Implementation actor | NOT_SET |
| Independent reviewer | NOT_SET |
| Started/finished UTC | NOT_SET / NOT_SET |
| MySQL source/target image digest | NOT_SET / NOT_SET |
| Backup tool image digest | NOT_SET |
| Representative profile ID / SHA-256 | NOT_SET / NOT_SET |
| Docker/CI URL | NOT_SET |
| Evidence root | `target/backup-recovery-evidence/<run-id>/` |

## 2. Task ledger

| Task ID | Impl status | Review status | Changed files | Test/Demo | Evidence path + SHA-256 | Finding/Blocker |
|---|---|---|---|---|---|---|
| HFP-03-001 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-002 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-003 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-004 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-005 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-006 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-007 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-008 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-009 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-010 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-011 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-012 | NOT_STARTED | NOT_REVIEWED | | | | |

task status は `NOT_STARTED / IN_PROGRESS / REVIEWABLE / PASS / FAIL / BLOCKED` のみ。必須 task/acceptance を免除して PASS にしない。P2/NOTE の延期は findings へ `DEFERRED` として発注者承認、期限、risk owner、release 影響、代替 control を記録する。

## 3. Requirements trace

| RQ | AC | Owner task | 実装箇所 | 自動 test class/script + case | 隔離 Demo | Review 判定 |
|---|---|---|---|---|---|---|
| HFP-03-RQ-001 | HFP-03-AC-001-01 | HFP-03-001 | | | | NOT_REVIEWED |
| HFP-03-RQ-001 | HFP-03-AC-001-02 | HFP-03-001 | | | | NOT_REVIEWED |
| HFP-03-RQ-001 | HFP-03-AC-001-03 | HFP-03-001 | | | | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-01 | HFP-03-002,003,004 | | | | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-02 | HFP-03-002,003,004 | | | | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-03 | HFP-03-002,003 | | | | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-01 | HFP-03-003 | | | | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-02 | HFP-03-003,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-03 | HFP-03-003,008 | | | | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-01 | HFP-03-004 | | | | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-02 | HFP-03-004,006,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-03 | HFP-03-004,005 | | | | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-01 | HFP-03-006 | | | | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-02 | HFP-03-006,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-03 | HFP-03-006 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-01 | HFP-03-006,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-02 | HFP-03-007 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-03 | HFP-03-006,007,009 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-01 | HFP-03-007 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-02 | HFP-03-008 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-03 | HFP-03-009 | | | | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-01 | HFP-03-001,002,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-02 | HFP-03-002,010 | | | | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-03 | HFP-03-001,010,011 | | | | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-01 | HFP-03-006,010 | | | | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-02 | HFP-03-002,010 | | | | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-03 | HFP-03-010 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-01 | HFP-03-004,005 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-02 | HFP-03-005 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-03 | HFP-03-005,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-01 | HFP-03-007,011,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-02 | HFP-03-011 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-03 | HFP-03-007,011 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-01 | HFP-03-005,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-02 | HFP-03-012 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-03 | HFP-03-009,012 | | | | NOT_REVIEWED |

## 4. Safety/quality gates

| Gate | Result | 実測値/再現 | Evidence SHA | Reviewer note |
|---|---|---|---|---|
| HFP-03-GATE-01 source destructive operation 0 | NOT_RUN | | | |
| HFP-03-GATE-02 target/full/checkpoint selection | NOT_RUN | | | |
| HFP-03-GATE-03 binlog continuity/single connection | NOT_RUN | | | |
| HFP-03-GATE-04 DB/uploads reference/hash | NOT_RUN | | | |
| HFP-03-GATE-05 negative safety suite | NOT_RUN | | | |
| HFP-03-GATE-06 RPO/RTO | NOT_RUN | RPO= / RTO= | | |
| HFP-03-GATE-07 secret/role separation | NOT_RUN | secret matches= | | |
| HFP-03-GATE-08 Docker/drill/CI skip 0 | NOT_RUN | tests= failures= errors= skipped= | | |

## 5. Restore drill timeline

| Segment | Start UTC | End UTC | Duration | Result | Evidence |
|---|---|---|---:|---|---|
| incident/request | | | | NOT_RUN | |
| plan + approval | | | | NOT_RUN | |
| download + integrity | | | | NOT_RUN | |
| DB full + binlog replay | | | | NOT_RUN | |
| uploads staging | | | | NOT_RUN | |
| validation + read-only smoke | | | | NOT_RUN | |
| cutover/read-write approval | | | | NOT_RUN | |

- Requested target UTC: NOT_SET
- Effective checkpoint UTC: NOT_SET
- RPO: NOT_SET
- Base full ID / uploads ID / binlog start-stop: NOT_SET
- Plan SHA / manifest SHA: NOT_SET
- Representative profile ID / SHA / tolerance result: NOT_SET / NOT_SET / NOT_RUN
- Marker before DB/file: NOT_RUN / NOT_RUN
- Marker after DB/file absent: NOT_RUN / NOT_RUN
- Source before/after SHA equal: NOT_RUN

## 6. Findings

| Finding ID | Severity | RQ/Task | File:line | 再現 | 影響 | 推奨修正 | Status |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

Severity は P0（production 破壊/復元不能）、P1（RPO/RTO/security/整合性）、P2（限定的な運用性/監視）、NOTE（要件を破らない非必須改善）とする。P0/P1 または未管理 acceptance が残る場合は全体 PASS にしない。P2/NOTEを延期する場合は発注者承認、owner、期限、release影響を記録する。

finding status は `OPEN / FIXED_BY_IMPLEMENTER / VERIFIED_CLOSED / REJECTED / DEFERRED` とする。実装担当は `FIXED_BY_IMPLEMENTER` まで、独立 Reviewer だけが `VERIFIED_CLOSED` にできる。`DEFERRED` は P2/NOTE に限る。

## 7. Evidence manifest

| Evidence file | SHA-256 | Producer | Redaction/secret scan | Retention/CI artifact |
|---|---|---|---|---|
| | | | | |

## 8. Final decision history（追記）

| UTC | Reviewer | Decision | Open finding/blocker | 根拠 |
|---|---|---|---|---|
| | | NOT_REVIEWED | | |

Decisionは`REVIEWABLE / PASS / FAIL / BLOCKED`のいずれかとする。`REVIEWABLE`はmerge前、`PASS`はmerge済みcommitとmerge deltaを独立Reviewした場合だけ使用する。
