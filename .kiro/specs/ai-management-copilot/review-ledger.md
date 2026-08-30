# AI Management Copilot Review Ledger

## 0. Review state

| 項目 | 状態 |
|---|---|
| Review type | 独立Review待ち。まずPLAN Review、実装後にIMPLEMENTATION Review |
| Implementation state | **NOT STARTED / DISCOVERY ONLY** |
| Central NF-08 state | `CANDIDATE` |
| Existing AI learning state | `CONDITIONAL PASS`（P2残、GATE-S17-G10-PROD保留） |
| NF-07 | 未完・retention/PII inventory承認待ち |
| DG-08 | 未完・provider/DPA/越境/owner/role/retention/cost/escalation未確定 |
| Provider allowed now | local mock/rule only |
| External send | OFF。`ai.external-send-enabled=false`を維持 |
| Feature flag | management copilot OFF |
| PR | 作成しない |
| Base | `origin/main@0c122d33d4c90176601cf6dbdd9507c5c89ce5ee` |
| Working branch | `codex/ai-management-copilot` |
| Working tree | `C:\work\ses-manager-pro-ai-management-copilot`、開始時cleanを検証 |
| Remote | `https://github.com/satoshi2024/ses-manager-pro.git` |

## 1. 承認入力の未解決表

| input | ユーザー提示値 | 実装開始に必要な証跡 | 状態 |
|---|---|---|---|
| approved query catalog / roles / provider | `<APPROVED_SCOPE>` | query ID、parameter schema、role、scope、provider、owner、version | 未解決 |
| Owner | `<OWNER>` | catalog/gate/evaluationの責任者 | 未解決 |
| Base branch | `<BASE_BRANCH>` | branch名とcommitの対応 | 未解決（実体はorigin/mainを検証） |
| Base commit | `<BASE_COMMIT>` | immutable commit | placeholderだが実体は`0c122d33d4c90176601cf6dbdd9507c5c89ce5ee`として検証 |
| NF-07 | 未指定 | PII inventory、allowlist、retention、purge、processor条件 | 未完 |
| existing AI production gate | `GATE-S17-G10-PROD` | G10/DPA/越境/training opt-out/送信flag/production evidence | 保留 |
| DG-08 | 未指定 | provider/owner/role/retention/cost/human escalationのDecision | 未完 |

## 2. 完了対応表（Discovery handoff）

| ユーザー要求 | 対応文書/証跡 | 状態 |
|---|---|---|
| 専用worktree/branch、normal checkout保全 | 開始時git検証、`requirements.md` 0、`tasks.md` T000 | 完了（Discovery） |
| 開始時root/branch/status/remote/base検証 | worktree root、branch、clean status、origin URL、HEAD/merge-base確認 | 完了 |
| AGENTS.md全文 | 読了。日本語、Flyway、test、AI、scope、UI規約を反映 | 完了 |
| NF-08/DG-08/受入後3文書 | requirements/design/start/review/traceabilityを読了し、gate未完を反映 | 完了（gateは未完） |
| platform-invariants | `design.md`の時間/scope/state表と設計へ反映 | 完了（Discovery） |
| ai-feedback-learning | existing gateway/run/feedback/evaluation、CONDITIONAL PASS、P2/G10保留を反映 | 完了（gateは未完） |
| AI PII allow-list | allowlist、canary、raw prompt 0日、mock/rule限定を反映 | 完了（実装は未着手） |
| canonical service | Dashboard/UtilizationForecast/ManagementAccounting/CashFlow/SalesPerformance/DataScopeの正本利用方針を反映 | 完了（実装は未着手） |
| 固定pipeline | `requirements.md` R7、`design.md` §1 | 完了（設計） |
| catalog外SQL/table/column禁止 | R3、設計§2 | 完了（設計） |
| typed result・円/割合/期間/timezone/freshness/forecast | R6、設計§6 | 完了（設計） |
| 画面/export/AI metric一致 | R10、F2、design§13 | 未実装・受入条件化 |
| scope A/B | R5、design§4、F2 | 未実装・受入条件化 |
| prompt injection / 0/NULL / 巨大result | R2/R6/R10、B2 | 未実装・受入条件化 |
| provider 429/timeout/invalid JSON | R8、design§11、B1 | 未実装・受入条件化 |
| scope外ID/PII推測防止、citation再認可 | R5/R7、design§10 | 未実装・受入条件化 |
| feedback/model/prompt/data version/cost/latency記録 | R9、design§9、F1/B1/B2 | 未実装・受入条件化 |
| mock/ruleまで、外部AI無効 | R1/R8、design§7-8、Review state | 完了（設定変更なし） |
| 完了後もgate未完ならCONDITIONAL PASS・flag OFF | R10、tasks M、ledger state | 確定 |

## 3. Task completion

| task | status | commit | evidence |
|---|---|---|---|
| T000 Discovery/gate/inventory | DONE | 作成commitで確定 | 文書読了、worktree検証、production code差分なし |
| F1 catalog/run/feedback | BLOCKED | — | NF-07/DG-08/owner/approved scope/production gate待ち |
| F2 intent/parameter/scope/service gateway | BLOCKED | — | F1、scope承認、SalesPerformance scoped adapter待ち |
| A1 chat/answer/citation UI | BLOCKED | — | F2、citation/human escalation承認待ち |
| B1 provider/redaction/timeout/cost | BLOCKED | — | gate/provider policy待ち。mock/rule以外禁止 |
| B2 evaluation/adversarial | BLOCKED | — | B1、dataset/segment/owner/budget承認待ち |
| M integration/review handoff | BLOCKED | — | F1〜B2と全production gate待ち |

## 4. Review handoff contract

### Plan Review

Review担当は、次を最初に判定する。

1. 未解決placeholderを推測せず、CANDIDATEとして停止できているか。
2. catalog runtimeがSQL/table/column/任意beanを実行できない設計か。
3. typed result、正本service、scope A/B、SalesPerformanceのscope gap、citation再認可が設計されているか。
4. PII allowlist、raw prompt 0日、mock/rule、external flag OFF、NF-07/DG-08/既存gate保留が一貫しているか。
5. feedback、model/prompt/data version、cost、latency、retention、human escalationの受入条件があるか。
6. scope A/B、prompt injection、0/NULL、巨大result、429/timeout/invalid JSON/partial citation/PII canary、metric contractのtest計画があるか。

Plan ReviewがPASSになるまで、F1以降のproduction codeを開始しない。

### Implementation Review

実装後にのみ実施する。最終remote Head、task単位commit、test gate、flag/gate状態、migration、ログcanary、scope proof、contract test、rollbackを独立worktreeで再検証する。`PLAN PASS → IMPLEMENTATION PASS`の両方が揃うまでPRを作成しない。

## 5. 次のhandoff

このcommitをpushした後のremote Headと本ledger、`requirements.md`、`design.md`、`tasks.md`を独立Reviewへ渡す。現段階の判定は`PLAN REVIEW REQUESTED / IMPLEMENTATION NOT READY`であり、production実装完了やproduction approvalを意味しない。
