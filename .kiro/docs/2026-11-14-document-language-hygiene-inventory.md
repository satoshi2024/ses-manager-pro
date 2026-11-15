# G6 文書用語整備・出典一覧（2026-11-14）

## 1. 調査範囲と判断方法

- 基準SHA: `b7da82993b8d534fbcf56e59806cc5476380321f`。行番号はこの基準の原文を指す。本文整備で行の増減は行っていない。
- Git管理下の `.kiro/`、`docs/`、`ops/` の文書（md / markdown / txt / rst / adoc / csv）と、ルートの README*.md / AGENTS.md / CLAUDE.md、計617ファイルを調査した。画像・圧縮ファイル・実装ソース・実行ログは語彙検索に混ぜていない。
- 指定語彙に加え、文脈上の不自然な専門表現を検索した。漢字はCP932の例外fallback付きencoderで表現可否とroundtripを確認した。字形の一括変換ではなく、周辺の業務・計算・認可の意味に合わせた。
- 原語はUnicodeエスケープで記録する。原文は基準SHAの該当ファイル・行で照合できる。語彙と漢字の検出は重複し得るため、行数は重複排除した値である。
- 617文書中の検出は184ファイル、721行。変更は既存93ファイルと本附表1ファイル。CLAUDE.md / AGENTS.mdは許可された権限説明の4行ずつだけを訂正し、freeeを含む後続節は原文と同一。
- マニフェストの所在ディレクトリだけでなく、文書名が別のマニフェストから参照されている場合も保全した。参照先文書は51ファイル、参照の出典は52箇所（9節）。開始時の原文との一致を確認したもので、過去のmanifest hashが現在のHEADに一致していたと主張するものではない。

| 分類 | 検出ファイル数 | 検出行数 | 方針 |
|---|---:|---:|---|
| 修正対象 | 94 | 414 | 説明文を日本語化。引用・部分一致は5節へ記録 |
| 対象外 | 26 | 100 | 原文を変更せず保全 |
| 証跡保全 | 50 | 156 | 原文を変更せず保全 |
| 歴史記録保全 | 14 | 51 | 原文を変更せず保全 |

## 2. 変更ファイル一覧

既存ファイルはすべて文書。Java・SQL・JS・テスト・migrationは変更していない。

| ファイル | 変更行数 | 内容 |
|---|---:|---|
| `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md` | 1 | 説明文の用語整備 |
| `.kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md` | 1 | 説明文の用語整備 |
| `.kiro/roadmap/2026-08-27-post-acceptance-start-conversations.md` | 2 | 説明文の用語整備 |
| `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/ai-feedback-learning/review-ledger.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/asset-account-license-lifecycle/inventory.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md` | 20 | 説明文の用語整備 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md` | 51 | 説明文の用語整備 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md` | 28 | 説明文の用語整備 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md` | 107 | 説明文の用語整備 |
| `.kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md` | 9 | 説明文の用語整備 |
| `.kiro/specs/bp-company-master-procurement-compliance/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/bp-company-master-procurement-compliance/review-ledger.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/business-logic-integrity-hardening/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/cashflow-forecast/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/cashflow-forecast/requirements.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/cashflow-forecast/tasks.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` | 5 | 説明文の用語整備 |
| `.kiro/specs/contract-document-esign/review-conversation.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/contract-document-esign/start-conversation.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/contract-price-history/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/crm-contact-opportunity/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md` | 16 | 説明文の用語整備 |
| `.kiro/specs/crm-contact-opportunity/tasks.md` | 4 | 説明文の用語整備 |
| `.kiro/specs/customer-feature-proposals/README.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/conversation-baseline-v2.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R04__legal-document-ledger-archive__review.txt` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R05__productivity-search-saved-view__review.txt` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R06__bp-company-master-procurement-compliance__review.txt` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R08__crm-contact-opportunity__review.txt` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S03__enterprise-identity-security__start.txt` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S04__legal-document-ledger-archive__start.txt` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S05__productivity-search-saved-view__start.txt` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S06__bp-company-master-procurement-compliance__start.txt` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S08__crm-contact-opportunity__start.txt` | 3 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/dispatch-guide.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/execution-review-handbook.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/platform-invariants.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/shared-standards.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/task-start-conversations.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md` | 8 | 説明文の用語整備 |
| `.kiro/specs/customer-success-service-desk/requirements.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/customer-success-service-desk/review-ledger.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/customer-success-service-desk/tasks.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/dashboard-and-contract-list/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/dashboard-and-contract-list/requirements.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/dashboard-improvements/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/reviewer-verification-decision-delta-r23-p1-01.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/engineer-availability-visualization/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/engineer-sales-commission/design.md` | 4 | 説明文の用語整備 |
| `.kiro/specs/engineer-sales-commission/requirements.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/engineer-sales-commission/tasks.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/enterprise-identity-security/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md` | 9 | 説明文の用語整備 |
| `.kiro/specs/enterprise-identity-security/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/half-finished-production-readiness/README.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/half-finished-production-readiness/review-conversations.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/half-finished-production-readiness/start-conversations.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/jp-pint-digital-invoice/review-ledger.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/legal-document-ledger-archive/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/legal-document-ledger-archive/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/lifecycle-status-consistency/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/money-flow-consistency/design.md` | 5 | 説明文の用語整備 |
| `.kiro/specs/money-flow-consistency/requirements.md` | 9 | 説明文の用語整備 |
| `.kiro/specs/money-flow-consistency/tasks.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/multi-tier-bp-management/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md` | 7 | 説明文の用語整備 |
| `.kiro/specs/organization-management-accounting/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/organization-management-accounting/review-ledger.md` | 6 | 説明文の用語整備 |
| `.kiro/specs/productivity-search-saved-view/design.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/productivity-search-saved-view/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/README.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/recruiting-pipeline/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/referential-integrity/requirements.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/revenue-forecast/requirements.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/scheduled-management-reporting/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/scheduled-management-reporting/inventory.md` | 3 | 説明文の用語整備 |
| `.kiro/specs/skill-sheet-generation/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md` | 6 | 説明文の用語整備 |
| `.kiro/specs/ui-scale-regression-hardening-200/defect-catalog.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-conversation.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md` | 5 | 説明文の用語整備 |
| `.kiro/specs/ui-scale-regression-hardening-200/start-conversation.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/ui-scale-regression-hardening-200/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/utilization-forecast/design.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/utilization-forecast/requirements.md` | 2 | 説明文の用語整備 |
| `.kiro/specs/utilization-forecast/tasks.md` | 1 | 説明文の用語整備 |
| `.kiro/specs/webhook-notifications/design.md` | 1 | 説明文の用語整備 |
| `AGENTS.md` | 4 | D-P3-1/2の権限説明のみ |
| `CLAUDE.md` | 4 | D-P3-1/2の権限説明のみ |
| `docs/i18n/plan.md` | 1 | 説明文の用語整備 |
| `.kiro/docs/2026-11-14-document-language-hygiene-inventory.md` | 新規 | 原文の全検出箇所・保留理由・検証結果 |

## 3. 主な用語方針

| 原語（Unicode） | 日本語の表現 |
|---|---|
| `\u5b9a\u5411` | 対象限定（テストの場合は対象限定テスト） |
| `\u4e3b\u63a7` | 統括 |
| `\u9580\u7981` | ゲート |
| `\u843d\u76e4` | 永続化 |
| `\u4e0a\u7dda` | 本番投入 |
| `\u9996\u671f` | 第1期 |
| `\u6f14\u7df4` | リハーサル |
| `\u53e3\u5f84` | 集計基準・金額算定基準・判定基準など文脈別 |
| `\u5206\u5c64` | 階層別 |
| `\u8ca0\u5411\u7cfb` | 異常系 |
| `\u8d1f\u5411\u7cfb` | 異常系 |
| `\u6301\u9396` | ロック保持下・ロック保持中 |
| `\u7d50\u6848` | 完了 |
| `\u56de\u586b` | 補完・バックフィル |
| `\u8f09\u4f53` | 代表行（carrier識別子は維持） |
| `\u91cd\u8a66` | 再試行 |
| `\u53ef\u898b\u6bcd\u96c6\u56e3` | 閲覧可能な母集団 |

## 4. 修正対象の全出典

| 原文のファイル:行 | 語彙検出（Unicode・出現数） | CP932非対応漢字（Unicode） | 処理 |
|---|---|---|---|
| `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md:98` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md:290` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/roadmap/2026-08-27-post-acceptance-start-conversations.md:41` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/roadmap/2026-08-27-post-acceptance-start-conversations.md:143` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md:15` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md:30` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/README.md:64` | `\u53e3\u5f84 ×3` | — | 説明文を修正 |
| `.kiro/specs/README.md:70` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/README.md:77` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/ai-feedback-learning/review-ledger.md:67` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/ai-feedback-learning/review-ledger.md:104` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ai-feedback-learning/review-ledger.md:191` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/inventory.md:71` | `\u6f14\u7df4 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:150` | `\u6838\u5bfe ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:156` | `\u7d50\u6848 ×1`, `\u6642\u5e8f ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:171` | `\u6301\u9396 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:191` | `\u6301\u9396 ×1`, `\u7d50\u6848 ×1`, `\u5fa9\u6838 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:196` | `\u5fa9\u6838 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:207` | `\u6301\u9396 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:239` | `\u6301\u9396 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:257` | `\u6301\u9396 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:259` | `\u6301\u9396 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:301` | `\u8ca0\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:307` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:308` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:309` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:310` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:311` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:312` | `\u8d1f\u5411 ×1`, `\u6301\u9396 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:313` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:314` | `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:320` | `\u8d1f\u5411 ×1`, `\u6301\u9396 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/lifecycle-revocation-bridge-plan.md:321` | `\u6838\u5bfe ×1`, `\u8d1f\u5411 ×1` | `\u8d1f` | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:14` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:35` | `\u6f14\u7df4 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:44` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:45` | `\u6f14\u7df4 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:102` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:312` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:314` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:316` | `\u53e3\u5f84 ×1`, `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:319` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:331` | `\u7d50\u6848 ×1`, `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:340` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:349` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:350` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:354` | `\u7d50\u6848 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:356` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:359` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:363` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:373` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:374` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:375` | `\u7d50\u6848 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:377` | `\u7d50\u6848 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:389` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:412` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:415` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:416` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:417` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:426` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:427` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:428` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:434` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:440` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:443` | `\u7d50\u6848 ×2`, `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:445` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:463` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:481` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:513` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:519` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:533` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:546` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:557` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:559` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:575` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:576` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:584` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:587` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:649` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:701` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:703` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:705` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:720` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-production-runbook-draft.md:724` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:1` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:30` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:42` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:67` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:73` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:87` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:89` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:90` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:95` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:97` | `\u56de\u586b ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:100` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:101` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:103` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:108` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:110` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:111` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:159` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:161` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:164` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:178` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:194` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:197` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:203` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:211` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:212` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:213` | `\u8ca0\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:214` | `\u8ca0\u5411 ×1`, `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md:215` | `\u8ca0\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:10` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:25` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:26` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:28` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:32` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:34` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:36` | `\u91cd\u8a66 ×2`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:59` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:60` | `\u8f2a\u6b21 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:61` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:67` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:68` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:69` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:71` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:72` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:82` | `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:177` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:179` | `\u8f2a\u6b21 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:180` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:187` | `\u8f2a\u6b21 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:188` | `\u8f2a\u6b21 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:189` | `\u8f2a\u6b21 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:192` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:227` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:229` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:252` | `\u91cd\u8a66 ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:267` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:275` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:277` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:286` | `\u91cd\u8a66 ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:289` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:291` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:299` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:306` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:309` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:311` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:312` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:318` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:320` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:323` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:324` | `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:335` | `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:342` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:343` | `\u8f09\u4f53 ×1`, `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:344` | `\u8f09\u4f53 ×3`, `\u8f2a\u6b21 ×1` | — | 説明文を修正。残存は5節 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:349` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:351` | `\u91cd\u8a66 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:352` | `\u7d50\u6848 ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:368` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:370` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:374` | `\u91cd\u8a66 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:378` | `\u8f09\u4f53 ×1` | — | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:381` | `\u8f09\u4f53 ×1` | — | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:384` | `\u8f09\u4f53 ×2` | — | 説明文を修正。残存は5節 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:387` | `\u8f09\u4f53 ×1` | — | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:389` | `\u8f09\u4f53 ×1` | — | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:393` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:407` | `\u91cd\u8a66 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:416` | `\u56de\u586b ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:421` | `\u56de\u586b ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:428` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:462` | `\u7d50\u6848 ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:463` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:477` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:478` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:504` | `\u7d50\u6848 ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:505` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:513` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:517` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:518` | `\u91cd\u8a66 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:525` | `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:526` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:547` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:549` | `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:564` | `\u56de\u586b ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:565` | `\u56de\u586b ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:566` | `\u56de\u586b ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:567` | `\u56de\u586b ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:568` | `\u540c\u7968 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:569` | `\u540c\u7968 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:570` | `\u7d50\u6848 ×1`, `\u540c\u7968 ×1`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:571` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:573` | `\u7d50\u6848 ×2`, `\u65e0\u9700 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:586` | `\u7d50\u6848 ×1`, `\u6574\u7d44 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:589` | `\u8f2a\u6b21 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:590` | `\u8f2a\u6b21 ×3` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:595` | `\u7d50\u6848 ×1`, `\u91cd\u8a66 ×2`, `\u53c2\u4e0e ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:598` | `\u540c\u7968 ×1`, `\u7cbe\u78ba ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:599` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:600` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:601` | `\u540c\u7968 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:602` | `\u7d50\u6848 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:603` | `\u7d50\u6848 ×1`, `\u91cd\u8a66 ×2`, `\u65b0\u7968 ×2`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:604` | `\u7d50\u6848 ×2`, `\u91cd\u8a66 ×3`, `\u7cbe\u78ba ×1`, `\u8f09\u4f53 ×4` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:605` | `\u8f09\u4f53 ×1`, `\u8f2a\u6b21 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:606` | `\u91cd\u8a66 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:607` | `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:608` | `\u7d50\u6848 ×1`, `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:610` | `\u91cd\u8a66 ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:611` | `\u91cd\u8a66 ×2`, `\u8f09\u4f53 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:612` | `\u91cd\u8a66 ×1`, `\u8f09\u4f53 ×2` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:653` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:660` | `\u6f14\u7df4 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md:130` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:66` | `\u5b9a\u5411 ×2` | — | 過去の実行引継ぎ記録の引用を保持 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:70` | `\u5b9a\u5411 ×1` | — | 過去の実行引継ぎ記録の引用を保持 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:92` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:170` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:171` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:172` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:174` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:204` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:217` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:355` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:687` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/bp-company-master-procurement-compliance/design.md:3` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/bp-company-master-procurement-compliance/design.md:89` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/bp-company-master-procurement-compliance/review-ledger.md:39` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/bp-company-master-procurement-compliance/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/business-logic-integrity-hardening/tasks.md:68` | `\u5168\u4f53\u9a8c\u53ce ×1` | `\u9a8c` | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/design.md:12` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/design.md:26` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/requirements.md:21` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/tasks.md:11` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/tasks.md:12` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/cashflow-forecast/tasks.md:30` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md:4` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md:71` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md:85` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md:103` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md:126` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/contract-document-esign/review-conversation.md:119` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/contract-document-esign/start-conversation.md:61` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/contract-price-history/tasks.md:59` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/design.md:3` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/design.md:77` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:7` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:8` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:9` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:10` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:11` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:35` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:163` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:194` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:350` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:358` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:361` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:362` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:374` | `\u5b9a\u5411 ×1`, `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:389` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:409` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/review-ledger.md:424` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/tasks.md:17` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/tasks.md:73` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/crm-contact-opportunity/tasks.md:74` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-feature-proposals/README.md:58` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-feature-proposals/README.md:116` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-feature-proposals/README.md:118` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/conversation-baseline-v2.md:16` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R04__legal-document-ledger-archive__review.txt:54` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R05__productivity-search-saved-view__review.txt:53` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R06__bp-company-master-procurement-compliance__review.txt:54` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R08__crm-contact-opportunity__review.txt:19` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R08__crm-contact-opportunity__review.txt:53` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S03__enterprise-identity-security__start.txt:39` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S04__legal-document-ledger-archive__start.txt:40` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S04__legal-document-ledger-archive__start.txt:49` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S05__productivity-search-saved-view__start.txt:39` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S05__productivity-search-saved-view__start.txt:48` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S06__bp-company-master-procurement-compliance__start.txt:40` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S06__bp-company-master-procurement-compliance__start.txt:49` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S08__crm-contact-opportunity__start.txt:16` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S08__crm-contact-opportunity__start.txt:39` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S08__crm-contact-opportunity__start.txt:48` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/dispatch-guide.md:15` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/execution-review-handbook.md:164` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/execution-review-handbook.md:203` | `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/platform-invariants.md:239` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/platform-invariants.md:298` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/shared-standards.md:25` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/task-start-conversations.md:8` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/task-start-conversations.md:2673` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/task-start-conversations.md:4005` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:1` | `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:13` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:25` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:44` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:49` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:58` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:119` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md:160` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/requirements.md:76` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/review-ledger.md:56` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/review-ledger.md:112` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/review-ledger.md:113` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/tasks.md:5` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/tasks.md:11` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/customer-success-service-desk/tasks.md:39` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/dashboard-and-contract-list/design.md:120` | — | `\u9875` | 説明文を修正 |
| `.kiro/specs/dashboard-and-contract-list/requirements.md:53` | — | `\u9875` | 説明文を修正 |
| `.kiro/specs/dashboard-improvements/design.md:737` | — | `\u4e1a` | 説明文を修正 |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/reviewer-verification-decision-delta-r23-p1-01.md:363` | `\u64a4\u9500 ×1` | `\u9500` | 説明文を修正 |
| `.kiro/specs/engineer-availability-visualization/design.md:46` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/design.md:124` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/design.md:153` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/design.md:155` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/design.md:159` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/requirements.md:54` | `\u53e3\u5f84 ×3` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/tasks.md:42` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/engineer-sales-commission/tasks.md:47` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/design.md:3` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:324` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:343` | `\u5347\u7ea7 ×1` | `\u7ea7` | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:378` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:401` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:433` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:435` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:436` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:441` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/review-ledger.md:443` | `\u95e8\u7981 ×1`, `\u786c\u95e8\u7981 ×1` | `\u95e8` | 説明文を修正 |
| `.kiro/specs/enterprise-identity-security/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/half-finished-production-readiness/README.md:55` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/half-finished-production-readiness/review-conversations.md:51` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/half-finished-production-readiness/start-conversations.md:45` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/jp-pint-digital-invoice/review-ledger.md:13` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/legal-document-ledger-archive/design.md:3` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/legal-document-ledger-archive/design.md:61` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/legal-document-ledger-archive/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/lifecycle-status-consistency/tasks.md:40` | — | `\u51cf` | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/design.md:12` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/design.md:68` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/design.md:106` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/design.md:188` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/design.md:204` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:14` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:30` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:31` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:64` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:66` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:68` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:72` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:77` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/requirements.md:94` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/tasks.md:38` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/tasks.md:43` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/money-flow-consistency/tasks.md:89` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/multi-tier-bp-management/design.md:60` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:27` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:67` | — | `\u5bf9`, `\u4efd`, `\u786e` | 既存の中国語翻訳例を原文引用として保持 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:118` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:184` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:359` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:441` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:467` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:553` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/design.md:21` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/design.md:58` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:60` | `\u63a5\u7ba1 ×1` | — | 正しい日本語の部分一致（同一期間・同一期限・直接管理） |
| `.kiro/specs/organization-management-accounting/review-ledger.md:129` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:184` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:258` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:274` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:282` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:573` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/productivity-search-saved-view/design.md:3` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/productivity-search-saved-view/design.md:56` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 説明文を修正 |
| `.kiro/specs/productivity-search-saved-view/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 説明文を修正 |
| `.kiro/specs/recruiting-pipeline/design.md:71` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/referential-integrity/requirements.md:3` | `\u5916\u952e ×1` | `\u952e` | 説明文を修正 |
| `.kiro/specs/revenue-forecast/requirements.md:8` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/revenue-forecast/requirements.md:22` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/scale-300-e2e/README.md:88` | — | `\u5220` | 過去のUI不具合で観測した文字列を証拠として保持 |
| `.kiro/specs/scale-300-e2e/defect-catalog.md:149` | — | `\u5220` | 過去のUI不具合で観測した文字列を証拠として保持 |
| `.kiro/specs/scheduled-management-reporting/design.md:70` | `\u6642\u5e8f ×1` | — | 説明文を修正 |
| `.kiro/specs/scheduled-management-reporting/inventory.md:7` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/scheduled-management-reporting/inventory.md:13` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/scheduled-management-reporting/inventory.md:39` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/skill-sheet-generation/design.md:45` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/requirements.md:12` | `\u4e00\u671f ×1` | — | 正しい日本語の部分一致（同一期間・同一期限・直接管理） |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:34` | `\u53e3\u5f84 ×2` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:56` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:167` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:190` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:251` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/staffing-capacity-planning/review-ledger.md:439` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/defect-catalog.md:23` | `\u83dc\u5355 ×1` | `\u5355` | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-conversation.md:70` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md:23` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md:91` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md:101` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md:103` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/review-ledger.md:105` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/start-conversation.md:25` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/ui-scale-regression-hardening-200/tasks.md:6` | `\u5b9a\u5411 ×1` | — | 説明文を修正 |
| `.kiro/specs/utilization-forecast/design.md:12` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/utilization-forecast/requirements.md:12` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/utilization-forecast/requirements.md:19` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/utilization-forecast/tasks.md:7` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `.kiro/specs/webhook-notifications/design.md:33` | `\u53e3\u5f84 ×1` | — | 説明文を修正 |
| `docs/i18n/plan.md:63` | — | `\u7b80` | 説明文を修正 |
| `docs/i18n/plan.md:110` | — | `\u5c97` | 既存の中国語翻訳例を原文引用として保持 |

## 5. 編集可能な文書内で保持した引用・部分一致

以下の14行以外に編集対象文書の検出残存はない。コードリテラル・既存翻訳例・不具合観測値は保持し、正しい日本語への部分一致も置換しない。

| ファイル:行 | 原語（Unicode） | 保持理由 |
|---|---|---|
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:344` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:378` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:381` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:384` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:387` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/asset-account-license-lifecycle/v182-repair-executor-boundary-a-plan.md:389` | `\u8f09\u4f53` | 例外メッセージのコードリテラルを保持 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:66` | `\u5b9a\u5411` | 過去の実行引継ぎ記録の引用を保持 |
| `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md:70` | `\u5b9a\u5411` | 過去の実行引継ぎ記録の引用を保持 |
| `.kiro/specs/order-acceptance-workflow/review-ledger.md:67` | `\u5bf9`, `\u4efd`, `\u786e` | 既存の中国語翻訳例を原文引用として保持 |
| `.kiro/specs/organization-management-accounting/review-ledger.md:60` | `\u63a5\u7ba1` | 正しい日本語の部分一致（同一期間・同一期限・直接管理） |
| `.kiro/specs/scale-300-e2e/README.md:88` | `\u5220` | 過去のUI不具合で観測した文字列を証拠として保持 |
| `.kiro/specs/scale-300-e2e/defect-catalog.md:149` | `\u5220` | 過去のUI不具合で観測した文字列を証拠として保持 |
| `.kiro/specs/staffing-capacity-planning/requirements.md:12` | `\u4e00\u671f` | 正しい日本語の部分一致（同一期間・同一期限・直接管理） |
| `docs/i18n/plan.md:110` | `\u5c97` | 既存の中国語翻訳例を原文引用として保持 |

## 6. 明示的な対象外の全出典

該当ファイルは開始時の原文と同一。検出箇所を以下にすべて記録する。

| 原文のファイル:行 | 語彙検出（Unicode・出現数） | CP932非対応漢字（Unicode） | 処理 |
|---|---|---|---|
| `.kiro/reviews/production-acceptance/54edfd2b/automated-gates.md:7` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/automated-gates.md:9` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/automated-gates.md:20` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/baseline.md:9` | — | `\u9a8c`, `\u7ebf`, `\u8bb0`, `\u5f55` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/baseline.md:10` | — | `\u9a8c`, `\u8d1f`, `\u8d23` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/baseline.md:11` | — | `\u7801`, `\u8bfb`, `\u8bc1` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:9` | `\u9580\u7981 ×1`, `\u786c\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:18` | — | `\u4e1a`, `\u52a1` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:19` | — | `\u7801`, `\u6784` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:20` | — | `\u6d4b`, `\u8bd5` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:21` | — | `\u9690` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:22` | — | `\u5e93` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/final-report.md:23` | — | `\u8fd0`, `\u7ef4` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/handoff-for-independent-review.md:18` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/handoff-for-independent-review.md:25` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/phase-1-summary.md:10` | — | `\u8bfb` | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/phase-1-summary.md:14` | `\u9580\u7981 ×1`, `\u786c\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/reviews/production-acceptance/54edfd2b/test-quality-review.md:5` | `\u9580\u7981 ×1` | — | 対象外のため変更なし |
| `.kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md:28` | `\u963b\u65ad ×1` | — | 対象外のため変更なし |
| `.kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md:30` | `\u963b\u65ad ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:18` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:55` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:76` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:144` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:150` | `\u53e3\u5f84 ×2` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/design.md:331` | `\u4e00\u671f ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/requirements.md:41` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/requirements.md:140` | `\u4e00\u671f ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/review-conversations.md:124` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/review-conversations.md:159` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/tasks.md:71` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/ai-management-copilot/tasks.md:79` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1a-schema-draft.md:275` | — | `\u6bcf` | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:3` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:44` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:51` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:135` | `\u56de\u9000 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:136` | `\u56de\u9000 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:285` | `\u56de\u9000 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:287` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:293` | `\u4e00\u671f ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:316` | `\u4e00\u671f ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:323` | `\u56de\u9000 ×3` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:344` | `\u4e00\u671f ×2` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/feature/f1b-wave3-seal-publish-plan.md:413` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/tasks.md:95` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/tasks.md:104` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/tasks.md:105` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/data-migration-import-center/tasks.md:111` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/engineer-lifecycle-workflow/design.md:246` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/engineer-lifecycle-workflow/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/integration-hub-public-api/inventory.md:221` | `\u53e3\u5f84 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/mobile-pwa-self-service/revocation-recovery-decision.md:46` | `\u56de\u9000 ×2` | — | 対象外のため変更なし |
| `.kiro/specs/mobile-pwa-self-service/revocation-recovery-decision.md:76` | `\u63a5\u7ba1 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/completion-matrix.md:36` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/completion-matrix.md:47` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/completion-matrix.md:55` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/completion-matrix.md:57` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/design.md:17` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:38` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:43` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:46` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:55` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:57` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:262` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:278` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:299` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:304` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/prod-wiring-decision-contract.md:306` | `\u9589\u74b0 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:9` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:419` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:427` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:429` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:430` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:431` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:432` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:433` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:435` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:436` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:437` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:438` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:439` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:440` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:441` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:442` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:443` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:444` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:445` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:446` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:447` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:448` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:449` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/pure-policy-verification-kernel-plan.md:467` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/sf-policy-readonly-query-plan.md:604` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/sf-policy-readonly-query-plan.md:609` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/sf-w2b-slice-d-recovery-plan.md:115` | `\u4e00\u671f ×1` | — | 対象外のため変更なし |
| `.kiro/specs/privacy-retention-dsar/tasks.md:18` | `\u8ca0\u5411 ×1` | — | 対象外のため変更なし |
| `docs/review/evidence/nf02-p1-targeted/evidence-ja.md:1` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `docs/review/evidence/nf02-p1-targeted/evidence-ja.md:15` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |
| `docs/review/evidence/nf02-p1-targeted/evidence-ja.md:17` | `\u5b9a\u5411 ×1` | — | 対象外のため変更なし |

## 7. ハッシュ管理・参照先文書の全出典

該当ファイルは開始時の原文と同一。検出箇所を以下にすべて記録する。

| 原文のファイル:行 | 語彙検出（Unicode・出現数） | CP932非対応漢字（Unicode） | 処理 |
|---|---|---|---|
| `.kiro/specs/accounting-payment-integration/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/accounting-payment-integration/tasks.md:3` | `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/accounting-payment-integration/tasks.md:4` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/ai-feedback-learning/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/ai-feedback-learning/design.md:56` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/ai-feedback-learning/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/design.md:217` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/design.md:391` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:69` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:70` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:133` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:146` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:157` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:165` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:337` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:345` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:350` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:359` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:370` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:371` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:378` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-ledger.md:391` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:368` | `\u5b9a\u5411 ×2` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:372` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:375` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:376` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:377` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:378` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:380` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:381` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:383` | `\u5b9a\u5411 ×2` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:387` | `\u5b9a\u5411 ×2` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:388` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:439` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:457` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:458` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:100` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:108` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:123` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:136` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:143` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:147` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:162` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/approval-workflow-internal-control/tasks.md:165` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/attendance-leave-overtime-compliance/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/attendance-leave-overtime-compliance/design.md:96` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/attendance-leave-overtime-compliance/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/README.md:6` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/README.md:160` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/README.md:162` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R07__approval-workflow-internal-control__review.txt:54` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R09__order-acceptance-workflow__review.txt:53` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R10__dispatch-outsourcing-compliance-ledger__review.txt:54` | `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R10__dispatch-outsourcing-compliance-ledger__review.txt:69` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R11__attendance-leave-overtime-compliance__review.txt:71` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R12__staffing-capacity-planning__review.txt:19` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R12__staffing-capacity-planning__review.txt:53` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R13__external-customer-bp-portal__review.txt:58` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R14__engineer-self-service-portal-v2__review.txt:53` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R15__accounting-payment-integration__review.txt:55` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R16__jp-pint-digital-invoice__review.txt:54` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R17__ai-feedback-learning__review.txt:54` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S07__approval-workflow-internal-control__start.txt:40` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S07__approval-workflow-internal-control__start.txt:49` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S09__order-acceptance-workflow__start.txt:39` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S09__order-acceptance-workflow__start.txt:48` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S10__dispatch-outsourcing-compliance-ledger__start.txt:48` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S10__dispatch-outsourcing-compliance-ledger__start.txt:57` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S11__attendance-leave-overtime-compliance__start.txt:44` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S11__attendance-leave-overtime-compliance__start.txt:60` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S12__staffing-capacity-planning__start.txt:17` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S12__staffing-capacity-planning__start.txt:40` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S12__staffing-capacity-planning__start.txt:49` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S13__external-customer-bp-portal__start.txt:41` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S13__external-customer-bp-portal__start.txt:55` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S14__engineer-self-service-portal-v2__start.txt:40` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S14__engineer-self-service-portal-v2__start.txt:49` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S15__accounting-payment-integration__start.txt:42` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S15__accounting-payment-integration__start.txt:51` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S16__jp-pint-digital-invoice__start.txt:41` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S16__jp-pint-digital-invoice__start.txt:50` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S17__ai-feedback-learning__start.txt:41` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S17__ai-feedback-learning__start.txt:50` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md:119` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md:43` | `\u5b9a\u5411 ×1`, `\u5f00\u5de5 ×1` | `\u5f00` | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md:44` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md:49` | `\u5b9a\u5411 ×1`, `\u95e8\u7981 ×1`, `\u786c\u95e8\u7981 ×1` | `\u95e8` | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md:177` | `\u5f00\u5de5 ×1` | `\u5f00` | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:254` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:341` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:429` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:517` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:571` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:604` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:691` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:774` | `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:788` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:895` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:949` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:982` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:1074` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:1161` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:1250` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:1338` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md:1426` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1`, `\u9010\u9805\u7167\u5408 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:6` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:239` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:308` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:378` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:448` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:494` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:517` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:586` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:662` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:738` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:791` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:814` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:884` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:958` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:1029` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:1099` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md:1169` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/customer-success-service-desk/design.md:110` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/design.md:135` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md:88` | `\u4e00\u671f ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/engineer-self-service-portal-v2/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/engineer-self-service-portal-v2/design.md:63` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/engineer-self-service-portal-v2/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/external-customer-bp-portal/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/external-customer-bp-portal/design.md:62` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/external-customer-bp-portal/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/integration-test-plan/module-test-matrix.md:144` | `\u4e00\u671f ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/jp-pint-digital-invoice/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/jp-pint-digital-invoice/design.md:70` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/jp-pint-digital-invoice/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/jp-pint-digital-invoice/tasks.md:66` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/jp-pint-digital-invoice/tasks.md:90` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/design.md:23` | `\u56de\u586b ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/design.md:94` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/evidence/round11-remediation-20260809.md:25` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/evidence/round11-remediation-20260809.md:26` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/order-acceptance-workflow/tasks.md:90` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/design.md:3` | `\u5b9a\u5411 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/design.md:26` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/design.md:50` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/design.md:64` | `\u4e00\u671f ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/design.md:81` | `\u53ef\u898b\u6bcd\u96c6\u56e3 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/tasks.md:3` | `\u5b9a\u5411 ×1`, `\u5206\u5c64 ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/tasks.md:14` | `\u4e00\u671f ×1` | — | 証跡保全のため変更なし |
| `.kiro/specs/staffing-capacity-planning/tasks.md:55` | `\u53e3\u5f84 ×1` | — | 証跡保全のため変更なし |

## 8. 歴史記録として保全した全出典

該当ファイルは開始時の原文と同一。検出箇所を以下にすべて記録する。

| 原文のファイル:行 | 語彙検出（Unicode・出現数） | CP932非対応漢字（Unicode） | 処理 |
|---|---|---|---|
| `.kiro/audits/2026-07-20-audit-recheck-remaining.md:96` | — | `\u9500`, `\u989d` | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-20-mobile-ui-bug-audit.md:78` | — | `\u8bb0`, `\u5f55` | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:5` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:25` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:45` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:49` | `\u53e3\u5f84 ×2` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:52` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:54` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:83` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-business-logic-bug-audit.md:85` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-21-optimization-and-residual-bug-audit.md:63` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-22-current-module-integration-bug-audit.md:251` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-23-full-audit-round7.md:113` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-23-full-audit-round7.md:268` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-23-round7-fix-recheck.md:154` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-07-25-roadmap-review-and-hardening.md:89` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-08-01-attendance-parallel-track-plan.md:230` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-08-01-attendance-parallel-track-plan.md:337` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/audits/2026-08-01-attendance-parallel-track-plan.md:393` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:5` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:16` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:44` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:49` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:50` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-nf01-r01a-evidence.md:60` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:134` | `\u6642\u5e8f ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:300` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:344` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:401` | `\u53e3\u5f84 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:402` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:440` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:971` | `\u7cbe\u78ba ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:1039` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:1067` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:1069` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-execution-ledger.md:1083` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-integrated-fast.md:38` | `\u53e3\u5f84 ×3` | — | 歴史記録保全のため変更なし |
| `.kiro/roadmap/2026-10-01-snf-production-integrated-fast.md:214` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/contract-document-esign/review-ledger.md:27` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/contract-document-esign/review-ledger.md:173` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/contract-document-esign/review-ledger.md:189` | `\u5b9a\u5411 ×2` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:723` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:741` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:756` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:769` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:773` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:817` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:834` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:880` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:899` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |
| `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md:938` | `\u5b9a\u5411 ×1` | — | 歴史記録保全のため変更なし |

## 9. マニフェストからの参照元と保全先

参照元にはSHA-256リストとcommit別のpath inventoryを含む。文書は所在ディレクトリが異なる場合も原文を保持した。

| 参照元:行 | 保全した文書 |
|---|---|
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:88` | `.kiro/specs/approval-workflow-internal-control/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:89` | `.kiro/specs/approval-workflow-internal-control/operation-inventory.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:90` | `.kiro/specs/approval-workflow-internal-control/review-ledger.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:91` | `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:92` | `.kiro/specs/approval-workflow-internal-control/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:98` | `.kiro/specs/customer-product-expansion-2026/README.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:98` | `README.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:99` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R07__approval-workflow-internal-control__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:100` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R09__order-acceptance-workflow__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:101` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R10__dispatch-outsourcing-compliance-ledger__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:102` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R11__attendance-leave-overtime-compliance__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:103` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R12__staffing-capacity-planning__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:104` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R13__external-customer-bp-portal__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:105` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R14__engineer-self-service-portal-v2__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:106` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R15__accounting-payment-integration__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:107` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R16__jp-pint-digital-invoice__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:108` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/R17__ai-feedback-learning__review.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:109` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S07__approval-workflow-internal-control__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:110` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S09__order-acceptance-workflow__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:111` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S10__dispatch-outsourcing-compliance-ledger__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:112` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S11__attendance-leave-overtime-compliance__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:113` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S12__staffing-capacity-planning__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:114` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S13__external-customer-bp-portal__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:115` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S14__engineer-self-service-portal-v2__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:116` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S15__accounting-payment-integration__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:117` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S16__jp-pint-digital-invoice__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:118` | `.kiro/specs/customer-product-expansion-2026/copyable-conversations/S17__ai-feedback-learning__start.txt` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:119` | `.kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:120` | `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:121` | `.kiro/specs/customer-product-expansion-2026/spec-review-conversations.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:122` | `.kiro/specs/customer-product-expansion-2026/spec-start-conversations.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:128` | `.kiro/specs/accounting-payment-integration/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:129` | `.kiro/specs/accounting-payment-integration/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:130` | `.kiro/specs/ai-feedback-learning/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:131` | `.kiro/specs/ai-feedback-learning/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:132` | `.kiro/specs/attendance-leave-overtime-compliance/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:133` | `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:134` | `.kiro/specs/dispatch-outsourcing-compliance-ledger/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:135` | `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:136` | `.kiro/specs/engineer-self-service-portal-v2/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:137` | `.kiro/specs/engineer-self-service-portal-v2/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:138` | `.kiro/specs/external-customer-bp-portal/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:139` | `.kiro/specs/external-customer-bp-portal/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:140` | `.kiro/specs/jp-pint-digital-invoice/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:141` | `.kiro/specs/jp-pint-digital-invoice/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:142` | `.kiro/specs/order-acceptance-workflow/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:143` | `.kiro/specs/order-acceptance-workflow/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:144` | `.kiro/specs/staffing-capacity-planning/design.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:145` | `.kiro/specs/staffing-capacity-planning/tasks.md` |
| `.kiro/specs/approval-workflow-internal-control/review-manifest-10dc316d.md:405` | `README.md` |
| `docs/review/evidence/nf02-p1-targeted/final-source-manifest.txt:1` | `.kiro/specs/customer-success-service-desk/design.md` |
| `docs/review/evidence/nf02-p1-targeted/final-source-manifest.txt:2` | `.kiro/specs/customer-success-service-desk/baseline-p1-remediation.md` |

## 10. 保全範囲と静的確認

明示的な除外指定（配下全体を保持）:

- `.kiro/reviews/production-acceptance/`
- `.kiro/specs/privacy-retention-dsar/`
- `.kiro/specs/ai-management-copilot/`
- `.kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md`
- `.kiro/specs/integration-hub-public-api/`
- `ops/security/runbooks/integration-hub-public-api.md`
- `.kiro/specs/data-migration-import-center/`
- `.kiro/specs/engineer-lifecycle-workflow/`
- `.kiro/roadmap/2026-11-14-snf-nf01-fine-grained-epoch-plan.md`
- `.kiro/specs/production-launch/deploy-runbook.md`
- `docs/review/evidence/`
- `.kiro/specs/mobile-pwa*` と `revocation-recovery-decision.md`

追加で保全したハッシュ関連ディレクトリ:

- `.kiro/reviews/production-acceptance/54edfd2b`
- `.kiro/reviews/production-acceptance/54edfd2b/pitr-evidence`
- `.kiro/specs/approval-workflow-internal-control`
- `docs/review/evidence/nf02-p1-targeted`
- `ops/backup/lib`
- `.kiro/specs/integration-test-plan`

- `.kiro/audits/` の日付付き監査原本、および2026-10-01のR01a証跡・本番実行台帳・統合fast実測記録は原文を保持した。
- `contract-document-esign/review-ledger.md` と `dispatch-outsourcing-compliance-ledger/review-ledger.md` には既存のNUL・FF・CR・BEL制御文字が含まれ、一部は過去のheadや検証条件に隣接する。推測で文字やSHAを復元せず、歴史記録としてファイル全体を保全した。
- 対象外・ハッシュ管理・参照先・歴史記録の文書は開始時のUTF-8バイト列と照合して一致。
- 整備した説明文のSHA文字列とPASS / FAIL / BLOCKED / PENDING / APPROVED / VERIFIED_CLOSEDの出現列が原文と一致。過去の結果を現在の合格へ読み替えていない。
- 実行可能なコード例は不変。SQL例は日本語コメント2行だけを整備。Mermaidは表示ラベルだけを整備し、ASCII識別子・制御構造は保持。表示用の計算式2箇所と作業手順1箇所は説明語だけを整備した。
- CLAUDE.mdとAGENTS.mdの権限節は一致。最長一致menuによる保護の限界、別menu配下・別名rootのlegacy規則、isAdminOnlyActionのprefix判定、V66 denyの多層防御としての役割を説明した。既存の権限欠陥を修正したとは主張しない。
- 変更文書はUTF-8（BOMなし）・LF。文書だけの変更のためMaven・JUnit・Docker・browserは実行していない。

本附表は作業記録であり、本番投入や業務Ownerの署名を代行しない。
