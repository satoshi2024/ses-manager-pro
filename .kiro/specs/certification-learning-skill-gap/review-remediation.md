# PLAN Review指摘対応表

## 判定の前提

直近Reviewの判定はPLAN `FAIL`、Implementationは未着手です。本書はcandidate specの補正内容を記録するものであり、Review PASSやNF-03 `APPROVED`を宣言しません。中央traceabilityを実装AIが書き換えず、production変更も行いません。

## 指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | `CANDIDATE`、placeholder scope/Owner/Baseを維持。実値のOwner、approved scope、DG-03決定者・決定日・Baseを中央台帳へ記録する必要があることを明記 | `README.md`、`plan.md` §変更許可ゲート | 外部承認。実装AIでは解決不可 |
| NF03-PLAN-P1-02 | supplyは`t_engineer_skill_event`/`t_project_skill_event`、demandは`t_project_position_event`を追加候補とし、current projectionを過去へ遡及適用しない。PROJECT/POSITION/COMBINED precedence、履歴欠落、monthly snapshotを定義 | `inventory.md` §5.1、`design.md` §3.4/§4.4、F1-4/F2-3 | Ownerがevent/snapshot migration scopeとbackfill開始日を承認 |
| NF03-PLAN-P1-03 | `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed linkだけを認可根拠とし、generic `ENGINEER` linkを作らない。mixed-link時はrestricted priority、eventのexact version/hash、CLEAN、FileScopeValidationServiceを必須化 | `inventory.md` §5.2、`design.md` §3.6/§4.2、F1-2/B1 | legal-document側の正式enum・resolver契約を承認し、実装・E2Eで証明 |
| NF03-PLAN-P1-04 | plan planned costは申請snapshot、actual cost/payment/accountingは既存`t_expense_request`/outboxの正本。enrollmentはrelationだけを持つ。NULL/0、税込、差額再承認、締め済み月、支払所有者を定義 | `inventory.md` §2/§5.3、`requirements.md` R2、`design.md` §3.7/§4.5、F1-3/F2-2 | `m_approval_route.min_amount`、zero-cost、tolerance、reopen権限をOwner/Financeが承認 |
| NF03-PLAN-P1-05 | `CORRECTED`をcurrent statusから除外。訂正はrevision/event、EXPIREDはas-of導出、renewはcontinuity groupの新record、current_flag unique、expiry rule version snapshot、row lock＋CASを定義 | `requirements.md` R1、`design.md` §3.5/§5.3、F1-1/F2-1 | state/unique/CASをMySQL並行testで実装証明 |
| NF03-PLAN-P1-06 | semantic keyをrecord revisionではなくrecord＋effective expiry＋threshold＋recipientで構成。注入Clock、lifecycle/active account母集団、退職/休職/復職、manager変更、DB unique/outbox claimを定義 | `requirements.md` R4/R6、`design.md` §3.8/§4.4/§5.1、F2-4 | tenant timezone、復職通知、複数JVM結果をOwner承認後に実測 |
| NF03-PLAN-P1-07 | SELF/MANAGER/HR_FINALを`t_engineer_skill_assessment`で分離し、`t_learning_decision_event`でsource、human actor、reason、snapshot、adverse-useを監査。AI acceptはlearning suggestionだけ | `requirements.md` R7、`design.md` §3.9/§4.6、F1-5/F2-5 | 異議申立て、HR/legal workflow、利用禁止範囲をOwner/HRが承認 |
| NF03-PLAN-P2-01 | 未定義のR8参照を削除し、AI・人の確定境界を正式なR7として追加。tasks/matrixをR3/R7へ同期 | `requirements.md` R7、`tasks.md`、`completion-matrix.md` | re-reviewでID整合を再確認 |
| NF03-PLAN-P2-02 | `issuer_key`、`external_code_key`、`name_key`、NULL codeでも非NULLの`identity_key`、alias、merge reviewを定義。同じskill masterへ資格を登録しない | `design.md` §2/§3.5、`inventory.md` 新設候補 | issuer/name normalizationとmerge権限をOwner承認 |

## 再Reviewの開始条件

1. Ownerが実名・実値でapproved scope、Base、対象資格範囲、PII表示/保持、証憑target/scope、taxonomy alias/unknown、as-of event/snapshot、費用route/threshold、AI/human境界を承認する。
2. 中央traceabilityのNF-03を `APPROVED` に更新し、Decision、決定者、決定日、KPIを記録する。
3. 本specの候補表を承認値へ更新し、`tasks.md`のTask 0Rを完了できる証拠を付ける。
4. その後にだけF1を開始する。F1以降のtest/Demoが実装されるまでImplementation Reviewは開始しない。

## 現時点の証拠境界

- 確認済み: 専用worktree、remote base/head、`git diff --check`、inventory/spec文書の静的整合。
- 未確認: production implementation、Maven/MySQL、scheduler実行、複数JVM、Document download E2E、threshold fixture、AI timeout、browser Demo。
- したがって本書はPLAN失敗を解消したという意味ではなく、次回PLAN Reviewで再判定可能な設計差分を準備したものとする。
