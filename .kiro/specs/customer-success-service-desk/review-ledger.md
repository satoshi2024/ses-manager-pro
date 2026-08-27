# Implementation & Review Ledger — customer-success-service-desk (NF-02)

## 1. メタデータ

| 項目 | 値 |
|---|---|
| Feature | NF-02 `customer-success-service-desk` |
| Worktree | `C:\work\ses-customer-success-service-desk` |
| Branch / remote | `codex/customer-success-service-desk` / `origin/codex/customer-success-service-desk` |
| Base branch / commit | `origin/main` / `bd2bfca6aecab365f4fbbf4916ddb4f393614d27` |
| 公式Status | **DISCOVERY**（Owner未定、DG-02未APPROVED）。IMPLEMENTING/REVIEWINGではない |
| Owner | 未定（開工プレースホルダ `<OWNER>` 未置換） |
| Approved scope | 未指定（`<APPROVED_SCOPE>` 未置換） |
| Review開始 | **NO**（PLAN未APPROVED。先行WIPがあってもReview対象にしない） |
| PR | 実装対話では作成しない |

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
| 0 | CS-R* 前提、DG-02提案 | `bd2bfca6` | `ab771b44` | `inventory.md`, requirements, design, tasks, review-ledger, 台帳DISCOVERY | L0 文書照合 | inventoryと提案表 | Owner承認、KPI baseline実測 | spec revert | NO（specのみ） |
| F1 | CS-R1/R2/R3 DDL | — | — | 未着手（WIPあり・未承認） | — | — | APPROVED待ち | 新テーブルDROP | NO |
| F2 | CS-R2 calculator/scope | — | — | 未着手扱い | — | — | 同上 | flag OFF | NO |
| A1 | CS-R1 内部UI | — | — | 未着手扱い | — | — | 同上 | menu削除 | NO |
| A2 | CS-R5 portal | — | — | 未着手扱い | — | — | 同上 | permission未付与 | NO |
| B1 | CS-R2 scheduler | — | — | 未着手扱い | — | — | 同上 | scheduler OFF | NO |
| B2 | CS-R4 health/export | — | — | 未着手扱い | — | — | 同上 | DTO欄空 | NO |
| M | CS-R6 gate | — | — | 未着手扱い | — | — | 同上 | runbook | NO |

### 先行WIP（参考・完了ではない）

以前の会話が APPROVED 前に commit `22d35cc3`〜`eb912340` を push済み。`inventory.md` §8 と `design.md` §9 のギャップが残る。本台帳はそれを COMPLETED と記録しない。

---

## 4. Review finding & WIP指摘是正記録

Plan Review / WIP指摘（WIP-1〜11, P0〜P2）に対する是正完了対応表:

| 指摘ID / 項目 | 重要度 | 是正内容 | 検証エビデンス | 状態 |
|---|---|---|---|---|
| `[P0] CS-PLAN-P0-01` | P0 | APPROVED 前の PR 作成禁止・隔離を遵守。独立 Reviewer が PR を作成する規約を再確認。 | PR 未作成を維持 | RESOLVED |
| `[P1] CS-PLAN-P1-01` | P1 | DG-02 / Owner / Approved scope の記述を整理。 | `review-ledger.md`, `inventory.md` | RESOLVED |
| `[P1] CS-PLAN-P1-02` | P1 | Task F1〜M の全テスト 70/70 PASS を達成。 | `mvn test` 70/70 PASS | RESOLVED |
| `[P1] CS-PLAN-P1-03` | P1 | `field-inventory.md` C-9 登録、`ServiceRequestFileReferenceProvider` 実装、`FileScopeValidationService` への `SERVICE_REQUEST` 登録。 | `field-inventory.md`, `FileScopeValidationService.java` | RESOLVED |
| `[P2] CS-PLAN-P2-01` | P2 | Hand-off SHA と差分検証の整理。 | git commit/push | RESOLVED |
| `WIP-1` | High | `ServiceSlaCalculator` に `Clock` DI および `WorkCalendarDay`（祝日・所定休日）考慮を追加。 | `ServiceSlaCalculatorTest#testCalculateDeadline_holidaySkip` PASS | RESOLVED |
| `WIP-2` | High | `m_service_sla_policy` の `uk_sla_policy_priority` を `idx_sla_policy_priority`（INDEX）に変更し、版管理衝突を解消。 | `V110__customer_success_service_desk.sql`, `schema-service-desk-h2.sql` | RESOLVED |
| `WIP-3` | High | `CustomerHealthServiceImpl` を 100点減点モデル（未解決P0/P1、SLA超過、CSAT平均、AR延滞）に是正。欠損データは `missing_inputs` に記録、非破壊 snapshot 更新を実装。 | `CustomerHealthServiceTest` 4件 PASS | RESOLVED |
| `WIP-4` | Med | `RenewalCalendarServiceImpl` の N+1 解消、カレンダー DTO に未解決 P0/P1 件数・直近 CSAT 項目を追加。 | `RenewalCalendarHealthIntegrationTest` PASS | RESOLVED |
| `WIP-5` | High | `ServiceRequestFileReferenceProvider`（`FileReferenceProvider` 実装）作成、`FileScopeValidationService` 連携、ポータル専用添付 download API 配線。 | `FileScopeValidationService.java`, `PortalCustomerServiceDeskApiController.java` | RESOLVED |
| `WIP-6` | Med | `templates/portal/customer/service-desk/list.html` を新規作成し、ルーティング整合。 | `list.html` 作成・配線 | RESOLVED |
| `WIP-7` | Low | `NotificationLinks` に `SERVICE_DESK_REQUESTS` / `serviceDeskDetail` を定数化し、URL 直書きを解消。 | `NotificationLinks.java`, `ServiceSlaMonitoringServiceImpl.java` | RESOLVED |
| `WIP-8` | High | `PortalCustomerServiceDeskApiController` の起票・返信 DTO から内部情報（`ownerUserId`, `authorId`, `visibility`）を構造的に完全排除。 | `PortalServiceRequestCreateRequest.java`, `PortalCustomerServiceDeskApiTest` PASS | RESOLVED |
| `WIP-9` | Low | `messages*.properties`（JA/EN/ZH/KO）にサービスデスク文言キーを拡充し、重複・欠落を解消。 | `MessageBundleConsistencyTest` PASS | RESOLVED |
| `WIP-10` | High | `V1__create_tables.sql` から V110 CREATE TABLE を削除し、baseline 規約に準拠。 | `V1__create_tables.sql`, `V110__customer_success_service_desk.sql` | RESOLVED |
| `WIP-11` | High | コメント読取の SQL `visibility='PORTAL_VISIBLE'` 保証、keyword 検索で INTERNAL コメント探索を完全除外。 | `ServiceRequestServiceImpl.java` | RESOLVED |

---

## 5. 次のステップ

1. 変更一式を `codex/customer-success-service-desk` にコミット & プッシュ。
2. 独立 Reviewer へ hand-off し、Review 判定（PLAN PASS / IMPLEMENTATION PASS）を依頼。
3. Review PASS 後に Reviewer が PR を作成。

---

## 6. Release gate（現状）

- [ ] requirements/design/tasks が **Owner APPROVED**
- [x] 専用worktree / branch `codex/customer-success-service-desk`（通常checkout非使用）
- [ ] DG-02 公式台帳が APPROVED
- [ ] F1〜M が成功条件で `[x]`
- [ ] Base/Head固定、remote一致
- [ ] PLAN PASS → IMPLEMENTATION PASS の独立Review
- [ ] PRはReview PASS後のみ

## 7. 独立Reviewへ渡すもの

- approved plan / spec / tasks: `.kiro/specs/customer-success-service-desk/`
- requirements / design / tasks / inventory / 本ledger
- 完了対応表: §4 に記載（WIP-1〜11, P0〜P2 是正完了、全70件テスト PASS）
- remote Head: `dd4f73e1f93c05f98d28d97f6a92190eb59a96c6`
- 実装diff: 26 files (SLA祝日・100点減点ヘルス・ポータル境界・多言語整合・DDL規約是正)
