# S07 Round 4 current Head Review manifest

## 1. 対象とGit証拠

- 対象spec: `approval-workflow-internal-control`（S07）
- Review Base: `5d228d211d0d752833fe3424a3b8aa4b40096733`
- original implementation Head: `a70cb51145a94ec3d70421bcc1de77a6b236b559`
- Packet統合commit: `9215c5e797d063d13719b231175ab8741736a591`
- review baseline Head before this documentation correction: `10dc316d003d7070b7b232056d2c17a240274bb8`
- baseline `HEAD = origin/main = origin/HEAD`: `10dc316d003d7070b7b232056d2c17a240274bb8`; this correction is intentionally a new local commit after validation and is not pushed automatically
- baseline branch: `main`
- baseline worktree: clean、untrackedなし
- Base→current Head: **204 files / 8670 insertions / 328 deletions**、15 commits
- Packet fix-delta `9215c5e..10dc316d`: **8 files / 167 insertions / 69 deletions**
- `git diff --check 5d228d2..10dc316d`: exit 0
- worktree `git diff --check`: exit 0
- production code・migration SQLはPacket fix-delta（`9215c5e..10dc316d`）では変更していない。

再現コマンド:

```powershell
git diff --name-status --no-renames 5d228d211d0d752833fe3424a3b8aa4b40096733..10dc316d003d7070b7b232056d2c17a240274bb8
git diff --stat --no-renames 5d228d211d0d752833fe3424a3b8aa4b40096733..10dc316d003d7070b7b232056d2c17a240274bb8
git diff --check 5d228d211d0d752833fe3424a3b8aa4b40096733..10dc316d003d7070b7b232056d2c17a240274bb8
```

## 2. 完全path manifest（Base→current Head）

次の正規表現partitionは、`git diff --name-only 5d228d2..10dc316d`の204 pathを重複なく全て分類する。件数合計は204、未分類は0である。S07実装、S07検証、roadmap予約変更、範囲外spec文書を混同しないため、path分類とcommit分類を併記する。

| partition | path predicate | 件数 | Review上の帰属 |
|---|---|---:|---|
| S07 spec packet | `.kiro/specs/approval-workflow-internal-control/` | 4 | S07のrequirements/design/tasks/operation-inventory/Review Packet。S07正本 |
| Roadmap dispatch docs | `.kiro/specs/customer-product-expansion-2026/` | 25 | S07〜S17の派工・採番consumer・中央ledger。S07のmigration契約変更と後続spec予約の範囲 |
| Other spec docs | `.kiro/specs/`（上記2 partitionを除く） | 18 | S09〜S17のdesign/tasksおよび既存specの採番consumer。S07 production実装の範囲外として帰属 |
| Production Java | `src/main/java/` | 88 | S07のapproval engine/API/adapter/notification/UI連携と共有consumer。個別commitのtask帰属は§3 |
| Migration SQL | `src/main/resources/db/migration/` | 5 | S07正式migration V75〜V79。今回のfix-deltaでは変更なし |
| Frontend/resources | `src/main/resources/`（migration SQLを除く） | 30 | S07画面、JS、message、sidebar、共有UI consumer。個別commitのtask帰属は§3 |
| Test Java | `src/test/java/` | 29 | S07定向回帰、migration/static整合、共有consumer回帰 |
| Test resources | `src/test/resources/` | 5 | S07 H2 schema/seed/application test設定と共有fixture |
| **合計** | — | **204** | **未分類0** |

### 2.1 commit/task帰属

下表の件数は各commitで変更されたpath数であり、同一pathが複数commitで変更されるため加算値ではない。Base→Headの完全path partitionは§2、commit別の監査入口は下表とする。

| commit | 変更件数 | task / 内容 | 範囲判定 |
|---|---:|---|---|
| `4edb399` | 41 | T041/T042 F1 route/request/action/delegation engine・DDL merge | S07実装 |
| `9a57eeb` | 24 | Round 1〜3のRV1-01〜RV1-12修正 | S07中心。一部shared consumerはpath別にS07/範囲外を分離 |
| `d440eb5` | 74 | T043〜T045相当の業務画面統合・adapter経路 | S07実装 |
| `7b6bb0b` | 33 | design/decision/採番・派工文書補正 | S07およびroadmap consumer |
| `0b83610` | 1 | 採番衝突対応 | roadmap/migration contract |
| `19ae85c` | 1 | 追跡記録 | Review record |
| `1e204df` | 12 | S07設計・実装前提の補正 | S07 spec/Review |
| `5110f12` | 46 | Round 3指摘対応 | S07実装・test。shared pathはpath partitionで帰属 |
| `a33a6e9` | 17 | P1-09 A′ / P2-16 / P2-11対応 | S07 migration/test/approval boundary |
| `b380a5a` | 19 | Round 3追跡Review訂正 | Review evidence / B1・M gate記録 |
| `df674db` | 4 | RG-3/B1追跡続行 | B1/M evidence記録 |
| `a70cb51` | 2 | P1-10 test oracle修正 | migration smoke test evidence |
| `9215c5e` | 5 | S07 migration採番consumer fix | S07 V75〜V79、S09〜S17 V80〜V88 |
| `10dc316d` | 8 | Packet/ledger追跡更新とdirect regression記録 | Review process record |

`git show --stat --oneline <commit>`および§2のrange diffで、各taskの変更pathと範囲外pathを再現できる。S07実装とroadmap文書を同一taskのproduction変更として扱わない。

## 3. R1〜R5 requirements trace

これはcurrent Headに対する静的traceと証拠入口であり、未達の実環境gateをPASSへ読み替えるものではない。

| requirement | current Headの実装/成果物 | 直接検証・証拠 | 状態 |
|---|---|---|---|
| R1 申請/承認 | `ApprovalEngineServiceImpl`、`RouteResolverServiceImpl`、`ApprovalApiController`、`ApprovalTargetAdapterRegistry`、`design.md` §3/§6 | `ApprovalEngineServiceTest`、`RouteResolverServiceTest`、`ApprovalViewServiceImplTest`、`operation-inventory.md` §2 | 定向実装・回帰あり。実環境gateは別管理 |
| R2 対象整合性 | `ApprovalSnapshot`、5 adapter、対象API/service consumer、`V78`/`V79`、`ApprovalEngineConflictTest` | `ApprovalTargetAdapterTest`、`ApprovalEngineConflictTest`、`FlywayMigrationSmokeTest`、`MigrationScriptIntegrityTest` | H2/定向PASS。実MySQL・rollback・複数JVMは未達 |
| R3 route/代理 | `ApprovalAdministrationServiceImpl`、`ApprovalAdministrationApiController`、route/代理画面・DTO、V78 | `ApprovalAdministrationServiceTest`、`RouteResolverServiceTest`、`ApprovalPageRenderTest` | 定向PASS。実browser/MySQLは未達 |
| R4 UI/通知/SLA | `ApprovalViewServiceImpl`、`ApprovalNotificationKeys`、`ApprovalSlaService`、outbox dispatcher/scheduler、approval templates/JS | `ApprovalNotificationSlaTest`、`NotificationOutbox*Test`、`ApprovalUiContractTest`、`NotificationOutboxSchedulerIntegrationTest` | 定向PASS。実Webhook・複数JVMは未達 |
| R5 受入 | `Quotation/Contract/Invoice/MonthlyClosing` API consumer、5 adapter、Mの画面統合記録 | `QuotationApiControllerTest`、`ContractApiControllerTest`、`ContractPaginationTest`、`InvoiceApiControllerTest`、`ApprovalTargetAdapterTest`、current M定向46件 | 定向PASS。実MySQL、browser desktop/390px、zero-skippedは未達 |

操作単位のrequirements IDと既存endpoint/serviceの一次表は`operation-inventory.md` §2（9操作、R1.1〜R4.1）を正とする。

## 4. Public contract consumer inventory

### 採番・migration contract

- 正本: `customer-product-expansion-2026/README.md` §3、S07 `design.md`/`tasks.md`。
- S07実SQL: `V75__approval_workflow.sql`、`V76__approval_menu.sql`、`V77__approval_sla_step_start.sql`、`V78__approval_workflow_round_participant_version.sql`、`V79__notification_webhook_outbox.sql`。
- 後続予約consumer: `parallel-execution-plan.md`、`spec-start-conversations.md`、`spec-review-conversations.md`、S07/S09〜S17のcopyable start/review、各S09〜S17 `design.md`/`tasks.md`。
- 自動契約: `SpecDispatchConsistencyTest`。S07は`REALIZED_MIGRATIONS=[75,76,77,78,79]`を実SQLとdesign/tasks/parallel/start/review/copyableへ照合し、S09〜S17は単一予約を照合する。

### Approval API/page/UI contract

- API: `ApprovalApiController`、`ApprovalAdministrationApiController`、`ApprovalPageController`。
- Page/template: `templates/approval/inbox.html`、`requests.html`、`detail.html`、`routes.html`。
- JS/CSS/sidebar: `static/js/modules/approval.js`、`approval-routes.js`、`common.js`、`common.css`、`templates/layout/sidebar.html`。
- Domain consumers: `ApprovalEngineService`、`ApprovalViewService`、`ApprovalAdministrationService`、`ApprovalTargetAdapterRegistry`、`ApprovalNotificationService`、`NotificationOutboxService`、`NotificationOutboxDispatcher`、`NotificationOutboxScheduler`、`ApprovalSlaScheduler`。

### 5業務・9操作の既存consumer

- 見積: `QuotationApiController` / `QuotationServiceImpl` / `quotation.js` / `QuotationApprovalAdapter`。
- 契約: `ContractApiController` / `ContractServiceImpl` / `contract.js` / `contract-price-revision.js` / `ContractApprovalAdapter`。
- 請求: `InvoiceApiController` / `InvoiceServiceImpl` / `invoice.js` / `InvoiceApprovalAdapter`。
- BP支払: `InvoiceApiController` / `BpPaymentServiceImpl` / `BpPaymentApprovalAdapter`。
- 月次締め: `MonthlyClosingApiController` / `MonthlyClosingServiceImpl` / `monthly-closing.js` / `MonthlyClosingApprovalAdapter`。
- 対応する既存API、service、申請field、route source、scope、requirements IDは`operation-inventory.md` §2で行単位に固定する。

### Schema/test contract

- MySQL: V75〜V79。
- H2: `schema-approval-h2.sql`、`permission-group-seed-h2.sql`、`engineer-schema-h2.sql`、`schema-quotation-h2.sql`、`application-test.yml`。
- Static/direct regression: `MigrationScriptIntegrityTest`、`SpecDispatchConsistencyTest`、Node `--check`、M定向46件、B1定向47件。

## 5. B1/Mの完了判定と再開条件

- B1/T046とM/T047のcheckboxは、current Headでも`[ ]`を維持する。定向テストとscheduler Demo相当はPASSだが、実MySQL V79 fresh/legacy/rollback/lock、複数JVM claim/ShedLock、実Webhook endpoint、commit前例外時の実DB rollback、browser desktop/390px、CI zero-skippedが未達である。
- Mのcurrent記録は`1433 / failures 0 / errors 0 / skipped 12`（Maven本体BUILD SUCCESS、CI契約上はskip検出で未達）であり、全量PASSまたはrelease PASSへ読み替えない。
- B1/Mを閉じるには、Docker有効な実MySQL smoke、複数JVM競合、実Webhook、rollback、desktop/390px 5業務Demo、zero-skippedを同一Headで実測し、tasks.mdのDemo/release gate欄を更新する。
- したがって現時点の総合判定は`NOT REVIEWABLE`、S07は`IN PROGRESS`、S09/Wave 2は解放不可である。
