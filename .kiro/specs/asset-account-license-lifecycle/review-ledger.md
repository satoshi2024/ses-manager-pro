# Review Ledger — `asset-account-license-lifecycle` (NF-09)

## 1. 実装台帳 (Task Execution Ledger)

| Task | Requirements | Base | Head | 変更file | Tests | Demo | 未検証 | Rollback | Review ready |
|---|---|---|---|---|---|---|---|---|---|
| **0.1** | AS-R1〜R4, DG-09 | `origin/main` | — | `.kiro/specs/asset-account-license-lifecycle/*` | spec links & lint | Discovery & DG-09 Review | なし | spec削除 | YES (Spec Done) |
| **F1.1** | AS-R1, AS-R2, CR-03 | — | — | DDL, Migration, H2 schema | DDL smoke tests | DDL migration demo | — | DDL rollback | NO |
| **F1.2** | AS-R1, AS-R2 | — | — | Entities, Mappers | Entity CRUD tests | Mapper demo | — | Code revert | NO |
| **F2.1** | AS-R1, CR-02 | — | — | AssetService, AssetEventService | Asset status & CAS tests | Asset status change demo | — | Code revert | NO |
| **F2.2** | AS-R1, CR-02 | — | — | AssetAssignmentService | Concurrency overlap tests | Assignment overlap demo | — | Code revert | NO |
| **F2.3** | AS-R2, CR-04 | — | — | ExternalAccountService, LicenseService | Secret scan, License CAS tests | License limit demo | — | Code revert | NO |
| **A1.1** | AS-R1, CR-01, CR-05 | — | — | Controller, HTML, JS (Asset) | Controller tests, CSV tests | Asset UI demo | — | Code revert | NO |
| **A1.2** | AS-R3, CR-05 | — | — | Controller, HTML, JS (Inventory) | Inventory tests | Inventory UI demo | — | Code revert | NO |
| **A1.3** | AS-R2, CR-05 | — | — | Controller, HTML, JS (Account/License) | Account controller tests | Account UI demo | — | Code revert | NO |
| **A2.1** | AS-R4, CR-05 | — | — | MyAssetController, HTML, JS | MyAsset scope tests | Portal mobile demo | — | Code revert | NO |
| **A2.2** | AS-R4 | — | — | NotificationService | Notification dedupe tests | Notification demo | — | Code revert | NO |
| **B1.1** | AS-R1, AS-R4 | — | — | AssetScheduler | Scheduler batch tests | Batch log demo | — | Code revert | NO |
| **B1.2** | AS-R3 | — | — | Lost asset incident service | Lost incident tests | Lost incident demo | — | Code revert | NO |
| **B2.1** | AS-R3 (NF-01 link) | — | — | AssetLifecycleIntegrationService | Resignation blocker tests | Resignation block demo | — | Code revert | NO |
| **B2.2** | AS-R2 | — | — | External Revoke Adapter | Revoke timeout tests | Timeout handle demo | — | Code revert | NO |
| **M.1** | CR-01〜CR-06 | — | — | Test suites | Fast/MySQL/Perf test suites | All test pass logs | — | — | NO |
| **M.2** | AS-R1〜R5 | — | — | Runbook, scripts | Reconciliation tests | Runbook review | — | — | NO |

---

## 2. DG-09 決定ログ (Decision Gate 09 Log)

- **決定日**: 2026-08-28 (Discovery / Spec Phase)
- **資産種別**: PC, MONITOR, SMARTPHONE, TABLET, SECURITY_KEY, OTHER の6区分。
- **所有法人**: `m_company.id` 参照。NULLは全社共通。
- **棚卸し頻度**: 半期に1回（年2回: 3月末・9月末基準日）定期＋随時臨時。
- **外部アカウント連携方針**: 秘密情報を一切保持せず、状態（ACTIVE, SUSPENDED, REVOKED, EXCEPTION_HOLD）と参照のみ管理。外部失効APIのタイムアウト時は `TIMEOUT` として残し、自動失効完了扱いを禁止。
- **NF-01 退社ゲート連携**: 未返却資産および未失効アカウントを blocker として退社ケース完了を阻止。例外時は `ApprovalEngineService` による二者承認（理由・是正期限・リスク所有者必須）。

---

## 3. レビュー指摘事項台帳 (Review Findings)

| Finding ID | Severity | Requirement | Evidence `file:line` | Reproduction | Impact | Minimum fix | Regression | Status | Fix commit |
|---|---|---|---|---|---|---|---|---|---|
| *(現在指摘なし)* | — | — | — | — | — | — | — | — | — |

---

## 4. Release Gate チェック

- [x] requirements/design/tasks/inventory が作成・整合性確認済み。
- [ ] Approved scope / Owner / Base commit が確定し、`2026-08-27-post-acceptance-traceability.md` で `APPROVED` に更新されている。
- [ ] 実装が通常checkoutと分離した専用Codex worktree、`codex/asset-account-license-lifecycle` branchで行われている。
- [ ] 完了Taskのcommitがremote feature branchへpush済みで、local/remote Headが一致する。
- [ ] Reviewが専用worktreeで行われ、PLAN PASS / IMPLEMENTATION PASSが記録されている。
- [ ] PR作成・マージ・ブランチ削除が規約に則り管理されている。
