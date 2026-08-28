# 実装plan（candidate）

## Gate 0: 承認前の準備

1. worktree/root/branch/status/remote/baseを検証する。
2. 指定された依存specとNF-03のbacklog/requirements-design/traceabilityを読む。
3. 資格・skill・career・training・staffing・document・approval・self-serviceをinventoryする。
4. 重複master回避、PII、DocumentLink、taxonomy、as-of、費用approval、AI境界をdecision tableにする。
5. `tasks.md` と完了対応表を作成する。

Gate 0はproduction変更なし、文書の自己検証、Task 0のcommit/pushまでで完了とする。

Review remediationでは、承認前に次の候補契約を曖昧なまま残さない。

- supply (`t_engineer_skill`)、project skill、position demandはcurrent projectionとappend-only effective eventを併存させ、履歴欠落時はcurrent fallbackをしない。
- `CERTIFICATION_EVIDENCE`は`CERTIFICATION_RECORD` typed DocumentLinkだけを認可し、generic `ENGINEER` linkとのmixed-link OR-unionをrestricted policyで遮断する。eventのexact document version/hashとCLEANを再検証する。
- course予定額はlearning planの申請時snapshot、actual cost・payment・accountingは既存`t_expense_request`の正本とし、enrollmentへactual costを複製しない。
- `CORRECTED`は資格current statusではなくevent。renewはcontinuity groupの新record、EXPIREDはas-ofから導出する。
- notification keyはsemantic expiry date＋threshold＋recipient、注入Clock、lifecycle/active account population、DB unique＋outbox claimを使う。
- SELF/MANAGER/HR_FINAL assessmentと人のdecision eventを分離し、AI candidateから評価・配置・採否・不利益判断への直接遷移を禁止する。
- 既存skill/position書込み（`EngineerSkillServiceImpl`/`ProjectSkillServiceImpl`/`PositionServiceImpl`）をas-of eventの必須フック対象とする。
- `FileScopeValidationService`へ`CERTIFICATION_EVIDENCE`専用分岐を追加し、empty-link・admin bypassを資格証憑で禁止する。
- 経費締めは`ExpenseRequestServiceImpl`共有化（選択肢A）または研修wrapper（選択肢B）をDG-03でOwner/Financeが選択する。

## Gate 1: F1 DDL（承認後のみ）

資格master、engineer取得record、append-only event、course、course-skill、learning plan、plan-skill、enrollment、effective history、assessment、decision eventを確定する。migration番号は実装開始時のlatest+1、V1/H2専用schema/entity同期を設計に従って実施する。PII field、自然同一性、continuity/current unique、version/CAS、exact document version、expense relationを先に固定する。

## Gate 2: F2 service（承認後のみ）

取得・期限・cancel/correct/renew・duplicate防止、course/plan/enrollment state、既存ExpenseRequest連携、typed DocumentLink、approval adapter、effective as-of skill gap、synonym/unknown、rule fallback、scheduler population/dedupe、人のassessmentを実装する。scope checkはcontrollerだけでなくservice/file validationにも置く。

## Gate 3: A1/A2 UI（承認後のみ）

- A1: HR/managerの資格、期限、training、gap list/detail。
- A2: 本人の取得申請、証憑、learning plan、enrollment。

list/detail/exportで同じpopulationを使い、番号masking、390px表示、empty state、safePage、link解決を確認する。

## Gate 4: B1/B2連携（承認後のみ）

- B1: 90/60/30通知、approval route、DocumentService/scan/legal hold/download。
- B2: staffing demandとのas-of連携、taxonomy alias、unknown、AI candidate。AIは候補表示のみで、評価・配置のfinal decisionを人のworkflowへ残す。

## Gate 5: M completion（承認後のみ）

mandatory tests、Demo evidence、population matrix、migration/H2/MySQL gates、security/scope、remote HEADを確認する。M後にのみReview packetを作成し、ReviewのPLAN/IMPLEMENTATION双方PASS後にPR作成工程へ渡す。

## 変更許可ゲート

以下が全て実値で `APPROVED` になるまでGate 1以降に進まない。candidate方針を文書へ書いたことは承認の代替ではない。

- NF-03 traceability status
- approved scope
- Owner
- base commit/branch
- DG-03の6 decision（番号、証憑、taxonomy、as-of、費用、AI）
- Review/統合担当が要求する追加acceptance criteria

P1-01は実装AIが解決できない外部gateである。Ownerが中央traceabilityへ決定者・決定日・scope・baseを記録した後、Reviewを再実行する。
