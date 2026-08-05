# S07 Round 4 Review manifest — code baseline Head / Review成果物commit

> ファイル名は既存Packetからの参照互換のため維持する。内容の基準は旧`10dc316d`、`74329e9`、`92fad28`、`94e82cd`の各Packet snapshotではなく、実Gitで確認した以下の2層を分離する。
> - **code baseline Head** = `68fbbba4dff8255b3a745ce61e73e686a78bef3e`（Base→Head **23 commits / 219 paths**）。S07のproduction/code実装のReview対象であり、219 paths（#001〜#212＋`68fbbba`で追加された7 unique paths #213〜#219）はすべてcode baseline Headにcommit済みである。
> - **初回Review evidence commit** = `2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、browser evidence・runbook/test修正・Packet文書を含む。**履歴**）。
> - **現行Review evidence/result commit** = `646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**、seed修正＋browser evidence再生成）。いずれもcode baseline Headと分離して管理する。
> 直前の`6680e7d81c7842262a2fd07c57fb9942e80573ce`（および旧`1e8a224`）は履歴としてのみ扱う。
>
> このmanifestは受入PASSの宣言ではない。R1〜R5をAC→実装→assert→Demoの順で追跡可能にするためのReview証跡である。Packet文書の独立commitはなく、文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceとして記載し、文書commit hashをcurrent Headとして自己参照しない。

## 1. 対象とGit確定値

| 項目 | code baseline Head / Review成果物commitで確認した値 |
|---|---|
| 対象spec | `approval-workflow-internal-control`（S07） |
| Review Base | `5d228d211d0d752833fe3424a3b8aa4b40096733` |
| code baseline Head stats | Base→code baseline Headは**23 commits / 219 paths**（+11639/-337） |
| 初回Review evidence commit（履歴） | `2978461be1fd36334a00a97fabe37f5613e374a4`（browser evidence・runbook/test修正・Packet文書。Base→commit **24 commits / 272 paths**） |
| 現行Review evidence/result commit | `646dbdafb3c6b77ec0e3b7bb581392f50be53491`（seed修正＋browser evidence再生成。Base→commit **27 commits / 274 paths**） |
| original implementation Head | `a70cb51145a94ec3d70421bcc1de77a6b236b559` |
| Packet統合commit（過去の履歴） | `9215c5e797d063d13719b231175ab8741736a591` |
| code baseline Head（Review対象production/code） | `68fbbba4dff8255b3a745ce61e73e686a78bef3e` |
| code baseline refs | `68fbbba`時点で`HEAD = origin/main = origin/HEAD`（当時値）。初回Review evidence commit `2978461`・現行Review evidence/result commit `646dbda`はpush済み`origin/main` |
| branch / worktree | `main` / clean（最終確認時） |
| Base→code baseline Head | **23 commits / 219 files / +11639/-337**（=219 paths） |
| 直前production delta | `6680e7d..68fbbba`、12 files（うち新規7 unique paths） |
| 直前production deltaの範囲 | 4 Packet文書、V79.1 runbook、scheduler設定・H2回帰、FlywayV79_1RepairSmokeTest、OperationalBoundary実MySQL回帰、Webhook loopback回帰。これらはすべて`68fbbba`に含まれるcommit済みの履歴である |
| Packet文書commit / provenance | **独立Packet commitなし。文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceであり、code baseline Head（`68fbbba4dff8255b3a745ce61e73e686a78bef3e`）として自己参照しない** |
| Packet文書作業木 | 最終確認時はclean。今回の文書commit（`git log -1 -- <path>`で解決）はcode baseline Headの後のReview成果物として別管理する |
| diff check | `git diff --check` exit 0（最終確認時） |
| 独立Review報告のdiff hash | `63ace139532f2ccfea84f4876c6f5191db12fa4d`（旧Packetの履歴証跡） |

再現コマンド:

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
git rev-list --count 5d228d211d0d752833fe3424a3b8aa4b40096733..HEAD
git diff --stat --no-renames 5d228d211d0d752833fe3424a3b8aa4b40096733..HEAD
git diff --name-status --no-renames 5d228d211d0d752833fe3424a3b8aa4b40096733..HEAD
git diff --stat --no-renames
git diff --check 5d228d211d0d752833fe3424a3b8aa4b40096733..HEAD
git diff --check
```


### 1.1 worktree stateと今回の7 unique paths（219-path inventoryへの帰属）

最終確認時（`68fbbba4dff8255b3a745ce61e73e686a78bef3e`）のworktreeは**clean**である。旧Packetにあった「12ファイル未commit dirty」の記述は、`68fbbba`（回帰test commit）で12ファイルが全てcommit済みとなったため削除する。旧記述の12ファイルのうち、4 Packet文書は既に`6680e7d`時点の212 pathsに含まれており、残る7ファイルは今回の219-path inventoryへ新規unique paths（#213〜#219）として帰属する（§2.10）。

新規7 unique pathsの帰属:

| # | status | path | primary task / scope |
|---:|---|---|---|
| 213 | A | `sql/runbook/v79_1-fk-actions-forward-repair.sql` | `R4-P1-01`/`T047/M` V79.1 forward repair runbook（information_schemaで再開可能） |
| 214 | M | `src/main/java/com/ses/config/SchedulerLockConfig.java` | `T046/B1` scheduler DB時刻ロック設定（test profile切替） |
| 215 | M | `src/main/resources/application.yml` | `T046/B1` `app.scheduler.lock.use-db-time`設定 |
| 216 | A | `src/test/java/com/ses/config/SchedulerLockH2IntegrationTest.java` | `T046/B1` H2 lock warning回帰 |
| 217 | A | `src/test/java/com/ses/migration/FlywayV79_1RepairSmokeTest.java` | `R4-P1-01` V79.1実MySQL partial/repair/rollback回帰 |
| 218 | A | `src/test/java/com/ses/operational/OperationalBoundaryMySqlIntegrationTest.java` | `T046/B1`/`T047/M` 複数JVM ShedLock/claim・commit前rollback実MySQL回帰 |
| 219 | A | `src/test/java/com/ses/service/notification/WebhookNotifierLoopbackIntegrationTest.java` | `T046/B1` loopback実HTTP Webhook回帰 |

### 帰属コード

| コード | task / scope | Review上の扱い |
|---|---|---|
| `T041` | G7・9操作inventory | 変更は調査/spec文書のみ |
| `T042/F1` | route/request/action/delegation engine・DDL | S07実装 |
| `T043/F2` | 5 target adapter・対象API委譲 | S07実装 |
| `T044/A1` | inbox/request/diff/history UI | S07実装 |
| `T045/A2` | route version・代理管理・R4-P1-01 route source管理 | S07実装（R4-P1-01含む）として帰属 |
| `T046/B1` | 通知/SLA/outbox/escalation | 実装・回帰・実MySQL/loopback/複数JVM証拠を確認。`[x]` |
| `T047/M` | 5業務画面統合・回帰 | 実装・回帰・CI相当L4・5業務desktop/390px browser Demo（10経路）を確認。`[x]` |
| `R3-FIX` | Round 3修正・共有fixture/回帰 | S07由来またはshared consumerとして記録 |
| `MIGRATION-CONTRACT` | S07 V75〜V79/V79.1、S09〜S17 V80〜V88の予約consumer | 後続specの予約文書。S07 production実装とは分離 |
| `R4-DOC` | Round 4 Packet/manifest/ledger correction | current HeadとworktreeのReview process record |
| `SHARED` | S07以外の既存shared consumer・範囲外spec文書 | S07の受入PASSへ加算しない |

## 2. Base→Review対象production Headの全path manifest

以下はcode baseline Head `68fbbba4dff8255b3a745ce61e73e686a78bef3e`に対する`git diff --name-status --no-renames 5d228d211d0d752833fe3424a3b8aa4b40096733..68fbbba4dff8255b3a745ce61e73e686a78bef3e`の全219 pathである（初回Review evidence commit `2978461`（履歴）・現行Review evidence/result commit `646dbda`は§1/§2.10で分離管理）。#001〜#212は`6680e7d`時点の212 path、#213〜#219は`68fbbba`で追加された7 unique paths（§2.10）。各行にstatus、primary task/scope、変更を含む代表commitを記録する。同一pathが複数commitで変更された場合、commit欄は最終的な実装・証跡上の代表commitであり、§3のcommit履歴と併読する。

### 2.1 S07 spec packet（5 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 001 | M | `.kiro/specs/approval-workflow-internal-control/design.md` | `T042/F1`〜`T047/M`の設計正本、migration/AC境界 | `9215c5e` |
| 002 | A | `.kiro/specs/approval-workflow-internal-control/operation-inventory.md` | `T041` 9操作inventory | `9565513` |
| 003 | M | `.kiro/specs/approval-workflow-internal-control/review-ledger.md` | `R4-DOC` Packet/Issue Register | `76ffcbb` |
| 004 | A | `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md` | `R4-DOC` current Head全path/trace manifest | `76ffcbb` |
| 005 | M | `.kiro/specs/approval-workflow-internal-control/tasks.md` | `T041`〜`T047` task/Demo/release gate記録 | `76ffcbb` |

### 2.2 roadmap dispatch / reservation docs（25 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 006 | M | `.kiro/specs/customer-product-expansion-2026/README.md` | `MIGRATION-CONTRACT` 実在/予約migration表 | `9215c5e` |
| 007 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R07__approval-workflow-internal-control__review.txt` | `R4-DOC` S07 Review Packet copy | `10dc316` |
| 008 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R09__order-acceptance-workflow__review.txt` | `MIGRATION-CONTRACT` S09 review入口 | `1e204df` |
| 009 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R10__dispatch-outsourcing-compliance-ledger__review.txt` | `MIGRATION-CONTRACT` S10 review入口 | `1e204df` |
| 010 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R11__attendance-leave-overtime-compliance__review.txt` | `MIGRATION-CONTRACT` S11 review入口 | `1e204df` |
| 011 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R12__staffing-capacity-planning__review.txt` | `MIGRATION-CONTRACT` S12 review入口 | `1e204df` |
| 012 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R13__external-customer-bp-portal__review.txt` | `MIGRATION-CONTRACT` S13 review入口 | `1e204df` |
| 013 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R14__engineer-self-service-portal-v2__review.txt` | `MIGRATION-CONTRACT` S14 review入口 | `1e204df` |
| 014 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R15__accounting-payment-integration__review.txt` | `MIGRATION-CONTRACT` S15 review入口 | `1e204df` |
| 015 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R16__jp-pint-digital-invoice__review.txt` | `MIGRATION-CONTRACT` S16 review入口 | `1e204df` |
| 016 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R17__ai-feedback-learning__review.txt` | `MIGRATION-CONTRACT` S17 review入口 | `1e204df` |
| 017 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S07__approval-workflow-internal-control__start.txt` | `R4-DOC` S07 start Packet copy | `10dc316` |
| 018 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S09__order-acceptance-workflow__start.txt` | `MIGRATION-CONTRACT` S09 reservation | `7b6bb0b` |
| 019 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S10__dispatch-outsourcing-compliance-ledger__start.txt` | `MIGRATION-CONTRACT` S10 reservation | `7b6bb0b` |
| 020 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S11__attendance-leave-overtime-compliance__start.txt` | `MIGRATION-CONTRACT` S11 reservation | `7b6bb0b` |
| 021 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S12__staffing-capacity-planning__start.txt` | `MIGRATION-CONTRACT` S12 reservation | `7b6bb0b` |
| 022 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S13__external-customer-bp-portal__start.txt` | `MIGRATION-CONTRACT` S13 reservation | `7b6bb0b` |
| 023 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S14__engineer-self-service-portal-v2__start.txt` | `MIGRATION-CONTRACT` S14 reservation | `7b6bb0b` |
| 024 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S15__accounting-payment-integration__start.txt` | `MIGRATION-CONTRACT` S15 reservation | `7b6bb0b` |
| 025 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S16__jp-pint-digital-invoice__start.txt` | `MIGRATION-CONTRACT` S16 reservation | `7b6bb0b` |
| 026 | M | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S17__ai-feedback-learning__start.txt` | `MIGRATION-CONTRACT` S17 reservation | `7b6bb0b` |
| 027 | M | `.kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md` | `MIGRATION-CONTRACT` Wave/採番consumer、S07 Packet | `10dc316` |
| 028 | M | `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md` | `R4-DOC` 中央台帳S07 row7 | `76ffcbb` |
| 029 | M | `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md` | `R4-DOC` Review入口 | `10dc316` |
| 030 | M | `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md` | `R4-DOC` start入口 | `10dc316` |

### 2.3 S09〜S17 other spec docs（18 paths、S07 production範囲外）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 031 | M | `.kiro/specs/accounting-payment-integration/design.md` | `MIGRATION-CONTRACT` S15予約consumer | `7b6bb0b` |
| 032 | M | `.kiro/specs/accounting-payment-integration/tasks.md` | `MIGRATION-CONTRACT` S15予約consumer | `7b6bb0b` |
| 033 | M | `.kiro/specs/ai-feedback-learning/design.md` | `MIGRATION-CONTRACT` S17予約consumer | `7b6bb0b` |
| 034 | M | `.kiro/specs/ai-feedback-learning/tasks.md` | `MIGRATION-CONTRACT` S17予約consumer | `7b6bb0b` |
| 035 | M | `.kiro/specs/attendance-leave-overtime-compliance/design.md` | `MIGRATION-CONTRACT` S11予約consumer | `7b6bb0b` |
| 036 | M | `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` | `MIGRATION-CONTRACT` S11予約consumer | `7b6bb0b` |
| 037 | M | `.kiro/specs/dispatch-outsourcing-compliance-ledger/design.md` | `MIGRATION-CONTRACT` S10予約consumer | `7b6bb0b` |
| 038 | M | `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` | `MIGRATION-CONTRACT` S10予約consumer | `7b6bb0b` |
| 039 | M | `.kiro/specs/engineer-self-service-portal-v2/design.md` | `MIGRATION-CONTRACT` S14予約consumer | `7b6bb0b` |
| 040 | M | `.kiro/specs/engineer-self-service-portal-v2/tasks.md` | `MIGRATION-CONTRACT` S14予約consumer | `7b6bb0b` |
| 041 | M | `.kiro/specs/external-customer-bp-portal/design.md` | `MIGRATION-CONTRACT` S13予約consumer | `7b6bb0b` |
| 042 | M | `.kiro/specs/external-customer-bp-portal/tasks.md` | `MIGRATION-CONTRACT` S13予約consumer | `7b6bb0b` |
| 043 | M | `.kiro/specs/jp-pint-digital-invoice/design.md` | `MIGRATION-CONTRACT` S16予約consumer | `7b6bb0b` |
| 044 | M | `.kiro/specs/jp-pint-digital-invoice/tasks.md` | `MIGRATION-CONTRACT` S16予約consumer | `7b6bb0b` |
| 045 | M | `.kiro/specs/order-acceptance-workflow/design.md` | `MIGRATION-CONTRACT` S09予約consumer | `7b6bb0b` |
| 046 | M | `.kiro/specs/order-acceptance-workflow/tasks.md` | `MIGRATION-CONTRACT` S09予約consumer | `7b6bb0b` |
| 047 | M | `.kiro/specs/staffing-capacity-planning/design.md` | `MIGRATION-CONTRACT` S12予約consumer | `7b6bb0b` |
| 048 | M | `.kiro/specs/staffing-capacity-planning/tasks.md` | `MIGRATION-CONTRACT` S12予約consumer | `7b6bb0b` |

### 2.4 production Java（88 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 049 | M | `src/main/java/com/ses/common/constant/NotificationLinks.java` | `T043/F2` notification link consumer | `d440eb5` |
| 050 | M | `src/main/java/com/ses/common/util/PageUtils.java` | `R3-FIX` SQL/page境界共通修正 | `9a57eeb` |
| 051 | A | `src/main/java/com/ses/controller/api/ApprovalAdministrationApiController.java` | `T045/A2` route/代理管理API | `d440eb5` |
| 052 | A | `src/main/java/com/ses/controller/api/ApprovalApiController.java` | `T042/F1`〜`T043/F2` engine API | `d440eb5` |
| 053 | M | `src/main/java/com/ses/controller/api/ContractApiController.java` | `T047/M` contract申請経路 | `d440eb5` |
| 054 | M | `src/main/java/com/ses/controller/api/InvoiceApiController.java` | `T047/M` invoice/BP申請経路 | `d440eb5` |
| 055 | M | `src/main/java/com/ses/controller/api/MonthlyClosingApiController.java` | `T047/M` closing申請経路 | `d440eb5` |
| 056 | M | `src/main/java/com/ses/controller/api/QuotationApiController.java` | `T047/M` quotation申請経路 | `d440eb5` |
| 057 | A | `src/main/java/com/ses/controller/page/ApprovalPageController.java` | `T044/A1`〜`T045/A2` approval pages | `d440eb5` |
| 058 | M | `src/main/java/com/ses/controller/page/ProjectIngestionPageController.java` | `SHARED`/`R3-FIX` page共通修正 | `9a57eeb` |
| 059 | M | `src/main/java/com/ses/controller/page/ResumeIngestionPageController.java` | `SHARED`/`R3-FIX` page共通修正 | `9a57eeb` |
| 060 | A | `src/main/java/com/ses/dto/approval/ApprovalActionRequest.java` | `T042/F1` action command | `9565513` |
| 061 | A | `src/main/java/com/ses/dto/approval/ApprovalActionView.java` | `T044/A1` action history view | `d440eb5` |
| 062 | A | `src/main/java/com/ses/dto/approval/ApprovalDelegationRequest.java` | `T045/A2` delegation command | `d440eb5` |
| 063 | A | `src/main/java/com/ses/dto/approval/ApprovalDelegationView.java` | `T045/A2` delegation audit view | `d440eb5` |
| 064 | A | `src/main/java/com/ses/dto/approval/ApprovalDiffItem.java` | `T044/A1` diff/mask view | `d440eb5` |
| 065 | A | `src/main/java/com/ses/dto/approval/ApprovalRequestCreateRequest.java` | `T042/F1` request command | `9565513` |
| 066 | A | `src/main/java/com/ses/dto/approval/ApprovalRequestListItem.java` | `T044/A1` request list | `d440eb5` |
| 067 | A | `src/main/java/com/ses/dto/approval/ApprovalRequestListResponse.java` | `T044/A1` request page response | `d440eb5` |
| 068 | A | `src/main/java/com/ses/dto/approval/ApprovalRequestView.java` | `T044/A1` detail/history | `d440eb5` |
| 069 | A | `src/main/java/com/ses/dto/approval/ApprovalResubmitRequest.java` | `T042/F1` resubmit command | `9565513` |
| 070 | A | `src/main/java/com/ses/dto/approval/ApprovalRoutePreviewRequest.java` | `T045/A2` route preview | `d440eb5` |
| 071 | A | `src/main/java/com/ses/dto/approval/ApprovalRoutePreviewView.java` | `T045/A2` route preview result | `d440eb5` |
| 072 | A | `src/main/java/com/ses/dto/approval/ApprovalRouteSaveRequest.java` | `T045/A2` route save | `d440eb5` |
| 073 | A | `src/main/java/com/ses/dto/approval/ApprovalRouteStepRequest.java` | `T045/A2` route step save | `d440eb5` |
| 074 | A | `src/main/java/com/ses/dto/approval/ApprovalRouteStepView.java` | `T045/A2` route step view | `d440eb5` |
| 075 | A | `src/main/java/com/ses/dto/approval/ApprovalRouteView.java` | `T045/A2` route view | `d440eb5` |
| 076 | A | `src/main/java/com/ses/entity/ApprovalAction.java` | `T042/F1`/`R3-FIX` action round/slot | `5110f12` |
| 077 | A | `src/main/java/com/ses/entity/ApprovalDelegation.java` | `T042/F1` delegation entity | `9565513` |
| 078 | A | `src/main/java/com/ses/entity/ApprovalDelegationType.java` | `R3-FIX` normalized delegation type | `5110f12` |
| 079 | A | `src/main/java/com/ses/entity/ApprovalParticipant.java` | `R3-FIX` participant visibility | `5110f12` |
| 080 | A | `src/main/java/com/ses/entity/ApprovalRequest.java` | `T042/F1`/`R3-FIX` request version/round | `5110f12` |
| 081 | A | `src/main/java/com/ses/entity/ApprovalRoute.java` | `T042/F1` route entity | `9565513` |
| 082 | A | `src/main/java/com/ses/entity/ApprovalRouteStep.java` | `T042/F1` route step entity | `9565513` |
| 083 | M | `src/main/java/com/ses/entity/BpPayment.java` | `T043/F2`/`R3-FIX` target version | `5110f12` |
| 084 | M | `src/main/java/com/ses/entity/Contract.java` | `T043/F2`/`R3-FIX` target version | `5110f12` |
| 085 | M | `src/main/java/com/ses/entity/Invoice.java` | `T043/F2`/`R3-FIX` target version | `5110f12` |
| 086 | A | `src/main/java/com/ses/entity/NotificationOutbox.java` | `T046/B1` outbox entity | `b380a5a` |
| 087 | M | `src/main/java/com/ses/entity/Quotation.java` | `T043/F2`/`R3-FIX` target version | `5110f12` |
| 088 | A | `src/main/java/com/ses/mapper/ApprovalActionMapper.java` | `T042/F1` action mapper | `9565513` |
| 089 | A | `src/main/java/com/ses/mapper/ApprovalDelegationMapper.java` | `T042/F1` delegation mapper | `9565513` |
| 090 | A | `src/main/java/com/ses/mapper/ApprovalDelegationTypeMapper.java` | `R3-FIX` delegation type mapper | `5110f12` |
| 091 | A | `src/main/java/com/ses/mapper/ApprovalParticipantMapper.java` | `R3-FIX` participant mapper | `5110f12` |
| 092 | A | `src/main/java/com/ses/mapper/ApprovalRequestMapper.java` | `T042/F1`/`R3-FIX` request SQL boundary | `5110f12` |
| 093 | A | `src/main/java/com/ses/mapper/ApprovalRouteMapper.java` | `T042/F1` route mapper | `9565513` |
| 094 | A | `src/main/java/com/ses/mapper/ApprovalRouteStepMapper.java` | `T042/F1` route step mapper | `9565513` |
| 095 | M | `src/main/java/com/ses/mapper/BpPaymentMapper.java` | `R3-FIX` target lock/version SQL | `a33a6e9` |
| 096 | M | `src/main/java/com/ses/mapper/ContractMapper.java` | `T043/F2`/`R3-FIX` contract version SQL | `5110f12` |
| 097 | M | `src/main/java/com/ses/mapper/InvoiceMapper.java` | `R3-FIX` target lock/version SQL | `a33a6e9` |
| 098 | A | `src/main/java/com/ses/mapper/NotificationOutboxMapper.java` | `T046/B1` outbox claim/update | `b380a5a` |
| 099 | A | `src/main/java/com/ses/service/approval/ApprovalAdministrationService.java` | `T045/A2` administration contract | `d440eb5` |
| 100 | A | `src/main/java/com/ses/service/approval/ApprovalEngineService.java` | `T042/F1` engine contract | `9565513` |
| 101 | A | `src/main/java/com/ses/service/approval/ApprovalNotificationKeys.java` | `T046/B1` round/step/slot dedupe | `b380a5a` |
| 102 | A | `src/main/java/com/ses/service/approval/ApprovalNotificationService.java` | `T046/B1` notification contract | `d440eb5` |
| 103 | A | `src/main/java/com/ses/service/approval/ApprovalPayloads.java` | `T043/F2` target payload | `d440eb5` |
| 104 | A | `src/main/java/com/ses/service/approval/ApprovalRequestCommand.java` | `T042/F1` request command | `9565513` |
| 105 | A | `src/main/java/com/ses/service/approval/ApprovalSlaService.java` | `T046/B1` SLA calculation | `b380a5a` |
| 106 | A | `src/main/java/com/ses/service/approval/ApprovalSnapshot.java` | `T042/F1`/`T043/F2` snapshot | `9565513` |
| 107 | A | `src/main/java/com/ses/service/approval/ApprovalTargetAdapter.java` | `T043/F2` adapter contract | `5110f12` |
| 108 | A | `src/main/java/com/ses/service/approval/ApprovalTargetAdapterRegistry.java` | `T043/F2` adapter registry | `d440eb5` |
| 109 | A | `src/main/java/com/ses/service/approval/ApprovalViewService.java` | `T044/A1` view contract | `d440eb5` |
| 110 | A | `src/main/java/com/ses/service/approval/ResolvedRoute.java` | `T042/F1` resolved route | `9565513` |
| 111 | A | `src/main/java/com/ses/service/approval/RouteResolverService.java` | `T042/F1` route contract | `9565513` |
| 112 | A | `src/main/java/com/ses/service/approval/RouteSlot.java` | `R3-FIX` slot/quorum model | `5110f12` |
| 113 | A | `src/main/java/com/ses/service/approval/RouteSnapshot.java` | `T042/F1` route snapshot | `9565513` |
| 114 | A | `src/main/java/com/ses/service/approval/RouteStepGroup.java` | `R3-FIX` slot group | `5110f12` |
| 115 | A | `src/main/java/com/ses/service/impl/ApprovalAdministrationServiceImpl.java` | `T045/A2` route/代理 implementation | `d440eb5` |
| 116 | A | `src/main/java/com/ses/service/impl/ApprovalEngineServiceImpl.java` | `T042/F1`/`R3-FIX` state/CAS/lock order | `5110f12` |
| 117 | A | `src/main/java/com/ses/service/impl/ApprovalViewServiceImpl.java` | `T044/A1`/`R3-FIX` SQL visibility/mask | `a33a6e9` |
| 118 | M | `src/main/java/com/ses/service/impl/BpCompanyServiceImpl.java` | `SHARED`/`R3-FIX` permission consumer | `a33a6e9` |
| 119 | A | `src/main/java/com/ses/service/impl/BpPaymentApprovalAdapter.java` | `T043/F2` BP adapter | `5110f12` |
| 120 | M | `src/main/java/com/ses/service/impl/BpPaymentServiceImpl.java` | `T043/F2`/`R3-FIX` target update | `a33a6e9` |
| 121 | A | `src/main/java/com/ses/service/impl/ContractApprovalAdapter.java` | `T043/F2` contract adapter | `d440eb5` |
| 122 | M | `src/main/java/com/ses/service/impl/ContractServiceImpl.java` | `T043/F2`/`R3-FIX` target update | `5110f12` |
| 123 | A | `src/main/java/com/ses/service/impl/InvoiceApprovalAdapter.java` | `T043/F2` invoice adapter | `d440eb5` |
| 124 | M | `src/main/java/com/ses/service/impl/InvoiceServiceImpl.java` | `T043/F2`/`R3-FIX` target update | `a33a6e9` |
| 125 | M | `src/main/java/com/ses/service/impl/LeadServiceImpl.java` | `SHARED`/`R3-FIX` existing consumer | `9a57eeb` |
| 126 | A | `src/main/java/com/ses/service/impl/MonthlyClosingApprovalAdapter.java` | `T043/F2` closing adapter | `d440eb5` |
| 127 | M | `src/main/java/com/ses/service/impl/NotificationServiceImpl.java` | `T046/B1` transaction/outbox integration | `b380a5a` |
| 128 | A | `src/main/java/com/ses/service/impl/QuotationApprovalAdapter.java` | `T043/F2` quotation adapter | `d440eb5` |
| 129 | A | `src/main/java/com/ses/service/impl/RouteResolverServiceImpl.java` | `T042/F1`/`R3-FIX` route/approver resolution | `5110f12` |
| 130 | M | `src/main/java/com/ses/service/impl/WorkRecordServiceImpl.java` | `SHARED`/`R3-FIX` target update consumer | `9a57eeb` |
| 131 | A | `src/main/java/com/ses/service/notification/NotificationOutboxDispatcher.java` | `T046/B1` claim/dispatch | `b380a5a` |
| 132 | A | `src/main/java/com/ses/service/notification/NotificationOutboxService.java` | `T046/B1` outbox persistence | `b380a5a` |
| 133 | M | `src/main/java/com/ses/service/notification/WebhookNotifier.java` | `T046/B1` webhook boundary | `b380a5a` |
| 134 | A | `src/main/java/com/ses/service/scheduler/ApprovalSlaScheduler.java` | `T046/B1` SLA scheduler | `b380a5a` |
| 135 | A | `src/main/java/com/ses/service/scheduler/NotificationOutboxScheduler.java` | `T046/B1` outbox scheduler | `b380a5a` |
| 136 | M | `src/main/java/com/ses/service/security/ActionPermissionResolver.java` | `R3-FIX` approval resource/action seed | `a33a6e9` |

### 2.5 migration SQL（5 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 137 | A | `src/main/resources/db/migration/V75__approval_workflow.sql` | `T042/F1` approval DDL | `4edb399` |
| 138 | A | `src/main/resources/db/migration/V76__approval_menu.sql` | `T044/A1` approval menu/permission | `d440eb5` |
| 139 | A | `src/main/resources/db/migration/V77__approval_sla_step_start.sql` | `T046/B1` SLA step start | `a33a6e9` |
| 140 | A | `src/main/resources/db/migration/V78__approval_workflow_round_participant_version.sql` | `R3-FIX` round/participant/version | `a33a6e9` |
| 141 | A | `src/main/resources/db/migration/V79__notification_webhook_outbox.sql` | `T046/B1` notification outbox | `b380a5a` |

### 2.6 frontend / resources（30 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 142 | M | `src/main/resources/messages.properties` | `T044/A1`/`T046/B1` approval messages | `d440eb5` |
| 143 | M | `src/main/resources/messages_en.properties` | `T044/A1`/`T046/B1` approval messages | `d440eb5` |
| 144 | M | `src/main/resources/messages_ko.properties` | `T044/A1`/`T046/B1` approval messages | `d440eb5` |
| 145 | M | `src/main/resources/messages_zh_CN.properties` | `T044/A1`/`T046/B1` approval messages | `d440eb5` |
| 146 | M | `src/main/resources/static/css/common.css` | `T044/A1` approval UI layout | `d440eb5` |
| 147 | M | `src/main/resources/static/js/common.js` | `T044/A1` shared AJAX/CSRF/UI consumer | `d440eb5` |
| 148 | A | `src/main/resources/static/js/modules/approval-routes.js` | `T045/A2` route admin UI | `d440eb5` |
| 149 | A | `src/main/resources/static/js/modules/approval.js` | `T044/A1` inbox/request UI | `d440eb5` |
| 150 | M | `src/main/resources/static/js/modules/bp-company.js` | `T047/M` BP approval action consumer | `d440eb5` |
| 151 | M | `src/main/resources/static/js/modules/candidate.js` | `SHARED` existing page consumer | `d440eb5` |
| 152 | M | `src/main/resources/static/js/modules/contract-price-revision.js` | `T047/M` contract price approval | `d440eb5` |
| 153 | M | `src/main/resources/static/js/modules/contract.js` | `T047/M` contract approval | `d440eb5` |
| 154 | M | `src/main/resources/static/js/modules/crm-leads.js` | `SHARED` existing page consumer | `d440eb5` |
| 155 | M | `src/main/resources/static/js/modules/invoice.js` | `T047/M` invoice approval | `d440eb5` |
| 156 | M | `src/main/resources/static/js/modules/monthly-closing.js` | `T047/M` closing approval | `d440eb5` |
| 157 | M | `src/main/resources/static/js/modules/my-timesheet.js` | `SHARED` existing page consumer | `d440eb5` |
| 158 | M | `src/main/resources/static/js/modules/proposal-kanban.js` | `T047/M` quotation approval | `d440eb5` |
| 159 | M | `src/main/resources/static/js/modules/quotation.js` | `T047/M` quotation approval | `d440eb5` |
| 160 | M | `src/main/resources/static/js/modules/todo.js` | `SHARED` existing page consumer | `d440eb5` |
| 161 | M | `src/main/resources/static/js/modules/work-record.js` | `SHARED` existing page consumer | `d440eb5` |
| 162 | A | `src/main/resources/templates/approval/detail.html` | `T044/A1` detail/diff/history | `d440eb5` |
| 163 | A | `src/main/resources/templates/approval/inbox.html` | `T044/A1` inbox | `d440eb5` |
| 164 | A | `src/main/resources/templates/approval/requests.html` | `T044/A1` request list | `d440eb5` |
| 165 | A | `src/main/resources/templates/approval/routes.html` | `T045/A2` route admin | `d440eb5` |
| 166 | M | `src/main/resources/templates/layout/sidebar.html` | `T044/A1`/`T045/A2` menu | `d440eb5` |
| 167 | M | `src/main/resources/templates/monthly-closing/list.html` | `T047/M` closing action | `d440eb5` |
| 168 | M | `src/main/resources/templates/payroll/index.html` | `SHARED` existing page consumer | `d440eb5` |
| 169 | M | `src/main/resources/templates/project-ingestion/review.html` | `SHARED`/`R3-FIX` page consumer | `9a57eeb` |
| 170 | M | `src/main/resources/templates/resume-ingestion/review.html` | `SHARED`/`R3-FIX` page consumer | `9a57eeb` |
| 171 | M | `src/main/resources/templates/todo/list.html` | `SHARED` existing page consumer | `d440eb5` |

### 2.7 test Java（29 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 172 | A | `src/test/java/com/ses/controller/api/ApprovalApiControllerTest.java` | `T042/F1`/`T043/F2` API contract | `d440eb5` |
| 173 | M | `src/test/java/com/ses/controller/api/ContractApiControllerTest.java` | `T047/M` contract regression | `d440eb5` |
| 174 | M | `src/test/java/com/ses/controller/api/ContractPaginationTest.java` | `T047/M` contract pagination regression | `d440eb5` |
| 175 | M | `src/test/java/com/ses/controller/api/InvoiceApiControllerTest.java` | `T047/M` invoice/BP regression | `d440eb5` |
| 176 | M | `src/test/java/com/ses/controller/api/QuotationApiControllerTest.java` | `T047/M` quotation regression | `d440eb5` |
| 177 | A | `src/test/java/com/ses/controller/page/ProjectIngestionPageControllerTest.java` | `SHARED`/`R3-FIX` page regression | `9a57eeb` |
| 178 | A | `src/test/java/com/ses/controller/page/ResumeIngestionPageControllerTest.java` | `SHARED`/`R3-FIX` page regression | `9a57eeb` |
| 179 | M | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | `R3-FIX` MySQL migration fixture/oracle | `a70cb51` |
| 180 | M | `src/test/java/com/ses/migration/MigrationScriptIntegrityTest.java` | `T042/F1`/`MIGRATION-CONTRACT` SQL integrity | `5110f12` |
| 181 | M | `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java` | `MIGRATION-CONTRACT` S07/S09〜S17 reservation | `10dc316` |
| 182 | A | `src/test/java/com/ses/service/impl/ApprovalAdministrationServiceTest.java` | `T045/A2` route/代理 admin | `d440eb5` |
| 183 | A | `src/test/java/com/ses/service/impl/ApprovalEngineConflictTest.java` | `T042/F1`/`R3-FIX` target conflict | `5110f12` |
| 184 | A | `src/test/java/com/ses/service/impl/ApprovalEngineServiceTest.java` | `T042/F1` state/quorum/CAS | `5110f12` |
| 185 | A | `src/test/java/com/ses/service/impl/ApprovalNotificationSlaTest.java` | `T046/B1` SLA/dedupe/recipient | `b380a5a` |
| 186 | A | `src/test/java/com/ses/service/impl/ApprovalTargetAdapterTest.java` | `T043/F2`/`T047/M` adapter/idempotency | `d440eb5` |
| 187 | A | `src/test/java/com/ses/service/impl/ApprovalViewPageBoundaryTest.java` | `T044/A1` view boundary | `5110f12` |
| 188 | A | `src/test/java/com/ses/service/impl/ApprovalViewServiceImplTest.java` | `T044/A1`/`R3-FIX` visibility/mask | `a33a6e9` |
| 189 | M | `src/test/java/com/ses/service/impl/BpCompanyServiceImplTest.java` | `SHARED`/`R3-FIX` permission regression | `9a57eeb` |
| 190 | M | `src/test/java/com/ses/service/impl/InvoiceServiceImplTest.java` | `T047/M`/`R3-FIX` invoice/BP regression | `a33a6e9` |
| 191 | M | `src/test/java/com/ses/service/impl/NotificationServiceImplTest.java` | `T046/B1` transaction/outbox | `b380a5a` |
| 192 | A | `src/test/java/com/ses/service/impl/RouteResolverServiceTest.java` | `T042/F1`/`T045/A2` route boundary | `5110f12` |
| 193 | A | `src/test/java/com/ses/service/notification/NotificationOutboxDispatcherTest.java` | `T046/B1` claim/dispatch | `b380a5a` |
| 194 | A | `src/test/java/com/ses/service/notification/NotificationOutboxSchedulerIntegrationTest.java` | `T046/B1` scheduler二重起動Demo相当 | `df674db` |
| 195 | A | `src/test/java/com/ses/service/notification/NotificationOutboxServiceTest.java` | `T046/B1` outbox state/retry | `b380a5a` |
| 196 | M | `src/test/java/com/ses/service/security/ActionPermissionMatrixTest.java` | `R3-FIX` action deny/mask | `a33a6e9` |
| 197 | M | `src/test/java/com/ses/service/security/ConcurrentLoginSessionSmokeTest.java` | `SHARED` concurrency smoke | `9a57eeb` |
| 198 | A | `src/test/java/com/ses/web/ApprovalPageRenderTest.java` | `T044/A1` Thymeleaf render | `d440eb5` |
| 199 | A | `src/test/java/com/ses/web/ApprovalUiContractTest.java` | `T044/A1` responsive/UI contract | `d440eb5` |
| 200 | M | `src/test/java/com/ses/web/PayrollLandmarkA11yTest.java` | `SHARED`/`R3-FIX` UI regression | `9a57eeb` |

### 2.8 test resources（5 paths）

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 201 | M | `src/test/resources/application-test.yml` | `T042/F1` H2 schema registration | `9565513` |
| 202 | M | `src/test/resources/sql/engineer-schema-h2.sql` | `R3-FIX` shared H2 menu fixture | `df674db` |
| 203 | M | `src/test/resources/sql/permission-group-seed-h2.sql` | `R3-FIX` approval/bank permission seed | `a33a6e9` |
| 204 | A | `src/test/resources/sql/schema-approval-h2.sql` | `T042/F1`/`T046/B1` H2 approval schema | `b380a5a` |
| 205 | M | `src/test/resources/sql/schema-quotation-h2.sql` | `T043/F2` target version schema sync | `5110f12` |

### 2.9 Base→Review対象production Headで追加されたpathと履歴delta

`0a724356..74329e9`の差分に含まれるBase→Headの新規pathは次の6件である。いずれも`74329e9`に含まれる。

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 206 | A | `src/main/java/com/ses/dto/approval/ApprovalResponsibilitySaveRequest.java` | `T045/A2`/`R4-P1-01` responsibility assignment command | `74329e9` |
| 207 | A | `src/main/java/com/ses/dto/approval/ApprovalResponsibilityView.java` | `T045/A2`/`R4-P1-01` responsibility assignment view | `74329e9` |
| 208 | A | `src/main/java/com/ses/entity/ApprovalResponsibility.java` | `T045/A2`/`R4-P1-01` organization responsibility entity | `74329e9` |
| 209 | A | `src/main/java/com/ses/mapper/ApprovalResponsibilityMapper.java` | `T045/A2`/`R4-P1-01` responsibility mapper | `74329e9` |
| 210 | M | `src/main/java/com/ses/mapper/UserPermissionGroupMapper.java` | `R4-P1-01` permission-group active membership query | `74329e9` |
| 211 | A | `src/main/resources/db/migration/V79_1__approval_route_decision_sources.sql` | `R4-P1-01` route role condition/responsibility patch migration | `74329e9` |

`92fad28`では新規pathとして`ApprovalAdministrationApiControllerTest`が追加された。一方、`ApprovalAdministrationServiceTest`と`RouteResolverServiceTest`は既存manifestの#182/#192を更新したものであり、別行へ重複計上しない。#212はBase→Review対象production Headで一度だけ列挙する。

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 212 | A | `src/test/java/com/ses/controller/api/ApprovalAdministrationApiControllerTest.java` | `R4-P1-01` responsibility逆期間HTTP 400/`ApiResult.code=400`、APPLICANT_MANAGER HTTP契約 | `92fad28` |

`94e82cd..1e8a224`は既存manifest pathである4文書のPacket統計を更新した履歴deltaであり、新規pathはない。該当pathは#003/#004/#005/#028に一度ずつ列挙済みである。

**path集計:** S07 packet 5 + roadmap dispatch 25 + other spec 18 + production Java 88 + migration SQL 5 + frontend/resources 30 + test Java 29 + test resources 5 + R4-P1-01 source additions 6 + R4-P1-01 test addition 1 = **212 unique paths**。`git diff --name-only 5d228d2..HEAD | Sort-Object -Unique`相当で一意212件を確認し、manifestの#001〜#212は各pathを一度だけ列挙する。重複0、未分類0。`ApprovalAdministrationServiceTest`と`RouteResolverServiceTest`の追加回帰はそれぞれ既存#182/#192の更新として帰属する。

### 2.10 今回追加された7 unique paths（68fbbba、#213〜#219）

`6680e7d..68fbbba`で追加された7 unique pathsは次のとおり。いずれも`68fbbba`にcommit済みであり、§1.1の表と一致する。

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 213 | A | `sql/runbook/v79_1-fk-actions-forward-repair.sql` | `R4-P1-01`/`T047/M` V79.1 forward repair runbook（information_schema再開可能） | `68fbbba` |
| 214 | M | `src/main/java/com/ses/config/SchedulerLockConfig.java` | `T046/B1` scheduler DB時刻ロック設定 | `68fbbba` |
| 215 | M | `src/main/resources/application.yml` | `T046/B1` `app.scheduler.lock.use-db-time`設定 | `68fbbba` |
| 216 | A | `src/test/java/com/ses/config/SchedulerLockH2IntegrationTest.java` | `T046/B1` H2 lock warning回帰 | `68fbbba` |
| 217 | A | `src/test/java/com/ses/migration/FlywayV79_1RepairSmokeTest.java` | `R4-P1-01` V79.1実MySQL partial/repair/rollback回帰 | `68fbbba` |
| 218 | A | `src/test/java/com/ses/operational/OperationalBoundaryMySqlIntegrationTest.java` | `T046/B1`/`T047/M` 複数JVM ShedLock/claim・commit前rollback実MySQL回帰 | `68fbbba` |
| 219 | A | `src/test/java/com/ses/service/notification/WebhookNotifierLoopbackIntegrationTest.java` | `T046/B1` loopback実HTTP Webhook回帰 | `68fbbba` |

## 3. R1〜R5 AC trace (AC→実装→assert→Demo)

以下の`assert`は定向testまたは静的契約の入口、`Demo`は自動Demo相当を含む。`未達`は受入不成立を意味し、定向assertをPASSへ拡張しない。

| AC | 受入条件 | 実装 / consumer | assert / evidence | Demo evidence | 状態 |
|---|---|---|---|---|---|
| R1.1 | 対象操作を直接確定せず、申請draftと差分snapshotを作る | `ApprovalEngineServiceImpl.request`、`ApprovalSnapshot`、`ApprovalApiController`、5 adapter、`operation-inventory.md` §2 | `ApprovalEngineServiceTest`、`ApprovalApiControllerTest`、`ApprovalTargetAdapterTest` | MockMvc/API契約＋定向adapterで申請経路を確認。実5業務browser（10経路）で申請draft作成と対象状態不変を確認済み | 証拠あり（browser実測済み） |
| R1.2 | routeを対象種別・組織・金額帯・申請者roleで決め、順次/並列stepを扱う | `RouteResolverServiceImpl`、`m_approval_route*`、`ApprovalRoute`/route DTO、`V79_1__approval_route_decision_sources.sql` | `RouteResolverServiceTest`のmin-1/min/min+1/max-1/max/max+1、組織具体性・帯幅・version優先、role条件優先/fallback | 境界・role fixture自動Demo、V79.1実MySQL migration/history/checksum/FK/CHECK/index確認済み（管理画面browserはR3.1参照） | 証拠あり（実MySQL確認済み） |
| R1.3 | USER/permission group/申請者上長/組織責任者/財務責任者からapproverを解決する | `RouteResolverServiceImpl`、`ApprovalAdministrationServiceImpl`、`ApprovalResponsibility`、route step DTO/API/UI | `RouteResolverServiceTest` 28件で3 source解決・responsibility期間/組織scope・group/user disabled/deleted・候補0/self-only・APPLICANT_MANAGER期間/mapper境界/snapshot永続化後のmanager変更不変を確認、`ApprovalAdministrationServiceTest` 13件で設定異常系、`ApprovalAdministrationApiControllerTest` 6件でHTTP 400/404と`ApiResult.code`を確認。R1.3対象計**47 / 0 / 0 / 0** | `RouteResolverServiceTest` 28件＋V79.1実MySQL schema/history、browser 10経路で申請者(営業/マネージャー)・承認者(管理者)の実ユーザー通し確認済み | 証拠あり |
| R1.4 | 申請者自身を承認不可。同一人物の複数step解決でも職務分離 | `ApprovalEngineServiceImpl`のself-approval除外、slot/quorum、`RouteSlot` | `ApprovalEngineServiceTest`の自己承認拒否、同一slot本人/代理先着、parallel quorum | 定向fixture＋browser 10経路（営業/マネージャー申請→管理者承認、自己承認なし）で確認済み | 証拠あり |
| R1.5 | approve/return/reject/withdraw、comment・時刻・代理理由を記録 | `ApprovalAction`、`ApprovalEngineServiceImpl`、approval API/DTO、delegation action fields | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalPageRenderTest` | MockMvc render/history＋browserで承認操作を実測。reject/return/withdrawは定向testで確認 | 証拠あり |
| R2.1 | 申請時version/diffを保存し、対象変更時はconflictとして古いsnapshotを適用しない | `ApprovalSnapshot`、V78 version、5 adapter `currentVersion`、request lock→target lock | `ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、`MigrationScriptIntegrityTest` | H2/定向競合fixture、V79.1実MySQL fresh/legacy/partial/repair/rollback、shared JDBC複数JVM ShedLock/claim確認済み。full application instance cronは要件外N/A | 証拠あり |
| R2.2 | 最終承認で既存service単件methodを1回だけ呼び、状態機械/監査を再実装しない | 5 `*ApprovalAdapter`、既存Quotation/Contract/Invoice/BpPayment/MonthlyClosing service | `ApprovalTargetAdapterTest`、`QuotationApiControllerTest`、`ContractApiControllerTest`、`InvoiceApiControllerTest`、`ContractPaginationTest` | M定向46件＋browser 10経路（申請→承認→適用で対象状態が1回だけ変化）確認済み | 証拠あり |
| R2.3 | 外部API/メールをtransaction内で呼ばず、commit後outbox/jobで実行 | V79、`NotificationOutboxService/Dispatcher/Scheduler`、`NotificationServiceImpl` | `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`NotificationOutboxSchedulerIntegrationTest` | scheduler二重起動で1行/SENT/attempt1、実MySQL commit前rollback、loopback endpoint JSON POST確認済み。外部Webhook provider・full application instance cronは要件外N/A | 証拠あり（loopback実HTTP実測） |
| R2.4 | retryで二重見積/請求/支払/外部連携を作らない | request CAS/idempotency、adapter registry、outbox dedupe UNIQUE | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、outbox tests | 同一request定向retry/claim＋browser 10経路の二重click/retry（申請1件・APPROVE action 1件）確認済み | 証拠あり |
| R3.1 | 管理者がrouteをversion付きで編集し、適用開始日を指定 | `ApprovalAdministrationServiceImpl`、route API/page/DTO、version_no/valid_from | `ApprovalAdministrationServiceTest`、`RouteResolverServiceTest`、`ApprovalPageRenderTest` | MockMvc/Thymeleaf管理画面契約＋route version/適用開始日fixture確認済み。管理画面browserはM対象外（5業務browser DemoはR5.1参照） | 証拠あり（定向） |
| R3.2 | 進行中申請は申請時route snapshotを使い、route変更で承認者を変えない | `RouteSnapshot`、`ApprovalRequest.routeSnapshotJson`、engine request/approve | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest` | snapshot fixture＋request→DB `route_snapshot_json`再読込で承認者不変を確認済み | 証拠あり |
| R3.3 | 代理は期間・対象・委任者/代理者・理由・承認を持ち、監査で代理表示 | `ApprovalDelegation`、`ApprovalDelegationType`、admin service、action.delegated_from | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`ApprovalViewServiceImplTest` | MockMvc/Thymeleafと期間境界fixture確認済み。代理承認の実ユーザー通しは定向test | 証拠あり（定向） |
| R3.4 | approver解決不能時は受付拒否し、管理者へ設定不足通知 | `RouteResolverServiceImpl` fail-closed、`notifyAdminsOfConfigGap`、approval notification | `RouteResolverServiceTest`の未設定/自己承認候補ゼロ、`ApprovalEngineServiceTest` | 拒否fixtureとコード経路、loopback実HTTP通知到達で確認済み | 証拠あり |
| R4.1 | 自分の申請、承認待ち、完了一覧、差分、comment、履歴を閲覧 | `ApprovalViewServiceImpl`、participant SQL、approval templates/JS/DTO | `ApprovalViewServiceImplTest`、`ApprovalViewPageBoundaryTest`、`ApprovalPageRenderTest`、`ApprovalUiContractTest` | MockMvc/Thymeleaf実描画＋browser 10経路（inbox/detail、desktop/390px）で確認済み | 証拠あり |
| R4.2 | 申請/差戻し/承認/却下/期限超過を対象本人だけへ通知 | `ApprovalNotificationKeys`、`ApprovalNotificationService`、`NotificationServiceImpl`、outbox | `ApprovalNotificationSlaTest`、`NotificationServiceImplTest`、outbox testsのrecipient/dedupe assert | scheduler相当Demo＋loopback endpoint JSON POSTで宛先限定・dedupe確認済み。外部Webhook providerは要件外N/A | 証拠あり |
| R4.3 | stepごとのSLA期限を持ち、期限超過を上位責任者へescalate | V77 step start、`ApprovalSlaService`、`ApprovalSlaScheduler`、dedupe key | `ApprovalNotificationSlaTest`の直前/時点/直後・NULL・重複、`ApprovalEngineServiceTest` | scheduler定向で期限境界を確認済み。外部Webhook providerは要件外N/A | 証拠あり（定向） |
| R5.1 | 申請者単独で5業務を確定できない | 対象5 API→`ApprovalTargetAdapterRegistry`、bypass権限なし | `QuotationApiControllerTest`、`ContractApiControllerTest`、`InvoiceApiControllerTest`、`ApprovalTargetAdapterTest` | M定向46件＋browser 10経路（申請者単独確定不可）確認済み | 証拠あり |
| R5.2 | 承認中の対象変更を検知し古いsnapshotを適用しない | version/CAS、target row lock、`conflict`遷移 | `ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、`ApprovalEngineServiceTest` | H2競合fixture＋実MySQL shared JDBC複数JVM ShedLock/claim確認済み。full application instance cronは要件外N/A | 証拠あり |
| R5.3 | 二重click/retryでも最終業務操作は1回 | request row lock、CAS、idempotency、outbox dedupe | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、outbox tests | scheduler二重起動相当＋browser 10経路の二重click/retry（申請1件・APPROVE action 1件・retry後再適用なし）確認済み | 証拠あり |
| R5.4 | route変更後も進行中申請の承認者は不変 | route snapshot固定、admin version追加 | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`RouteResolverServiceTest` | snapshot fixture＋request→DB snapshot再読込で承認者不変確認済み。route改版のbrowser通しはR3.2参照 | 証拠あり |

### Traceの判定

- R1〜R5の全20 ACについて、実装入口、assert入口、Demoの有無を行単位で固定した。
- R1.2/R1.3のR4-P1-01差分はV79.1、route管理経路、resolver、H2/testへ反映済み。実MySQL migration/history、shared JDBCの複数JVM ShedLock/claim、commit前例外時rollback、loopback送信、zero-skipped、**5業務desktop/390px browser Demo（10経路）**を確認済み。
- 旧記録にあった「外部provider・full application instance・browser未達によるNOT REVIEWABLE」は解消した。**外部provider・full application instance cronは要件外N/A**、browserは10経路実測済み。S07は技術・機能gate確認済みの**REVIEW**状態であり、文書整合の独立再Review待ち（review-ledger/manifest/中央ledgerを参照）。

## 4. Current Headのpublic contract consumer inventory

### 4.1 Migration / dispatch contract

- 実在SQLは`V75__approval_workflow.sql`、`V76__approval_menu.sql`、`V77__approval_sla_step_start.sql`、`V78__approval_workflow_round_participant_version.sql`、`V79__notification_webhook_outbox.sql`、`V79_1__approval_route_decision_sources.sql`の6本。
- S07はV75〜V79とV79.1、S09〜S17はV80〜V88の単一予約で固定する。V79.1はV79適用後・V80適用前のpatchであり、V75〜V79を編集しない。V80〜V88のSQLはcurrent Headに存在しない予約である。
- consumerは`README.md`、`parallel-execution-plan.md`、`spec-start-conversations.md`、`spec-review-conversations.md`、copyable start/review、各S09〜S17 design/tasks。`SpecDispatchConsistencyTest`がS07実在集合と後続単一予約を照合する。

### 4.2 Approval API/page/UI contract

- API: `ApprovalApiController`、`ApprovalAdministrationApiController`、`ApprovalPageController`。
- page/template: `approval/inbox.html`、`requests.html`、`detail.html`、`routes.html`。
- JS/CSS/sidebar: `approval.js`、`approval-routes.js`、`common.js`、`common.css`、`layout/sidebar.html`。
- domain: `ApprovalEngineService`、`ApprovalViewService`、`ApprovalAdministrationService`、`ApprovalTargetAdapterRegistry`、`ApprovalNotificationService`、`NotificationOutboxService`、`NotificationOutboxDispatcher`、`NotificationOutboxScheduler`、`ApprovalSlaScheduler`。

### 4.3 5業務9操作 contract

9操作の一次表は`operation-inventory.md` §2を正とする。各行のendpoint、既存service単件method、申請field、request type、金額源、scope、requirements IDをconsumer inventoryとして固定する。

- 見積: `QuotationApiController` / `QuotationServiceImpl` / `quotation.js` / `proposal-kanban.js` / `QuotationApprovalAdapter`。
- 契約: `ContractApiController` / `ContractServiceImpl` / `contract.js` / `contract-price-revision.js` / `ContractApprovalAdapter`。
- 請求: `InvoiceApiController` / `InvoiceServiceImpl` / `invoice.js` / `InvoiceApprovalAdapter`。
- BP支払: `InvoiceApiController` / `BpPaymentServiceImpl` / `bp-company.js` / `BpPaymentApprovalAdapter`。
- 月次締め: `MonthlyClosingApiController` / `MonthlyClosingServiceImpl` / `monthly-closing.js` / `MonthlyClosingApprovalAdapter`。

## 5. Verification / release gate

### 5.1 code baseline Headで記録された回帰と実測（現行Review evidence commit 646dbdaと分離）

- **code baseline Head** `68fbbba4dff8255b3a745ce61e73e686a78bef3e`（Base→Head **23 commits / 219 files / +11639/-337**）。`68fbbba`時点で`HEAD = origin/main = origin/HEAD`だった（**当時値**）。**初回Review evidence commit** `2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit** `646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**、seed修正＋browser evidence再生成）はcode baseline Headと分離して管理する。e88351d時点の基準はBase→**26 commits / 274 paths / +16873/-342**。現在の文書同期commitは`git log -1 -- <path>`で解決され、68fbbbaのHEAD/origin一致は当時値であり矛盾しない。Packet文書の独立commitはなく、文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceとして記載する（current Headとして自己参照しない）。
- R1.3追加対象testは`RouteResolverServiceTest` 28件（APPLICANT_MANAGER 8件追加）、`ApprovalAdministrationServiceTest` 13件、`ApprovalAdministrationApiControllerTest` 6件（responsibility逆期間HTTP 400追加）の計**47 / failures 0 / errors 0 / skipped 0**。request作成時にDBへ保存した`route_snapshot_json`を再読込し、manager変更後も同一requestの承認者が不変であることを確認した。
- migration/static/JSは`MigrationScriptIntegrityTest` 26件、`SpecDispatchConsistencyTest` 8件、`JsSyntaxCheckTest` 1件の計**35 / 0 / 0 / 0 / BUILD SUCCESS**。
- 再Review対象direct consumer regressionはR4-P1-01（7クラス）＋B1/M（13クラス）の**20クラス、150 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**。
- CI相当`pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`の最終実測はDocker `29.6.1`、Node `v24.18.0`、`mvn -B clean test` **1471 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**（01:09 h）。続くskip確認は**0 test cases / 0 report classes**で、script exit **0**。L4 zero-skippedは確認済み（このrunの再取得は不要）。なお、今回の作業木では`FlywayV79_1RepairSmokeTest`にpartial-state testを1メソッド追加したため、次回全量は1471→1472件になる（当該クラスは実MySQLで**2 / 0 / 0 / 0**を確認済み）。
- V79.1実MySQLはfresh/legacy適用でv79.1到達を確認し、`flyway_schema_history`、checksum、FK/CHECK/index assertionも確認済み。`FlywayV79_1RepairSmokeTest`をDocker Server `29.6.1`で実行し、**2 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**（test execution 1998 s / Maven total 34:17）を確認した。旧checksumによるvalidate失敗、`repair()`単独の危険性、forward DDL→V79.1限定allowlist repair→validate、partial history repair/re-migrate、clean/re-forward rollback rehearsalに加え、**今回の`v79_1-fk-actions-forward-repair.sql`再開可能性**を実MySQLで検証した。runbookは`information_schema`の状態判定で未適用DDLだけを実行するため、DROP後・FK追加後・CHECK追加後の各partial状態から再実行でき、最終schemaとV79.1限定repair/validateへ収束することを3状態それぞれで確認した（AFTER_DROP/AFTER_FK_ADD/AFTER_CHECK_ADD、各状態でrunbook2回実行の冪等性も確認）。
- scheduler H2はproduction既定の`app.scheduler.lock.use-db-time=true`を維持し、`application-test.yml`のtest profileだけ`false`へ切り替えた。`SchedulerLockH2IntegrationTest`でH2のlock warningを解消した回帰**3 / 0 / 0 / 0**を確認した。
- shared JDBCの複数JVM ShedLock/claim、commit前例外時の実DB rollbackは`OperationalBoundaryMySqlIntegrationTest`で**3 / 0 / 0 / 0**、lock遷移`LOCK_ACQUIRED pid=...`→`LOCK_NOT_ACQUIRED pid=...`→`LOCK_RELEASED`→`LOCK_ACQUIRED pid=...`、outbox claimは`CLAIM_RESULT=1`/`CLAIM_RESULT=0`、DB状態`PROCESSING`、`attempt_count=1`を確認した。adapter後の意図的例外ではapproval action/request/Quotation対象行が実MySQL transactionでrollbackした。
- Webhook loopbackは`WebhookNotifierLoopbackIntegrationTest` **1 / failures 0 / errors 0 / skipped 0**で、`127.0.0.1` endpointへの実`RestTemplate` JSON POSTを確認した。
- desktop/390px 5業務browser Demoは実Chrome `150.0.7871.187`（Playwright経由、headless実ブラウザ）で**10経路（5業務×desktop/390px）全てPASS**した。各業務で（a）申請者単独では対象状態が変わらない（申請者単独確定不可）、（b）申請→承認→適用で対象状態が1回だけ変わる、（c）申請時の二重click/retryでも申請は1件のみ（idempotency key一意制約でdedupe）、（d）承認時の二重clickでもAPPROVE actionは1件のみ・retry後も業務操作は再適用されない（`error.approval.invalidState`で安全に拒否）を実browserで確認した。証拠は`evidence/browser-m/`配下のスクリーンショット40枚とJSON 11ファイル（各経路のbefore/after状態・申請数・action数・retry安定性）として記録済み。内訳: 見積提出（Q-202608-0001/0002 下書き→提出済）、契約稼動化（C-2026-0001/0002 準備中→稼動中）、請求送付（INV-202607-0001/0002 未送付→送付済）、BP支払確定（bp_payment 1/2 未払→支払済）、月次締め（2026-05/2026-04 open→closed、管理者が適用）。

上記は定向/静的/H2 evidence、fresh/legacyの実MySQL smoke、V79.1-specific partial/repair/rollback、複数JVM ShedLock/claim、commit前rollback、loopback Webhook、実browser 5業務Demoを含む。

### 5.1.1 残存gateの判定（N/A化）

旧記録で「未達」とされていた次の2 gateは、元要件上の正式要求ではないためN/Aへ変更する（「必要な場合」のまま放置しない）。

1. **2つのfull application instanceによるcron end-to-end（N/A）**: requirements.md R2.3/R2.4/R4.2は「外部送信をDB transaction内で行わずcommit後outbox/jobで実行し、再送で二重外部連携を作らない」ことを要求しており、デプロイ構成として「2つのfull application instance」を要求していない。単一writer保証はDBレベルの`t_shedlock`（ShedLock）とoutbox claim（`UPDATE ... WHERE status='PENDING'` を`REQUIRES_NEW`で直列化）で実現され、JVM数に依存しない。このDB境界の性質は`OperationalBoundaryMySqlIntegrationTest`（2 JVM・1共有DB、`LOCK_NOT_ACQUIRED`/`CLAIM_RESULT=0`）で実測済みであり、2つのfull application instanceを起動しても同じDBロック/claim経路を通るため、追加の性質を検証しない。よって正式要件外としてN/A。
2. **外部provider相当のWebhook endpoint到達（N/A）**: R2.3は「外部API/メール送信をtransaction内で呼ばない」こと（呼出タイミングの制約）を要求しており、特定の実外部providerとの統合は要求していない。Webhook URLは`m_system_config`の設定値であり、未設定時は配信対象外として`SENT`扱い（design §1.2）。実HTTP送信経路（RestTemplate→実endpoint）は`WebhookNotifierLoopbackIntegrationTest`で実測済みであり、実providerの選択はデプロイ設定に属する。よって正式要件外としてN/A。

### 5.2 Issue / task判定

| ID / task | code baseline Head / Review evidence commit判定 | 根拠 |
|---|---|---|
| `R4-REVIEW-01` | **VERIFIED_CLOSED** | manifestをcode baseline Head `68fbbba4dff8255b3a745ce61e73e686a78bef3e`の219 unique committed paths（#001〜#212＋今回の7 unique paths #213〜#219）と初回Review evidence commit `2978461`（履歴）・現行Review evidence/result commit `646dbda`（browser evidence・Packet文書）へ整理し、R1〜R5の20 AC traceと範囲外consumer分離を再確認した |
| `R4-REVIEW-02` | **VERIFIED_CLOSED** | V75〜V79とV79.1 patch、V80〜V88予約、static 35/0/0/0の静的整合を確認。実MySQL gateとは分離 |
| `R4-REVIEW-03` | **VERIFIED_CLOSED** | B1/T046・M/T047 checkboxは`[x]`。shared JDBCの複数JVM ShedLock/claim、commit前例外時rollback、loopback webhook、CI相当L4 1471/0/0/0 zero-skippedに加え、5業務desktop/390px browser Demo（10経路）を実測。full application instance cron・外部providerは§5.1.1のとおりN/A |
| `R4-REVIEW-04` | **VERIFIED_CLOSED** | code baseline Head `68fbbba4dff8255b3a745ce61e73e686a78bef3e`（23 commits、219 paths）と初回Review evidence commit `2978461`（履歴）・現行Review evidence/result commit `646dbda`（27 commits、274 paths）へ同期し、worktree clean、Packet文書4ファイルの独立commitなし・文書commitは`git log -1 -- <path>`で解決するprovenanceであることを確認した |
| `approval-workflow-internal-control-R4-P1-01` | **VERIFIED_CLOSED** | R1.2/R1.3のcode/H2/境界・異常系test **47/0/0/0**、direct regression green、V79.1実MySQL fresh/legacy、履歴、checksum/FK/CHECK/index assertion、partial/repair/rollback、runbookの再開可能性（3 partial状態）、shared JDBCの複数JVM ShedLock/claim、commit前例外時rollback、loopback webhookを全て確認済み |
| `T046/B1` | **完了** | 定向B1/scheduler回帰、H2 lock warning回帰、shared JDBCの複数JVM ShedLock/claim、commit前例外時rollback、loopback webhookを確認。full application instance cron・外部providerは§5.1.1のとおりN/A |
| `T047/M` | **完了** | M定向回帰 green、CI相当L4 **1471 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**、shared JDBCの複数JVM/commit rollback、loopback送信、5業務desktop/390px browser Demo（10経路）を確認 |

**総合判定:** **REVIEW（独立再Review待ち）**。S07の技術・機能gateは独立Reviewで全て確認済み（R4-P1-01・B1/Mは`[x]`、R4-REVIEW-01/02/03/04はVERIFIED_CLOSED）。本manifestの文書同期（Head分離・AC trace同期）の整合を独立再Reviewで確認後、S07 PASS・S09 READY・Wave 2解放へ一括遷移する。それまではS09=`NOT READY`、Wave 2=`未解放`を維持する。

### 5.3 再Review / 再開条件

1. 独立Reviewで219 committed paths（#001〜#212＋#213〜#219）のstatus/count/個別帰属、R1〜R5の20 AC trace、範囲外consumer分離を確認する。
2. Packet、Issue Register、中央台帳の**code baseline Head** `68fbbba`（23 commits / 219 paths、`68fbbba`時点の`HEAD = origin/main = origin/HEAD`は当時値）と**初回Review evidence commit** `2978461`（履歴、24 commits / 272 paths）と**現行Review evidence/result commit** `646dbda`（27 commits / 274 paths）を分離した状態で再確認する（e88351d時点の基準はBase→**26 commits / 274 paths / +16873/-342**）。現在の文書同期commitは`git log -1 -- <path>`で解決され、68fbbbaのHEAD/origin一致（当時値）と矛盾しないことを確認する。Packet文書の独立commitはなく、文書commitは`git log -1 -- <path>`で解決するprovenanceとして記載されていることを確認する。
3. `evidence/browser-m/`のスクリーンショットとJSON（5業務×desktop/390px、申請者単独確定不可・申請1件・適用1回・APPROVE action 1件・retry安定）を独立Reviewで確認する。
4. 独立再Reviewで文書整合（Head分離・AC trace同期・中央ledger row 7〜9の統一）を確認後、S07 PASS・S09 READY・Wave 2解放へ一括遷移する。
