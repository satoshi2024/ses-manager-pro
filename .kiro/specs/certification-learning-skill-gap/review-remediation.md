# PLAN Review指摘対応表

## 判定の前提

直近Review（Head `34f20724`）の判定はPLAN `FAIL`、Implementation `NOT STARTED`です。**PLAN FAIL の理由は実名欠如ではない。** 未達は approved scope、承認 Base SHA、DG-03 業務実値、Status=`APPROVED` のみ。開発段階 OwnerRef=`PROJECT_OWNER`（DecisionId `DG-03-DEV-20260828`、承認 commit `34f20724`）は責任主体識別として **VERIFIED_CLOSED**。

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

`DG-03-DEV-20260828` は Owner 識別ポリシーの Decision であり、資格 PII・証憑 enum・taxonomy・as-of・費用締め・AI/human の業務 Decision ではない。

## 再Reviewの開始条件

1. **OwnerRef=`PROJECT_OWNER`**（`DG-03-DEV-20260828`）の下で、approved scope、Base SHA、対象資格範囲、PII表示/保持、証憑target/scope、taxonomy alias/unknown、as-of event/snapshot、費用route/threshold（締め選択肢A/B含む）、AI/human境界の**実値**を承認する（個人の実名は記録しない）。
2. 中央traceabilityのNF-03を `APPROVED` に更新し、DecisionId、決定日、OwnerRef、対象 scope、Base SHA、承認 commit、KPIを記録する。
3. 本specの候補表を承認値へ更新し、Task 0R-4までの文書補正を確認する。
4. その後にだけF1を開始する。F1以降のtest/Demoが実装されるまでImplementation Reviewは開始しない。

## 現時点の証拠境界

- 確認済み: 専用worktree、remote base/head、`git diff --check`、inventory/spec文書の静的整合、R2 findingの1行対応表。
- 未確認: production implementation、Maven/MySQL、scheduler実行、複数JVM、Document download E2E、threshold fixture、AI timeout、browser Demo。
- したがって本書はPLAN失敗を解消したという意味ではなく、次回PLAN Reviewで再判定可能な設計差分を準備したものとする。
