# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`T060 FIX DELTA READY / R10 Round 4 RE-REVIEW_REQUIRED`。発注者の2026-08-09付`G2-DEV-GATE`正式決定により、
特定自然人の事前指名または実actor承認eventをT060の開発完了条件から外し、`ACTIVE`化、T066 M PASS、
本番交付のrelease gateへ移した。P1-01は`VERIFIED_CLOSED`を維持し、P1-02は実装側で
`FIXED_BY_DECISION_CHANGE`。R10が決定正本、spec、決定表、L0 matrix、派工対話の同期を確認するまで
Review statusは`OPEN / RE-REVIEW_REQUIRED`とし、T061/V82は開始しない。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5、G2-DEV-GATE | G2正本/Decision Log/README、dispatch `requirements.md`/`design.md`/`tasks.md`/`field-mapping.md`/`review-ledger.md`、S10/R10/T060派工対話 | **PASS**: mapping 96行、SRC-E ⑱=1、SRC-L ④=1、根拠なし2026-10行=0。gate L0はlifecycle 4状態/test matrix 7件/実actor承認event非block/M・本番fail-closedを確認。`SpecDispatchConsistencyTest` 8/8、`git diff --check` exit 0 | Base `1fd0f7492ab46388c961e2e721ccdedd416929c4` → Headはpush後に本ledgerへ追記 | runtime assignment/承認event/外部専門家Review未取得では`ACTIVE`化・M PASS・本番交付を禁止。rollbackはG2-DEV-GATE fix commitをrevert。production code/DDL/DB変更なし |

## R10 Issue Register

| issue ID | Review status | Implementer status | violated / location | fix evidence | verification / next action |
|---|---|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R1-P1-01 | **VERIFIED_CLOSED** | **FIXED_BY_IMPLEMENTER** | T060 Objective/L0全項目網羅、R2.1／field-mapping.md SRC-E section・SRC-L section・2026-10行 | SRC-E「社会保険の加入手続きが完了していない場合の理由（⑱）」とSRC-L「60歳以上か否かの別（④）」を独立mapping行へ追加。4公式PDFに確認できない2026-10通知行を一次source特定gateへ戻した | R10 Round 2確認済み。新fixでも96行、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0を維持する |
| dispatch-outsourcing-compliance-ledger-R1-P1-02 | **OPEN / RE-REVIEW_REQUIRED** | **FIXED_BY_DECISION_CHANGE** | 旧T060 Demo／実actor・承認日時・mapping hash証跡を開発gateとして要求 | `G2-DEV-GATE`を正本へ追加し、`DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`をrequirements/design/tasks/field mapping/L0/派工対話へ同期。特定自然人はruntime assignmentで交代可能とし、承認eventは対象version/hashへ監査snapshotとして保存 | R10は旧approval eventの提出を要求せず、正本同期、provisional完了条件、M/本番fail-closed、対象hash不一致拒否を確認する。合格時は`VERIFIED_CLOSED_BY_DECISION_CHANGE` |

## R10 Round 3 履歴

- 判定: `FAIL: open blockers=dispatch-outsourcing-compliance-ledger-R1-P1-02`。
- P1-01は`VERIFIED_CLOSED`、P1-02は`OPEN / APPROVAL_REQUIRED`。対象mapping blobは
  `80fe732df1553f5d9a21b6776d8288419f29d9cc`だった。
- 実actor承認eventがなく、当時の正本ではT060/F1を`[ ]`、T061/V82未開始とした。
- c34ba6f以降にfix deltaまたは承認eventがなく、収束規則により通常Reviewを停止した。
- 本履歴は改変せず、2026-08-09の正式決定変更をRound 4の新deltaとしてReviewする。

## G2-DEV-GATE後の再開条件

- T060実装側checkboxは`[x]`。ただし独立Review合格前にT061/V82を開始しない。
- R10 Round 4は、G2正本、requirements、designのmapping state machine、tasksのDemo/L0/M、field-mappingの
  lifecycle、S10/R10/T060派工対話が同一の二段階gateを示すことを確認する。
- `PROVISIONAL_REVIEWED`は公式source/版/確認日/effective period、全field mapping、L0、独立Review、
  mapping blob/hash固定で成立し、実actor承認eventを要求しない。
- `ACTIVE`化、T066 M PASS、本番交付は、activeな`COMPLIANCE_RESPONSIBLE` assignment、対象version/hashへの
  実actor承認event、外部専門家Reviewがすべて揃うまでfail-closedとする。
- R10がP1-02を`VERIFIED_CLOSED_BY_DECISION_CHANGE`としT060をPASSにした後、T061開始時にmerge済み
  `db/migration`のlatestを再確認する。

## 継続するM / 本番gate

- `GATE-T060-2026-10`: 待遇差説明を求める権利の正確な一次source、文言、対象範囲。旧版へ遡及しない。
- `GATE-T060-RETENTION`: 個別契約書・就業条件明示書・派遣先通知書のarchive category/保存起算点。
- `GATE-T060-COOLING`: クーリング期間値と組織単位変更の同一性基準。
- `GATE-T060-EXTERNAL`: 外部社労士/弁護士Review。T066 M PASSおよび本番解放前に必須。
