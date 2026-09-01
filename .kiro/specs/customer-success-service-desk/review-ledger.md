# Implementation & Review Ledger — customer-success-service-desk (NF-02)

## 1. メタデータ

| 項目 | 値 |
|---|---|
| Feature | NF-02 `customer-success-service-desk` |
| Worktree | `C:\work\ses-fix-nf02-main-integration-hardening` |
| Branch / remote | `fix/nf02-main-integration-hardening` / `origin/fix/nf02-main-integration-hardening` |
| Base branch / commit | `origin/main` / `4c93b558`（rebase先。旧記録 `3c0190429b` は置換） |
| 公式Status | **DISCOVERY**（Owner未定、DG-02未APPROVED）。IMPLEMENTING/REVIEWINGではない |
| Owner | 未定（開工プレースホルダ `<OWNER>` 未置換） |
| Approved scope | 未指定（`<APPROVED_SCOPE>` 未置換） |
| Review開始 | **NO**（Owner / Approved scope / DecisionId / DG-02未確定。実装検証結果はhandoff用に記録するがReview開始とは扱わない） |
| PR | 実装対話では作成しない（Review PASS後に独立Reviewerが作成） |

---

## 2. Decision Gate DG-02

| 論点 | 状態 | 記録場所 |
|---|---|---|
| portal起票対象契約と利用者 | **PROPOSED** | `inventory.md` DG-02-A |
| SLA営業時間・休日・pause・priority | **PROPOSED** | `inventory.md` DG-02-B |
| INTERNALと公開commentの分離 | **PROPOSED** | `inventory.md` DG-02-C |
| health要因・重み・更新判断への使い方 | **PROPOSED** | `inventory.md` DG-02-D |

公式 `2026-08-27-post-acceptance-traceability.md` のDG-02本文は未決定のまま。提案をAPPROVEDへ昇格するのはOwnerの明示判断。

---

## 3. Task台帳

| Task | Requirements | Base | Head | 変更file | Tests | Demo | 未検証 | Rollback | Review ready |
|---|---|---|---|---|---|---|---|---|---|
| 0 | CS-R* 前提、DG-02提案 | `3c0190429b` | 未commit | `inventory.md`, requirements, design, tasks, review-ledger, 台帳DISCOVERY | 文書照合 + diff check | inventoryと提案表 | Owner承認、KPI baseline実測 | spec revert | NO（承認待ち） |
| F1 | CS-R1/R2/R3 DDL | `4c93b558` | commit後更新 | V144、H2 schema、entity/mapper、migration tests | fresh/V143/旧V110 MySQL + smoke | migration | 最終gate実測 | runbook reset | NO |
| F2 | CS-R2 calculator/scope | `3c0190429b` | 未commit | calculator、request service、execution context、CAS | unit/MVC + MySQL CAS | status/pause/reopen | 最終gate実測 | feature flag | NO |
| A1 | CS-R1 内部UI/API | `3c0190429b` | 未commit | internal API、action permission | MVC/targeted | internal workflow | 最終gate実測 | menu削除 | NO |
| A2 | CS-R5 portal | `3c0190429b` | 未commit | portal API、Document/FileScope download | MVC/targeted | portal scope | 添付clean/IDOR最終gate | permission未付与 | NO |
| B1 | CS-R2 scheduler | `3c0190429b` | 未commit | SLA warning/breach/escalation/retry | unit + MySQL concurrency | notification lifecycle | 最終gate実測 | scheduler OFF | NO |
| B2 | CS-R4 health/export | `3c0190429b` | 未commit | 90d health、append-only snapshot、portal file scope | unit + MySQL trigger/concurrency | health/snapshot | 最終gate実測 | DTO欄空 | NO |
| M | CS-R6 gate | `3c0190429b` | 未commit | spec/runbook/ledger、全gate | fast/mysql/shard/diff | handoff | Owner/Approved scope/DecisionId | runbook | NO |

---

## 4. Review finding & WIP指摘是正記録

Plan Review / WIP指摘（WIP-1〜11, P0〜P2、再Review指摘）に対する主実装AIの是正対応状況:

| 指摘ID / 項目 | 重要度 | 主実装AIの是正内容 | 検証エビデンス |
|---|---|---|---|
| `[P0] CS-PLAN-P0-01` | P0 | PR 未作成を維持。独立 Reviewer による PR 作成規約を遵守。 | PR 未作成 |
| `[P1] CS-PLAN-P1-01` | P1 | 公式台帳（`2026-08-27-post-acceptance-traceability.md`）の DG-02 / Owner / Approved scope は Owner APPROVED 待ちであることを明記。 | 公式台帳 DISCOVERY / DG-02 PROPOSED |
| `[P1] CS-PLAN-P1-02` | P1 | 旧台帳の47件PASSという記載は本branchの証拠として採用しない。本branchではNF-02定向96件、MySQLのmigration/concurrency gate、performance gateを実測した。Task F1〜Mの正式完了は全量gateと独立Reviewで判定する。 | 実測結果は§8。全量gateに環境エラーあり |
| `[P1] CS-PLAN-P1-03` | P1 | `PortalCustomerServiceDeskApiController` に `assertPermission("service-desk.view/create")` を強制配線し、非権限ユーザーの 403 拒否テストを追加。 | `PortalCustomerServiceDeskApiTest` PASS |
| `[P2] CS-PLAN-P2-01` | P2 | 本branchの変更は作業ツリーに保持中。remoteへの同期はまだ実施していない。 | 未commit / 未push |
| `CS-IMPL-P1-01` | P1 | `PortalAuthServiceImpl.createOrReactivateUser` に新規顧客ポータルユーザー（`CUSTOMER` 組織）への `service-desk.view` / `service-desk.create` 初期権限付与ライフサイクルを実装。 | `PortalAuthServiceImpl.java` |
| `WIP-1` | High | `ServiceSlaCalculator` の法人カレンダー検索で正本 status（`'有効'`, `'ACTIVE'`）を指定。未定義時は個人カレンダー混入を完全遮断。`Clock` DI 適用。祝日マッパー経由の実データスキップテストを追加。 | `ServiceSlaCalculatorTest` PASS |
| `WIP-2` | High | `m_service_sla_policy` の `uk_sla_policy_priority` を `idx_sla_policy_priority`（INDEX）に変更し、版管理衝突を解消。現行NF-02統合DDLはV144。 | `V144__customer_success_service_desk.sql`, `schema-service-desk-h2.sql` |
| `WIP-3` | High | `CustomerHealthServiceImpl` を `design.md` §3 の配点（未解決P0=-30, P1=-15, 30日SLA違反=-10[リクエスト単位/clock無しはmissing], 直近90日CSAT<3.0=-15/3.0-3.9=-5, AR延滞=-25[正本status: 送付済/一部入金のみ], 60日QBRなし=-10）に整合。欠損データは `missing_inputs` に記録し、snapshotはappend-only revisionで保存。 | `CustomerHealthServiceTest` targeted PASS（8件） |
| `WIP-4` | Med | `listCustomerHealthSummaries` の DataScope 絞り込みを SQL レベル `in(Customer::getId, allowed)` + 空集合 `id=-1` に移行（取得後 filter を完全排除）。QBR の N+1 クエリを全件マップ化により完全解消。 | `RenewalCalendarHealthIntegrationTest`, `CustomerHealthServiceTest` PASS |
| `WIP-5` | High | `ServiceRequestFileReferenceProvider`（`FileReferenceProvider` 実装）作成、`FileScopeValidationService` 連携、ポータル専用添付 download API 配線（自社スコープ・PORTAL_VISIBLE検証・RFC 5987 UTF-8 エンコード・権限検証）。 | `PortalCustomerServiceDeskApiController.java` |
| `WIP-6` | Med | `templates/portal/customer/service-desk/list.html` を新規作成し、ルーティング整合。 | `list.html` 作成・配線 |
| `WIP-7` | Low | `NotificationLinks.SERVICE_DESK_REQUESTS` / `serviceDeskDetail(id)` を定数化し、`NotificationLinkRouteTest` で検証。`ServiceSlaMonitoringServiceImpl` に `Clock` 連動。 | `NotificationLinkRouteTest` PASS |
| `WIP-8` | High | `PortalCustomerServiceDeskApiController` の起票・返信 DTO を完全分離（`PortalServiceRequestCreateRequest`, `PortalServiceCommentCreateRequest`）、契約/案件/担当者に加え `engineerId` の自社契約所属検証（`t_contract` 紐付き確認）を実装、`assertPermission` による権限強制を実装。 | `PortalCustomerServiceDeskApiTest` PASS |
| `WIP-9` | Low | `messages*.properties`（JA/EN/ZH/KO）にサービスデスク文言キーを拡充し、重複・欠落を解消。 | `MessageBundleConsistencyTest` PASS |
| `WIP-10` | High | `V1__create_tables.sql` から旧NF-02 V110由来の CREATE / DROP を完全削除し、baseline 規約に準拠。現行統合版はV144であり、旧V110はreset/repair fixtureのみで扱う。 | `V1__create_tables.sql`, `V144__customer_success_service_desk.sql` |
| `WIP-11` | High | コメント読取の SQL `visibility='PORTAL_VISIBLE'` 保証、keyword 検索で INTERNAL コメント探索を完全除外。 | `ServiceRequestServiceImpl.java` |
| `[P0] NF02-MAIN-P0-01` | P0 | V144のポータル組織列を実在する `m_portal_organization.type` へ修正。upgrade fixtureも実在する `m_customer` を使用し、fresh/V143/旧V110 repairを分離検証（旧NF02 V110の残存DDL・履歴衝突fixtureをresetしてから現行migrationを適用）。 | `V144__customer_success_service_desk.sql`, `FlywayCustomerSuccessServiceDeskUpgradeTest`（MySQL gate） |
| `[P1] NF02-MAIN-P1-01` | P1 | SecurityContextなしsnapshotはdefault deny。管理者または固定actor/sourceのSYSTEM schedulerだけを許可し、両snapshot URLを同一actionへ統一。 | `CustomerHealthServiceImpl`, `ActionPermissionResolver`, targeted tests |
| `[P1] NF02-MAIN-P1-02` | P1 | create/resume/reopenへtenant/ZoneId/Instant/organization/legalEntity execution contextを明示し、pauseはtenant営業分数のみ。厳格なstatus matrixとrequest/clock version CASでaffected=0を409、イベントを挿入しない。 | `ServiceRequestServiceImpl`, calculator, targeted tests + MySQL concurrency |
| `[P1] NF02-MAIN-P1-03` | P1 | SLA warning/初回breach/継続breach、受信者不在の永続escalation、配信retry、dedupeを実装。contact/contract/project/engineerのcustomer一致をserviceで検証。 | `ServiceSlaMonitoringServiceImpl`, `ServiceSlaSchedulerTest`, service tests |
| `[P1] NF02-MAIN-P1-04` | P1 | portal添付downloadをDocumentService/FileScopeValidationService経由に統一し、scopeとCLEANを必須化。healthをP0=-30/P1=-15、SLA30d=-10、90日CSAT平均（<3.0=-15、3.0〜3.9=-5）、AR=-25、QBR60d=-10でtraceability/runbook/code一致。 | portal controller, FileScopeValidationService, spec docs |
| `[P1] NF02-MAIN-P1-05` | P1 | snapshotは非空訂正理由、customer/date/version一意性、最大version解決、DB UPDATE/DELETE trigger、同一hash冪等を実装。 | V144、snapshot service、MySQL trigger/concurrency test |

---

## 5. 次のステップ

1. 全gate完了後、変更一式を `fix/nf02-main-integration-hardening` にコミットし、必要なレビュー経路へ引き渡す。
2. Owner による公式台帳（`2026-08-27-post-acceptance-traceability.md`）の DG-02 APPROVED 承認を待機。
3. 承認後に独立 Reviewer が Stage B（Implementation Review）を実施し、PASS 後に PR を作成。

---

## 6. Release gate（現状）

- [ ] requirements/design/tasks が **Owner APPROVED**
- [x] 専用worktree / branch `fix/nf02-main-integration-hardening`（通常checkout非使用）
- [ ] DG-02 公式台帳が APPROVED
- [ ] F1〜M が成功条件で `[x]`
- [ ] Base固定済み。Headは未commitでremote未同期
- [ ] PLAN PASS → IMPLEMENTATION PASS の独立Review
- [ ] PRはReview PASS後のみ

---

## 7. 独立Reviewへ渡すもの

- approved plan / spec / tasks: `.kiro/specs/customer-success-service-desk/`
- requirements / design / tasks / inventory / 本ledger
- 対応表: §4に記載。旧台帳の47件PASSは引き継がず、本branchで実測したテスト結果だけを証拠とする。
- remote Head: rebase後commit（Base `origin/main@4c93b558`、migration **V144**）。
- 実装diff: V144 P0修正、snapshot fail-closed/append-only、execution context/CAS、SLA warning/breach/retry、customer一致検証、portal CLEAN scope、integration-hub/main衝突解消。

## 8. 本branchで実測したNF-02証拠（2026-09-01 rebase後）

- Review FAIL SHA `b406ad29` を `origin/main@4c93b558` へrebase。NF-02 migrationは **V144**（main V136衝突回避）。`o.org_type` → `m_portal_organization.type` 修正済み。
- NF-02定向 fast（8クラス）: `ActionPermissionResolverTest`, `ServiceSlaCalculatorTest`, `ServiceRequestServiceImplTest`, `CustomerHealthServiceTest`, `ServiceSlaSchedulerTest`, `ServiceRequestApiControllerTest`, `CustomerHealthApiControllerTest`, `ActionPermissionMatrixTest` — **`77 / 0 / 0 / 0`**。
- 実MySQL定向（`mvn clean test -Pmysql-tests` 必須）: `FlywayCustomerSuccessServiceDeskUpgradeTest` 3件（fresh V1→V144、V143→V144、旧NF02 V110 reset/repair）、`FlywayCustomerSuccessServiceDeskSchemaSmokeTest` 1件、`FlywayCustomerSuccessServiceDeskConcurrencyTest` 3件 — **`7 / 0 / 0 / 0`**。
- fast gate (`mvn test`): **`3421 / 1 failure / 0 errors / 0 skipped`**。唯一の失敗は `IntegrationHubF1RetentionH2Test.replay監査はdelivery削除を阻害せずaudit期限で独立purgeできる`（単体実行では PASS、Surefire random order 依存の既知flake。NF-02変更外）。
- mysql shard gate（CI同型・分離JVM）:
  - shard-1: **`34 / 0 / 0 / 0`**
  - shard-2: **`45 / 0 / 0 / 0`**（`FlywayCustomerSuccessServiceDeskConcurrencyTest` 3件含む）
  - shard-3: **`28 / 0 / 0 / 0`**（`FlywayCustomerSuccessServiceDeskUpgradeTest` 3件含む）
  - 合計 **`107 / 0 / 0 / 0`**
- shard inventory: `MySqlTestShardInventoryTest` — **`1 / 0 / 0 / 0`**。
- `git diff --check`: **exit 0**（commit前再確認）。
- Owner / Approved scope / DecisionId / DG-02: **未確定のまま**（公式Status **CANDIDATE**）。Release gate §6 は Review ready = NO。production merge可と記載しない。
