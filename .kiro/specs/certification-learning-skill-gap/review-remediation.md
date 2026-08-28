# PLAN Review指摘対応表

## 判定の前提

直近 Review（独立 Plan Review、Head `4e171f19`）: 総合 **FAIL**（Implementation 未着手）/ Plan **PASS** / Implementation **NOT STARTED**。F1 許可（開工対話）。

## R1 Review（Task 0R）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | `CANDIDATE`維持。Gate は**責任主体を一意に識別できる OwnerRef**（開発段階 `PROJECT_OWNER`）と approved scope・DG-03 実値・Base SHA・承認 commit の記録を要求。個人の実名は記録しない | `owner-policy.md`、`README.md`、`plan.md` §変更許可ゲート | **P1-01a（OwnerRef）:** VERIFIED_CLOSED。**P1-01b（scope/Base/DG-03実値/`APPROVED`）:** OPEN |
| NF03-PLAN-P1-02 | supplyは`t_engineer_skill_event`/`t_project_skill_event`、demandは`t_project_position_event`を追加候補とし、current projectionを過去へ遡及適用しない。PROJECT/POSITION/COMBINED precedence、履歴欠落、monthly snapshotを定義 | `inventory.md` §5.1、`design.md` §3.4/§4.4、F1-4/F2-3 | Ownerがevent/snapshot migration scopeとbackfill開始日を承認 |
| NF03-PLAN-P1-03 | `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed linkだけを認可根拠とし、generic `ENGINEER` linkを作らない。mixed-link時はrestricted priority、eventのexact version/hash、CLEAN、FileScopeValidationServiceを必須化 | `inventory.md` §5.2、`design.md` §3.6/§4.2、F1-2/B1 | legal-document側の正式enum・resolver契約を承認し、実装・E2Eで証明 |
| NF03-PLAN-P1-04 | plan planned costは申請snapshot、actual cost/payment/accountingは既存`t_expense_request`/outboxの正本。enrollmentはrelationだけを持つ。NULL/0、税込、差額再承認、締め済み月、支払所有者を定義 | `inventory.md` §2/§5.3、`requirements.md` R2、`design.md` §3.7/§4.5、F1-3/F2-2 | `m_approval_route.min_amount`、zero-cost、tolerance、reopen権限をOwner/Financeが承認 |
| NF03-PLAN-P1-05 | `CORRECTED`をcurrent statusから除外。訂正はrevision/event、EXPIREDはas-of導出、renewはcontinuity groupの新record、current_flag unique、expiry rule version snapshot、row lock＋CASを定義 | `requirements.md` R1、`design.md` §3.5/§5.3、F1-1/F2-1 | state/unique/CASをMySQL並行testで実装証明 |
| NF03-PLAN-P1-06 | semantic keyをrecord revisionではなくrecord＋effective expiry＋threshold＋recipientで構成。注入Clock、lifecycle/active account母集団、退職/休職/復職、manager変更、DB unique/outbox claimを定義 | `requirements.md` R4/R6、`design.md` §3.8/§4.4/§5.1、F2-4 | tenant timezone、復職通知、複数JVM結果をOwner承認後に実測 |
| NF03-PLAN-P1-07 | SELF/MANAGER/HR_FINALを`t_engineer_skill_assessment`で分離し、`t_learning_decision_event`でsource、human actor、reason、snapshot、adverse-useを監査。AI acceptはlearning suggestionだけ | `requirements.md` R7、`design.md` §3.9/§4.6、F1-5/F2-5 | 異議申立て、HR/legal workflow、利用禁止範囲をOwner/HRが承認 |
| NF03-PLAN-P2-01 | 未定義のR8参照を削除し、AI・人の確定境界を正式なR7として追加。tasks/matrixをR3/R7へ同期 | `requirements.md` R7、`tasks.md`、`completion-matrix.md` | re-reviewでID整合を再確認 |
| NF03-PLAN-P2-02 | `issuer_key`、`external_code_key`、`name_key`、NULL codeでも非NULLの`identity_key`、alias、merge reviewを定義。同じskill masterへ資格を登録しない | `design.md` §2/§3.5、`inventory.md` 新設候補 | issuer/name normalizationとmerge権限をOwner承認 |

## R2 Review（Task 0R-2）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | **OPEN**（OwnerRef ポリシーは確定、scope/Base/DG-03 実値は未承認） | 中央台帳 NF-03 `CANDIDATE` | approved scope・Base・DG-03 実値 → `APPROVED` |
| NF03-PLAN-P1-02〜07 | **SPEC_ADDRESSED / 未承認**（R1と同内容。R2で再確認） | 各§参照 | Owner承認＋実装証明 |
| NF03-PLAN-P2-01 | **VERIFIED_CLOSED（spec）** | R7正規化、R8残留なし | — |
| NF03-PLAN-R2-P1-08 | 既存`replaceSkills`/position更新をF1-4/F2-3の必須変更対象にファイル名付きで追加。物理delete→insertとeventの同一txを明記 | `inventory.md` §5.4、`design.md` §3.4、`tasks.md` F1-4/F2-3 | engineer-skill-career/staffing共有境界のOwner承認、実装test |
| NF03-PLAN-R2-P1-09 | `FileScopeValidationService`へ`CERTIFICATION_EVIDENCE`専用分岐（`document-archive`より前）。empty-link・admin bypass・ENGINEER-only mixed link拒否をF1-2/B1 testに列挙 | `inventory.md` §5.5、`design.md` §3.6/§4.2、`tasks.md` F1-2/B1 | enum承認、E2E否定系 |
| NF03-PLAN-R2-P1-10 | 経費締めを`ExpenseRequestServiceImpl`共有化（選択肢A推奨）または研修wrapper（選択肢B）としてdesign §3.7に明記。F2-2 testに締め済み月拒否を固定 | `design.md` §3.7/§4.5、`tasks.md` F2-2 | Owner/FinanceがA/BをDG-03で選択 |
| NF03-PLAN-R2-P2-03 | READMEをTask 0+0R+0R-2完了に更新。migrationは着手時latest+1再確認。inventory §5.1のPROJECT正本をevent表記へ統一 | `README.md`、`inventory.md` §5.1 | F1着手時の実採番 |
| NF03-PLAN-R2-P2-04 | Clock正本を`TenantClock`候補＋Asia/Tokyoへ固定。`AppConfig.systemDefaultZone`非依存をdesign §3.8に明記 | `design.md` §3.8、`tasks.md` F2-4 | tenant TZ設定のOwner承認 |
| NF03-PLAN-R2-P2-05 | `CertificationNotificationPopulationResolver`候補。NF-01 lifecycle case優先、通知除外と履歴閲覧を分離 | `design.md` §3.8 | lifecycle状態式のOwner承認 |
| NF03-PLAN-R2-P2-06 | SELF/MANAGERをstaffing/sales/exportへ出さない。HR_FINALのみ公式projection。decision table §4.6に追加 | `design.md` §3.9/§4.6、`tasks.md` F2-5/A1 | 異議申立てはOwner/HR |

## R3 Review（Task 0R-3）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | **OPEN**（OwnerRef 確定、実値未承認） | 中央台帳 `CANDIDATE` | approved scope・Base・DG-03 実値 |
| NF03-PLAN-R2-P2-03 | **CLOSED（spec）** | README/completion-matrix/plan Gate 0を0R-3まで反映。design §3.4手順3を`t_project_skill_event`/`t_project_position_event`表記へ統一 | — |
| NF03-PLAN-R3-P2-08 | `PositionServiceImpl.delete`をinventory §5.4・F1-4/F2-3フック表に追加。delete前close/cancelled event、as-ofはcurrent補完禁止 | `inventory.md` §5.4、`tasks.md` F1-4/F2-3、`plan.md` | 実装test（F1以降） |
| NF03-PLAN-R2-P2-06（残） | F2-5/A1 testにSELFがstaffing/salesに出ないことを明記 | `tasks.md` F2-5/A1 | 実装test |

## R4 Owner ポリシー（Task 0R-4）

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| 開発段階 Owner 表現 | OwnerRef=`PROJECT_OWNER`、実名非記録、承認証跡フィールドを `owner-policy.md` に確定。Gate 文言を OwnerRef へ統一 | `owner-policy.md`、README、plan、completion-matrix、中央 traceability DG-03 | **NF-03 は `CANDIDATE` 維持**。approved scope・Base・DG-03 実値承認後に `APPROVED` |

## R5 Review（Head `34f20724`）— P1-01 分割判定

| P1-01 部分 | Status | 証跡 |
|---|---|---|
| P1-01a 責任主体識別（OwnerRef） | **VERIFIED_CLOSED** | OwnerRef=`PROJECT_OWNER`、OwnerType=`ROLE`、DecisionId=`DG-03-DEV-20260828`、決定日=2026-08-28、承認 commit=`34f20724`。`owner-policy.md`、中央台帳、traceability DG-03 と一致。実名の追記なし |
| P1-01b approved scope / 承認 Base SHA / DG-03 業務実値 / `APPROVED` | **OPEN** | Status=`CANDIDATE`。scope・DG-03（6項目＋経費締め A/B）未確定。技術比較 base `455fc92e` のみで承認 Base SHA 未記録 |

**Review 評価ルール（開発段階）:** 個人の実名は要求せず、欠如を PLAN FAIL 理由にしない。責任主体は OwnerRef、承認証跡は DecisionId・決定日・OwnerRef・対象 scope・Base SHA・承認 commit。

`DG-03-DEV-20260828` は Owner 識別ポリシーの Decision。業務 Decision は `DG-03-SCOPE-APPROVAL-20260828-01`（approval-decision.md）。

## R6 実装側自己判定（Gate 0 / Task 0G）— 参考

**Reviewed Head:** `03545127`（Gate 0 承認 commit）

実装対話での自己判定。**独立 Review は本節を採用せず**、下記 R7（Head `4e171f19`）を正とする。

## R7 独立 Plan Review（Head `4e171f19`）— PLAN PASS

**Reviewed Head:** `4e171f196e861a3fd849db3aa9f98c1981a6d747`（remote と一致）

**Verdict:** 総合 Review **FAIL**（Implementation 未着手）/ Plan **PASS** / Implementation **NOT STARTED** / F1 **許可**（開工対話で。本 Review 対話では実装しない）

**検証:** merge parents `2abd4efc` + `76e45340`、merge-base `76e45340`、`git diff --check origin/main...HEAD` = 0、`gh pr list` 空。

### P1-01（閉鎖）

| 部分 | Status |
|---|---|
| P1-01a OwnerRef | VERIFIED_CLOSED |
| P1-01b scope/Base/DG-03/`APPROVED` | VERIFIED_CLOSED |

### P1-02〜P1-10 / P2

**APPROVED（spec）** または **VERIFIED_CLOSED（spec）**。REGRESSED なし。実装証明は F1〜M。

### 残リスク（PLAN FAIL ではない）

- `CERTIFICATION_PII` production 保持は NF-07。開発継続可、本番有効化は停止。
- DG-03-1 は AES-256-GCM **または** token — **F1-1 で列形を一つに固定**すること。
- FileScope empty-link/admin bypass は現行コードに残存。F1-2 で `CERTIFICATION_EVIDENCE` 専用分岐を `document-archive` より前に実装（契約維持）。
- latest Flyway **V114**。F1 は **V115+**。

### Next wave

- **F1 開始: YES**（開工対話）
- **PR: NOT CREATED**（M + Implementation Review PASS まで）

## 再Reviewの開始条件（Implementation）

1. F1〜M の task が completion-matrix に evidence 付きで `[x]` 記録される。
2. mandatory test / Demo / CI gate が実行され、結果が review-packet に記録される。
3. その後 Implementation Review を開始する。PASS 後にのみ PR 作成。

## 現時点の証拠境界

- 確認済み: 独立 Plan Review PASS（Head `4e171f19`）、Gate 0 Decision、traceability `APPROVED`、Base merge、spec 静的整合、`git diff --check`。
- 未確認: NF-03 production implementation、Maven/MySQL、scheduler E2E、Document download E2E、browser Demo。
- Implementation Review は F1〜M 完了後に開始する。

## F1 Implementation Review受領・F2持越し（2026-08-28）

独立ReviewのF1 Implementation **PASS**を正式に受領した。Plan Review R7も**PASS**であり、F2〜Mは`NOT STARTED`、F2着手が許可された。F1本体Headは`2f7bbac0`、現worktreeのlocal/remote Headは`f73fcbc23852daa75f8224f8cc411418db4938f1`、現行migrationはV119、F2はV120+を使用する。PR、merge、branch削除は引き続き禁止する。

### 持越し項目のF2接続

| 持越し | F2契約 | 対応箇所 | 状態 |
|---|---|---|---|
| `TYPE_DELETE`／DELETE当日as-of | delete前cancel/close event、DELETE当日をeffective intervalに含め、削除後current fallbackを禁止 | `completion-matrix.md`、F2-3/M | 未検証 |
| feature開始日前position update | history欠落時`historical_data_unavailable`、現行positionの過去補完禁止 | `completion-matrix.md`、F2-3/M | 未検証 |
| legal hold | certification evidenceのdownload/export/disposalをhold中fail closed、DocumentService/FileScope双方で再検証 | `completion-matrix.md`、F2-1/M | 未検証 |
| 証憑version pin | event記録のdocument version ID/hashと要求版を完全一致、CLEAN必須 | `completion-matrix.md`、F2-1/M | 未検証 |
| production `certification.pii.view` permission seed | production seed、未seed時full reveal fail closed、role別実API確認 | `completion-matrix.md`、F2-1/M | 未検証 |
| BP/別write pathのevent insert迂回防止 | skill/project/positionの全write pathを共通event writerへ集約し、直接mapper更新を検出 | `completion-matrix.md`、F2-3/M | 未検証 |
| PR前最新`origin/main`取り込み・migration衝突 | PR直前にfetch＋最新base取り込み、V120+とのmigration/schema/H2衝突を再確認 | `completion-matrix.md`、M/PR前gate | F2中は未実施 |

この表の項目は未追跡のまま落とさず、各F2 Taskのrequired testまたはMの明示的gateで `[x]` と証拠を付ける。F2実装中の基準は現worktree V119／V120+であり、最新`origin/main`の再取り込みはPR前gateで行う。
