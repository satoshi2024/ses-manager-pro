# S07 Round 4 current Head Review manifest

> ファイル名は既存Packetからの参照互換のため維持する。内容の基準は旧`10dc316d`および旧Packet Head `0a724356`ではなく、実Gitのcurrent Head `74329e9c982af24e10409b564c5d89a56ef4e2cd`である。
>
> このmanifestは受入PASSの宣言ではない。Base→current Headの全pathを一行ずつtask/commitへ帰属し、R1〜R5をAC→実装→assert→Demoの順で追跡可能にするためのReview証跡である。未達gateと、テスト追加を含む未commitの作業木差分は未達のまま記録する。211 pathsはcommit済みHeadの範囲であり、作業木の追加3 test filesは別途記録する。

## 1. 対象とGit確定値

| 項目 | current Headで確認した値 |
|---|---|
| 対象spec | `approval-workflow-internal-control`（S07） |
| Review Base | `5d228d211d0d752833fe3424a3b8aa4b40096733` |
| original implementation Head | `a70cb51145a94ec3d70421bcc1de77a6b236b559` |
| Packet統合commit | `9215c5e797d063d13719b231175ab8741736a591` |
| current Head | `74329e9c982af24e10409b564c5d89a56ef4e2cd` |
| current refs | `HEAD = origin/main = origin/HEAD = 74329e9c982af24e10409b564c5d89a56ef4e2cd` |
| branch / worktree | `main` / dirty（R4-P1-01の追加回帰test 3 filesが未commit。commit/pushは未実施） |
| Base→current Head | **18 commits / 211 files / +9684 / -330** |
| 直前fix-delta | `0a724356..74329e9`、26 files、`+698/-105` |
| 直前fix-deltaの範囲 | R4-P1-01のroute source実装・migration/H2/test・Packet/ledger同期。既存pathの更新に加え、manifestへ追加する6 pathsを含む |
| merge状態 | `74329e9`は`origin/main`へ反映済み。今回のR1.3追加回帰test 3 filesは未commit・未push |
| diff check | current worktreeはPASS（`git diff --check` exit 0）。未追跡test fileはgit diff対象外のため、statusと合わせて確認する |
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

### Current worktree delta（current Headには未反映）

- current Head `74329e9`には、R4-P1-01としてV75を変更せず`V79_1__approval_route_decision_sources.sql`を追加した変更、`applicant_role_condition`をroute entity/DTO/API/UI/resolverへ通した変更、`PERMISSION_GROUP`・`ORGANIZATION_MANAGER`・`FINANCE_MANAGER`のas-of解決、H2 schema/fixture同期が反映済みである。
- `0a724356..74329e9`で新たにcurrent manifestへ追加すべき6 pathsは、`ApprovalResponsibilitySaveRequest.java`、`ApprovalResponsibilityView.java`、`ApprovalResponsibility.java`、`ApprovalResponsibilityMapper.java`、`UserPermissionGroupMapper.java`、`V79_1__approval_route_decision_sources.sql`である。§2.9へ個別帰属した。
- current Head後のR1.3追加回帰は未commitの3 filesとして作業木に残る: `src/test/java/com/ses/controller/api/ApprovalAdministrationApiControllerTest.java`、`src/test/java/com/ses/service/impl/ApprovalAdministrationServiceTest.java`、`src/test/java/com/ses/service/impl/RouteResolverServiceTest.java`。これらは211 committed pathsには含めない。
- compileおよびR1.3対象testはgreen。Docker unavailableのため実MySQL gateは未達であり、作業木は`main`に対してdirtyのまま維持する。

### 帰属コード

| コード | task / scope | Review上の扱い |
|---|---|---|
| `T041` | G7・9操作inventory | 変更は調査/spec文書のみ |
| `T042/F1` | route/request/action/delegation engine・DDL | S07実装 |
| `T043/F2` | 5 target adapter・対象API委譲 | S07実装 |
| `T044/A1` | inbox/request/diff/history UI | S07実装 |
| `T045/A2` | route version・代理管理・R4-P1-01 route source管理 | S07実装と未達gateを分離 |
| `T046/B1` | 通知/SLA/outbox/escalation | 実装済み部分と未達gateを分離 |
| `T047/M` | 5業務画面統合・回帰 | checkboxは未完了を維持 |
| `R3-FIX` | Round 3修正・共有fixture/回帰 | S07由来またはshared consumerとして記録 |
| `MIGRATION-CONTRACT` | S07 V75〜V79/V79.1、S09〜S17 V80〜V88の予約consumer | 後続specの予約文書。S07 production実装とは分離 |
| `R4-DOC` | Round 4 Packet/manifest/ledger correction | current HeadとworktreeのReview process record |
| `SHARED` | S07以外の既存shared consumer・範囲外spec文書 | S07の受入PASSへ加算しない |

## 2. Base→current Headの全path manifest

以下は`git diff --name-status --no-renames 5d228d2..74329e9`の全211 pathである。各行にstatus、primary task/scope、変更を含む代表commitを記録する。同一pathが複数commitで変更された場合、commit欄は最終的な実装・証跡上の代表commitであり、§3のcommit履歴と併読する。current Head後の未commit test差分は§1のCurrent worktree deltaと§5で別管理する。

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

### 2.9 current Headで追加された6 paths

`0a724356..74329e9`の差分は26 filesだが、そのうち既存manifest pathの更新を除く新規pathは次の6件である。いずれもcommit `74329e9`に含まれ、未commit test 3 filesとは区別する。

| # | status | path | primary task / scope | commit |
|---:|---|---|---|---|
| 206 | A | `src/main/java/com/ses/dto/approval/ApprovalResponsibilitySaveRequest.java` | `T045/A2`/`R4-P1-01` responsibility assignment command | `74329e9` |
| 207 | A | `src/main/java/com/ses/dto/approval/ApprovalResponsibilityView.java` | `T045/A2`/`R4-P1-01` responsibility assignment view | `74329e9` |
| 208 | A | `src/main/java/com/ses/entity/ApprovalResponsibility.java` | `T045/A2`/`R4-P1-01` organization responsibility entity | `74329e9` |
| 209 | A | `src/main/java/com/ses/mapper/ApprovalResponsibilityMapper.java` | `T045/A2`/`R4-P1-01` responsibility mapper | `74329e9` |
| 210 | M | `src/main/java/com/ses/mapper/UserPermissionGroupMapper.java` | `R4-P1-01` permission-group active membership query | `74329e9` |
| 211 | A | `src/main/resources/db/migration/V79_1__approval_route_decision_sources.sql` | `R4-P1-01` route role condition/responsibility patch migration | `74329e9` |

**path集計:** S07 packet 5 + roadmap dispatch 25 + other spec 18 + production Java 88 + migration SQL 5 + frontend/resources 30 + test Java 29 + test resources 5 + current Head additions 6 = **211**。重複0、未分類0。current worktreeの未commit test 3 filesはこのHead manifestの集計外である。

## 3. R1〜R5 AC trace (AC→実装→assert→Demo)

以下の`assert`は定向testまたは静的契約の入口、`Demo`は自動Demo相当を含む。`未達`は受入不成立を意味し、定向assertをPASSへ拡張しない。

| AC | 受入条件 | 実装 / consumer | assert / evidence | Demo evidence | 状態 |
|---|---|---|---|---|---|
| R1.1 | 対象操作を直接確定せず、申請draftと差分snapshotを作る | `ApprovalEngineServiceImpl.request`、`ApprovalSnapshot`、`ApprovalApiController`、5 adapter、`operation-inventory.md` §2 | `ApprovalEngineServiceTest`、`ApprovalApiControllerTest`、`ApprovalTargetAdapterTest` | MockMvc/API契約と定向adapterで申請経路を確認。実5業務curl/browserは未実施 | 定向証拠あり、実Demo未達 |
| R1.2 | routeを対象種別・組織・金額帯・申請者roleで決め、順次/並列stepを扱う | `RouteResolverServiceImpl`、`m_approval_route*`、`ApprovalRoute`/route DTO、`V79_1__approval_route_decision_sources.sql` | `RouteResolverServiceTest`のmin-1/min/min+1/max-1/max/max+1、組織具体性・帯幅・version優先、role条件優先/fallback | 境界・role fixture自動Demoあり。実MySQL migration/管理画面browser Demoは未実施 | 定向証拠あり、実MySQL/実Demo未達 |
| R1.3 | USER/permission group/申請者上長/組織責任者/財務責任者からapproverを解決する | `RouteResolverServiceImpl`、`ApprovalAdministrationServiceImpl`、`ApprovalResponsibility`、route step DTO/API/UI | `RouteResolverServiceTest` 19件で3 source解決・responsibility期間/組織scope・group/user disabled/deleted・候補0/self-onlyを確認、`ApprovalAdministrationServiceTest` 13件で設定異常系、`ApprovalAdministrationApiControllerTest` 5件でHTTP 400/404と`ApiResult.code`を確認。R1.3対象計37/0/0/0 | H2 preview/管理画面契約あり。実MySQL・実role別browser Demoは未実施 | 定向証拠あり、実MySQL gate未達 |
| R1.4 | 申請者自身を承認不可。同一人物の複数step解決でも職務分離 | `ApprovalEngineServiceImpl`のself-approval除外、slot/quorum、`RouteSlot` | `ApprovalEngineServiceTest`の自己承認拒否、同一slot本人/代理先着、parallel quorum | 定向fixtureで確認。5業務の実ユーザー通しは未実施 | 定向証拠あり、実Demo未達 |
| R1.5 | approve/return/reject/withdraw、comment・時刻・代理理由を記録 | `ApprovalAction`、`ApprovalEngineServiceImpl`、approval API/DTO、delegation action fields | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalPageRenderTest` | MockMvc render/history相当あり。実画面操作は未実施 | 定向証拠あり、実Demo未達 |
| R2.1 | 申請時version/diffを保存し、対象変更時はconflictとして古いsnapshotを適用しない | `ApprovalSnapshot`、V78 version、5 adapter `currentVersion`、request lock→target lock | `ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、`MigrationScriptIntegrityTest` | H2/定向競合fixtureあり。V78/V79を含む実MySQL fresh/legacy/partial/repair/rollbackは未達 | 定向証拠あり、実MySQL gate未達 |
| R2.2 | 最終承認で既存service単件methodを1回だけ呼び、状態機械/監査を再実装しない | 5 `*ApprovalAdapter`、既存Quotation/Contract/Invoice/BpPayment/MonthlyClosing service | `ApprovalTargetAdapterTest`、`QuotationApiControllerTest`、`ContractApiControllerTest`、`InvoiceApiControllerTest`、`ContractPaginationTest` | M定向46件で委譲回帰。5業務curl→承認→適用Demoとbrowser通しは未実施 | 定向証拠あり、実Demo未達 |
| R2.3 | 外部API/メールをtransaction内で呼ばず、commit後outbox/jobで実行 | V79、`NotificationOutboxService/Dispatcher/Scheduler`、`NotificationServiceImpl` | `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`NotificationOutboxSchedulerIntegrationTest` | scheduler二重起動で1行/SENT/attempt1を確認。実Webhook endpointとcommit前例外rollbackは未達 | 定向Demo相当あり、release gate未達 |
| R2.4 | retryで二重見積/請求/支払/外部連携を作らない | request CAS/idempotency、adapter registry、outbox dedupe UNIQUE | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、outbox tests | 同一request定向retry/claimを確認。5業務への実10回再送Demoは未実施 | 定向証拠あり、実Demo未達 |
| R3.1 | 管理者がrouteをversion付きで編集し、適用開始日を指定 | `ApprovalAdministrationServiceImpl`、route API/page/DTO、version_no/valid_from | `ApprovalAdministrationServiceTest`、`RouteResolverServiceTest`、`ApprovalPageRenderTest` | MockMvc/Thymeleaf管理画面契約あり。実browser管理画面は未実施 | 定向証拠あり、実Demo未達 |
| R3.2 | 進行中申請は申請時route snapshotを使い、route変更で承認者を変えない | `RouteSnapshot`、`ApprovalRequest.routeSnapshotJson`、engine request/approve | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest` | snapshot fixtureで確認。実運用route変更→承認のbrowser Demoは未実施 | 定向証拠あり、実Demo未達 |
| R3.3 | 代理は期間・対象・委任者/代理者・理由・承認を持ち、監査で代理表示 | `ApprovalDelegation`、`ApprovalDelegationType`、admin service、action.delegated_from | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`ApprovalViewServiceImplTest` | MockMvc/Thymeleafと期間境界fixtureあり。実browser/実ユーザー代理承認は未実施 | 定向証拠あり、実Demo未達 |
| R3.4 | approver解決不能時は受付拒否し、管理者へ設定不足通知 | `RouteResolverServiceImpl` fail-closed、`notifyAdminsOfConfigGap`、approval notification | `RouteResolverServiceTest`の未設定/自己承認候補ゼロ、`ApprovalEngineServiceTest` | 拒否fixtureとコード経路確認。実通知到達Demoは未実施 | 部分証拠、実通知未達 |
| R4.1 | 自分の申請、承認待ち、完了一覧、差分、comment、履歴を閲覧 | `ApprovalViewServiceImpl`、participant SQL、approval templates/JS/DTO | `ApprovalViewServiceImplTest`、`ApprovalViewPageBoundaryTest`、`ApprovalPageRenderTest`、`ApprovalUiContractTest` | MockMvc/Thymeleaf実描画とresponsive markupあり。desktop/390px browserは未実施 | 定向証拠あり、実browser未達 |
| R4.2 | 申請/差戻し/承認/却下/期限超過を対象本人だけへ通知 | `ApprovalNotificationKeys`、`ApprovalNotificationService`、`NotificationServiceImpl`、outbox | `ApprovalNotificationSlaTest`、`NotificationServiceImplTest`、outbox testsのrecipient/dedupe assert | scheduler相当Demoあり。実Webhook endpoint/外部到達は未実施 | 定向証拠あり、実Webhook未達 |
| R4.3 | stepごとのSLA期限を持ち、期限超過を上位責任者へescalate | V77 step start、`ApprovalSlaService`、`ApprovalSlaScheduler`、dedupe key | `ApprovalNotificationSlaTest`の直前/時点/直後・NULL・重複、`ApprovalEngineServiceTest` | scheduler定向で境界を確認。実時刻運用・実Webhookは未実施 | 定向証拠あり、実Demo未達 |
| R5.1 | 申請者単独で5業務を確定できない | 対象5 API→`ApprovalTargetAdapterRegistry`、bypass権限なし | `QuotationApiControllerTest`、`ContractApiControllerTest`、`InvoiceApiControllerTest`、`ApprovalTargetAdapterTest` | M定向46件。5業務のdesktop/390px browser通しは未実施 | 定向証拠あり、実受入未達 |
| R5.2 | 承認中の対象変更を検知し古いsnapshotを適用しない | version/CAS、target row lock、`conflict`遷移 | `ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、`ApprovalEngineServiceTest` | H2競合fixture。実MySQL複数writer/複数JVMは未実施 | 定向証拠あり、実競合gate未達 |
| R5.3 | 二重click/retryでも最終業務操作は1回 | request row lock、CAS、idempotency、outbox dedupe | `ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`ApprovalTargetAdapterTest`、outbox tests | scheduler二重起動相当は確認。5業務各10回の実curl/browserは未実施 | 定向証拠あり、実Demo未達 |
| R5.4 | route変更後も進行中申請の承認者は不変 | route snapshot固定、admin version追加 | `ApprovalAdministrationServiceTest`、`ApprovalEngineServiceTest`、`RouteResolverServiceTest` | snapshot fixture。実browserによるroute改版通しは未実施 | 定向証拠あり、実Demo未達 |

### Traceの判定

- R1〜R5の全20 ACについて、実装入口、assert入口、Demoの有無を行単位で固定した。
- R1.2/R1.3のR4-P1-01差分はV79.1、route管理経路、resolver、H2/testへ反映済みで、定向証拠はgreen。ただし実MySQL migration、実browser、zero-skippedは未達のため総合受入は`NOT REVIEWABLE`のままとする。
- `定向証拠あり`はACの受入完了を意味しない。実MySQL、複数JVM、実Webhook、rollback、browser、zero-skippedが未達のため、総合受入は`NOT REVIEWABLE`のままとする。

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

### 5.1 current Head / current worktreeで記録された回帰

- current Head `74329e9`のBase→Headは**18 commits / 211 files / +9684/-330**。`HEAD = origin/main = origin/HEAD`を確認した。
- R1.3追加対象testは`RouteResolverServiceTest` 19件、`ApprovalAdministrationServiceTest` 13件、`ApprovalAdministrationApiControllerTest` 5件の計**37 / failures 0 / errors 0 / skipped 0**。3 filesは未commit作業木にある。
- migration/static/JSは`MigrationScriptIntegrityTest` 26件、`SpecDispatchConsistencyTest` 8件、`JsSyntaxCheckTest` 1件の計**35 / 0 / 0 / 0 / BUILD SUCCESS**。
- 20クラスdirect regression（R4-P1-01 consumer 7クラス＋B1/M 13クラス）は**153 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**。
- CI相当`pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`はNode `v24.18.0`を検出し、`mvn -B clean test`を完走。結果は**1454 / failures 0 / errors 0 / skipped 12 / Maven BUILD SUCCESS**、scriptはskip検出でexit 1となった。
- skipはDocker依存の12 test cases / 10 report classes: `CustomerContactPrimaryConcurrencyTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`ConcurrentLoginSessionSmokeTest`。DockerなしのためL4 zero-skippedは未達である。
- `git status --short --branch`は`main...origin/main`に対し上記test 3 files（M 2、?? 1）、`git diff --check`はexit 0。commit/pushは実施していない。

上記は定向/静的/H2 evidenceであり、以下のrelease gateを代替しない。

1. V79.1を含む実MySQL fresh/legacy/partial/repair/rollback/lock。
2. `flyway_schema_history`の適用確認。
3. 複数JVMのShedLock/claim競合。
4. 実Webhook endpoint到達。
5. commit前例外時の実DB rollback。
6. desktop/390pxでの5業務通しbrowser Demo。
7. L4 zero-skipped。

### 5.2 Issue / task判定

| ID / task | current Head / worktree判定 | 根拠 |
|---|---|---|
| `R4-REVIEW-01` | **OPEN** | manifestをcurrent Head `74329e9`の211 committed pathsへ拡張し、追加6 pathsの個別帰属を記録した。未commit test 3 filesは別管理で、独立Reviewによる完全性確認前 |
| `R4-REVIEW-02` | **VERIFIED_CLOSED** | V75〜V79とV79.1 patch、V80〜V88予約の静的整合、およびstatic 35/0/0/0を維持。実MySQL gateとは分離 |
| `R4-REVIEW-03` | **OPEN** | B1/T046・M/T047 checkboxは`[ ]`。実環境DoD、browser、rollback、複数JVM、Webhook、zero-skipped未達 |
| `R4-REVIEW-04` | **OPEN** | Packet/manifest/中央ledgerのcurrent Head値を`74329e9`、211 paths、18 commitsへ同期したが、独立Review再確認前 |
| `approval-workflow-internal-control-R4-P1-01` | **OPEN / P1** | R1.2/R1.3のcode/H2/境界・異常系testは定向37/0/0/0、direct regressionはgreen。ただしV79.1実MySQL適用・履歴/repair/rollback未確認のため受入上は未解消 |
| `T046/B1` | **未完了** | 定向B1/scheduler回帰はgreenだが、実MySQL/複数JVM/Webhook/rollback未達 |
| `T047/M` | **未完了** | M定向回帰はgreen、L4はfailure 0まで到達したがskip 12、browser、実MySQL等未達 |

**総合判定:** `NOT REVIEWABLE`。S07は`IN PROGRESS`、S09およびWave 2は解放不可。R4-P1-01は追加回帰を提出済みだが、実MySQL等の検証gate完了までは`OPEN / P1`を維持する。

### 5.3 再Review / 再開条件

1. 独立Reviewで211 committed pathsのstatus/count/個別帰属、R1〜R5の20 AC trace、範囲外consumer分離を確認する。
2. Packet、Issue Register、中央台帳のHead/merge/diff値を`74329e9`と一致した状態で再確認する。未commit test 3 filesはclean条件を満たさないため、commit禁止の現状ではdirtyとして記録する。
3. R4-P1-01についてV79.1の実MySQL fresh/legacy/repair適用、schema/history、設定→resolver→snapshotの通し証拠を取得する。
4. B1/Mの未達gateを同一Headで実証し、`tasks.md`のDemo/release gateとcheckboxを更新する。未実証項目を完了扱いにしない。
5. S07の正式Review PASS後にのみS09/Wave 2の開始判定を行う。
