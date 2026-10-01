# NF03 / NF06 / NF07 独立再審査

Reviewer: `astra_review_baseline` / gpt-6-astra。固定Headのread-only審査。新規試験なし。
総括が受領した結果を日本語で記録する。production approvalは含まない。

| 対象Head | 限定判定 |
|---|---|
| NF03 `3202ff23cd133ad3aa4cfdcf9220a6e9cc11cb99` | 製品修復 CODE PASS、並行試験証拠 CONDITIONAL |
| NF06 `bbdbaa836d587b6bb19ef216f6b84ff9f5935651` | PLAN CONDITIONAL、Proposal apply前に下記P1を補正 |
| NF07 `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b` | SYNTHETIC_FOUNDATION技術 PLAN PASS |

## NF03

XMLはMySQL 4/0/0/0、unit 17/0/0/0、inventory 1/0/0/0。
製品側のplan行lockと全非CANCELLED enrollmentの集約修復はPASS。
次のP2が残るため並行証拠を完全閉鎖しない。

- complete/cancelの両順序が直列呼出である。別connectionによる実競合へ置換する。
- lock probeを全DB件数から対象worker transaction、対象plan PK、PRIMARY/RECORD lockとその待機関係へ限定する。
- `@Testcontainers(disabledWithoutDocker=true)`を除去し、Docker必須・zero skippedを守る。
- 初回timeoutの根因は未確定。後続成功からworker起動問題と断定しない。

実装agentへ返却済み。修正Headと新XMLを改めて審査する。

## NF06

旧identity/mapping矛盾、chunk/row transaction矛盾、counter定義は閉鎖。
新P1: Proposal専用入口で`onProposalCreated`を抑止すると、activeな書類選考中proposalと
Bench engineerが併存し、通常作成の業務不変条件を破る。

初期proposal限定・成約遷移禁止・Contract派生禁止は維持するが、必要validation、Engineer派生状態同期、
outcome/auditを同一row transactionまたは信頼できる永続outboxへ含める。
通常作成との後置条件一致、故障時原子rollback、冪等再実行の副作用重複防止を要求する。
parser/schema/合成fixture/read-only validator/registry/fence/previewは先行可能。
child非対応とmanual remediationは初回増分の制約であり、全rollback機能完了ではない。

## NF07

旧P1のhold/dispatch線形化と、不可逆操作前のdurable intent ACKはともに閉鎖。
SF-W1→W2→W3→W4→Mで実装し、各固定Headを独立審査する。
W2開始前に実行可能CAS状態表、W3で認可後hold・送信前後crash・stale worker、
W4で独立journalからの復元・公開拒否を検証する。
合成基盤PlanのPASSであり、DG-07/Privacy/Legal/実provider/production承認を変更しない。
審査済み計画のcontrol統合commitは`010973da`、`cf974a65`。
