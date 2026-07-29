# 17spec単位・独立Review対話

各Reviewは実装対話と分離した新規対話で実行する。過去の会話を貼らず、spec、review ledger、commit/diffをrepositoryから読む。
T001は完了済みのため本書のReview対象から除外する。

R03〜R17は `test-execution-policy-s03-s17.md` を必須適用する。同一Headの有効なL4全量証拠を理由なく再実行せず、
再ReviewはOPEN issue、修正diff、direct regressionのL1〜L3を既定とする。昇格条件時だけL4を要求する。

## 使い方

1. `<BASE_COMMIT>`と`<HEAD_COMMIT>`を分かる場合だけ置換する。不明ならreview ledgerとgit statusから範囲を特定させる。
2. Review AIは変更しない。指摘は元の実装対話へ返す。
3. 修正後は同じReview対話へcommit/diffだけ伝えて再Reviewする。
4. 合格後だけ中央ledgerのReviewをPASSにし、次spec/Waveを開始する。
5. L4再実行の要否はM証拠commitとReview Headの一致、merge差分、共有境界変更から判定し、理由をledgerへ記録する。

## R01 — `multi-company-tenant-isolation` Review

```text
これは `multi-company-tenant-isolation` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T002: F1. tenant/legal entity DDLと既存データ移行
- T003: F2. TenantContextとMyBatis強制条件
- T004: F3. カスタムSQL・非HTTP経路
- T005: F4. 認証・platform管理・停止
- T006: F5. ファイル・export・backup
- T007: M. 全回帰と容量確認
- T001は完了済みで本Review対象外。差分に混在していても再判定しない。
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 0
- Migration: 未定（V59は永久欠番。共有DB再開時に当時のFlyway最新番号`latest + 1`から再計画）
- 先行条件: T001完了済み。T001を再実行・再Reviewしない。共有DB再承認がない限り本対話は延期確認だけで終了する。
- Decision gate: G0は独立DBで決定済み。共有DB SaaSの再承認、当時latest+1の再採番、Docker/MySQL smoke環境が揃うまで残taskは延期。
- 期待するtask順: F1→F2→F3→F4→F5→M（現在は全て延期）

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md
- .kiro/specs/multi-company-tenant-isolation/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R02 — `organization-management-accounting` Review

```text
これは `organization-management-accounting` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T008: F1. 組織/所属/cost center/予算DDL
- T009: F2. OrganizationScopeService
- T010: A1. 組織管理画面
- T011: B1. 月次帰属snapshotと予算
- T012: B2. 管理会計ダッシュボード
- T013: M. 回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 0
- Migration: V60
- 先行条件: 独立DB方式のtenant Gateがcurrent-mode完了としてReview済みであること。
- Decision gate: 全体Gate G1〜G6の発注者方針を記録し、V59を将来補写しないことを文書化してから開始。
- 期待するtask順: F1→(F2 || A1)→B1→B2→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md
- .kiro/specs/organization-management-accounting/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R03 — `enterprise-identity-security` Review

```text
これは `enterprise-identity-security` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T014: 0. G1/脅威モデル/permission inventory
- T015: F1. identity/MFA/session/permission DDL
- T016: A1. OIDC login/provision/logout
- T017: A2. MFA/session管理
- T018: B1. action permission移行
- T019: B2. file quarantine/scan/fail-closed
- T020: M. セキュリティ回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 0
- Migration: V63（organization-management-accountingの独立ReviewでV61/V62を使用したため、以前のV61予約から2つ後ろへ繰り上げ）
- 先行条件: organization-management-accounting完了・merge済み。
- Decision gate: G1（IdP、MFA、break-glass）が正式決定済み。
- 期待するtask順: 0→F1→[(A1→A2) || B1 || B2]→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md
- .kiro/specs/enterprise-identity-security/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R04 — `legal-document-ledger-archive` Review

```text
これは `legal-document-ledger-archive` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T021: 0. G2法務確認と既存file inventory
- T022: F1. 文書DDLとDocumentService
- T023: F2. Storage adapterとstream download
- T024: A1. 台帳検索/詳細/version UI
- T025: B1. 既存帳票/CloudSign統合
- T026: B2. 税務export/retention/disposal
- T027: M. 移行/回帰/復元
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 0
- Migration: V64
- 先行条件: enterprise-identity-security完了・merge済み。
- Decision gate: G2の法務監修者、保存期間、訂正削除方針が正式決定済み。
- 期待するtask順: 0→F1→F2→(A1 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md
- .kiro/specs/legal-document-ledger-archive/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R05 — `productivity-search-saved-view` Review

```text
これは `productivity-search-saved-view` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T028: F1. task/saved view基盤
- T029: A1. 横断検索
- T030: A2. ToDo/通知分離
- T031: B1. 保存ビュー/表示列
- T032: B2. 安全な一括操作
- T033: M. 回帰/負荷
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 0
- Migration: V65
- 先行条件: legal-document-ledger-archive完了・merge済み。
- Decision gate: blocking decisionなし。共通検索・scope・大量処理標準を確認。
- 期待するtask順: F1→(A1 || A2 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md
- .kiro/specs/productivity-search-saved-view/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R06 — `bp-company-master-procurement-compliance` Review

```text
これは `bp-company-master-procurement-compliance` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T034: 0. G2法務確認/既存自由入力profiling
- T035: F1. BP master/terms/contact/bank DDL
- T036: F2. 既存在庫/要員/支払移行
- T037: A1. BP管理画面
- T038: B1. 発注コンプライアンスrule/価格協議
- T039: B2. リスクdashboard/通知
- T040: M. 回帰/旧入力廃止判定
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 1
- Migration: V66
- 先行条件: Wave 0完了。CRMと並行開始できるがDDLはV66→V67順にmerge。
- Decision gate: G2の法務監修と対象法令・帳票項目が正式決定済み。
- 期待するtask順: 0→F1→F2→(A1 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md
- .kiro/specs/bp-company-master-procurement-compliance/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R07 — `approval-workflow-internal-control` Review

```text
これは `approval-workflow-internal-control` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T041: 0. G7と対象操作inventory
- T042: F1. route/request/action/delegation DDL
- T043: F2. 5 target adapters
- T044: A1. inbox/request/diff/history UI
- T045: A2. route/代理管理
- T046: B1. 通知/SLA/escalation
- T047: M. 対象画面統合/回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 1
- Migration: V68
- 先行条件: BP master V66とCRM V67が完了・merge済み。
- Decision gate: G7は決定値またはdecision-log推奨既定を明記。
- 期待するtask順: 0→F1→F2→(A1 || A2 || B1)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md
- .kiro/specs/approval-workflow-internal-control/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R08 — `crm-contact-opportunity` Review

```text
これは `crm-contact-opportunity` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T048: F1. contact/lead/opportunity DDLと移行
- T049: F2. opportunity状態/変換/forecast排他
- T050: A1. 顧客contacts/timeline
- T051: A2. lead/opportunity UI
- T052: B1. CRM KPI
- T053: M. 回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 1
- Migration: V67
- 先行条件: Wave 0完了。BPと並行開始できるがV66 merge後にV67をmerge。
- Decision gate: blocking decisionなし。forecast口径と既存contact移行を先に固定。
- 期待するtask順: F1→F2→(A1 || A2 || B1)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md
- .kiro/specs/crm-contact-opportunity/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R09 — `order-acceptance-workflow` Review

```text
これは `order-acceptance-workflow` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T054: F1. 注文/明細/検収DDL
- T055: F2. 見積→注文→契約
- T056: A1. 注文画面/注文請PDF/archive
- T057: B1. 月次検収service/UI
- T058: B2. 請求/月次締め/通知統合
- T059: M. 全通し
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 2
- Migration: V69
- 先行条件: approval-workflow-internal-control完了・merge済み。
- Decision gate: 承認状態機械と帳票archive interfaceが固定済み。
- 期待するtask順: F1→F2→(A1 || B1)→B2→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md
- .kiro/specs/order-acceptance-workflow/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R10 — `dispatch-outsourcing-compliance-ledger` Review

```text
これは `dispatch-outsourcing-compliance-ledger` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T060: 0. G2公式様式field mapping
- T061: F1. workplace/profile/finding/delivery DDL
- T062: F2. ComplianceRule分割/拡張
- T063: A1. 契約compliance profile/UI
- T064: B1. 法定帳票/交付/archive
- T065: B2. deadline/リスク運用
- T066: M. 法務受入/回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 2
- Migration: V70
- 先行条件: order-acceptance-workflow完了・merge済み。
- Decision gate: G2の公式様式field mappingと法務受入責任者が確定済み。
- 期待するtask順: 0→F1→F2→(A1 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R11 — `attendance-leave-overtime-compliance` Review

```text
これは `attendance-leave-overtime-compliance` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T067: 0. G6/36協定/就業規則確認
- T068: F1. calendar/attendance/month/leave/agreement DDL
- T069: F2. 集計/時間外calculator
- T070: A1. 本人/管理画面と月次状態
- T071: A2. 休暇/approval統合
- T072: B1. freee/provider sync
- T073: B2. 客先工数差異/通知
- T074: M. 回帰/法務受入
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 2
- Migration: V71
- 先行条件: order-acceptance-workflow完了・merge済み。
- Decision gate: G6（雇用勤怠の正）、36協定、就業規則が正式決定済み。
- 期待するtask順: 0→F1→[(F2→A1→B2) || A2 || B1]→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md
- .kiro/specs/attendance-leave-overtime-compliance/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R12 — `staffing-capacity-planning` Review

```text
これは `staffing-capacity-planning` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T075: F1. position/allocation/scenario DDL
- T076: F2. proposal/contract/availability統合
- T077: A1. position board/allocation timeline
- T078: B1. 需給heatmap/KPI
- T079: B2. scenario compare
- T080: M. 回帰/性能
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 2
- Migration: V72
- 先行条件: dispatchとattendanceが両方完了・merge済み。
- Decision gate: 募集枠、兼務、配賦率、scenarioの業務口径を確認。
- 期待するtask順: F1→F2→(A1 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md
- .kiro/specs/staffing-capacity-planning/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R13 — `external-customer-bp-portal` Review

```text
これは `external-customer-bp-portal` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T081: 0. G3/G8と公開field inventory
- T082: F1. portal org/user/invite/consent DDL
- T083: F2. 専用security chain/DTO boundary
- T084: A1. 顧客portal
- T085: A2. BP portal
- T086: B1. 管理/通知/利用規約
- T087: M. penetration/回帰/運用
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 3
- Migration: V73
- 先行条件: Wave 2、identity、archive完了。engineer portalより先にsecurity chainをmerge。
- Decision gate: G3（domain/本人確認/利用規約）確定、G8は決定または推奨既定を記録。
- 期待するtask順: 0→F1→F2→(A1 || A2 || B1)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md
- .kiro/specs/external-customer-bp-portal/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R14 — `engineer-self-service-portal-v2` Review

```text
これは `engineer-self-service-portal-v2` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T088: F1. change/expense/1on1/survey DDL
- T089: A1. my dashboard/profile/skill申請
- T090: A2. 本人給与/勤怠導線
- T091: B1. 経費申請/承認/archive
- T092: B2. 1on1/survey/privacy
- T093: M. 回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 3
- Migration: V74
- 先行条件: external portalのsecurity chainが先にmerge済み。attendance/staffingの公開interface固定済み。
- Decision gate: G9は決定または推奨既定を記録。給与・勤怠・privacyの本人scopeを固定。
- 期待するtask順: F1→(A1 || A2 || B1 || B2)→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md
- .kiro/specs/engineer-self-service-portal-v2/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R15 — `accounting-payment-integration` Review

```text
これは `accounting-payment-integration` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T094: 0. G4/API spike/canonical mapping
- T095: F1. connection/mapping/job DDLと既存connection移行
- T096: F2. AccountingProvider/freee/CSV
- T097: A1. mapping/preview/job管理UI
- T098: B1. 売上/取消連携
- T099: B2. BP/経費/支払連携
- T100: B3. 月次照合/closing
- T101: M. 回帰/障害訓練
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 3
- Migration: V75
- 先行条件: portal系、order、BP、archive完了・merge済み。
- Decision gate: G4（freee plan/API/仕訳の正）確定、G9の経費方針を記録。
- 期待するtask順: 0→F1→F2→(A1 || B1 || B2)→B3→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md
- .kiro/specs/accounting-payment-integration/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R16 — `jp-pint-digital-invoice` Review

```text
これは `jp-pint-digital-invoice` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T102: 0. G5/provider/spec version spike
- T103: F1. participant/digital invoice/event DDL
- T104: F2. CanonicalInvoice/renderer/validator
- T105: B1. provider送信/status/webhook
- T106: A1. 設定/送信/状態UI
- T107: B2. 受信review
- T108: M. provider受入/回帰
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 3
- Migration: V76
- 先行条件: accounting-payment-integration完了・merge済み。
- Decision gate: G5（Certified Service Provider、sandbox、認証、文書種別）が正式決定済み。
- 期待するtask順: 0→F1→F2→(B1 || A1)→B2→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md
- .kiro/specs/jp-pint-digital-invoice/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

## R17 — `ai-feedback-learning` Review

```text
これは `ai-feedback-learning` 専用の独立Review対話です。実装対話の説明を信用せず、spec、review ledger、実diffから検証してください。
Review中はfileを変更しません。問題の修正は元の実装対話へ返してください。

【Review対象】
- T109: 0. G10/use case/PII/metric確定
- T110: F1. version/run/item/feedback/outcome/evaluation DDL
- T111: F2. AiExecutionGateway/PII mask
- T112: B1. feedback/outcome連携
- T113: B2. offline evaluation/version promotion
- T114: A1. evaluation dashboard
- T115: M. 回帰/安全性
- Base commit: <BASE_COMMIT>
- Head commit: <HEAD_COMMIT>
commitが未指定ならreview-ledger.md、git status、git logから対象範囲を特定し、分離不能ならReviewを止めて必要情報を報告してください。

【前提】
- Wave: Wave 4
- Migration: V77
- 先行条件: CRM、proposal、staffing、outcome sourceが完了・merge済み。
- Decision gate: G10はmock/rule継続または実provider/DPA/PII許可を記録。
- 期待するtask順: 0→F1→F2→(B1 || B2)→A1→M

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md
- .kiro/specs/ai-feedback-learning/review-ledger.md

【Review観点】
- 各requirements IDについて実装箇所、自動test、Demo、未検証を対応付ける。
- 未実装、過剰実装、範囲外変更、認可漏れ、状態競合、二重登録、rollback不備を確認する。
- CSRF、監査、楽観ロック、4言語i18n、tenant/data/organization/file scopeを確認する。
- list/detail/count/export/download/notification/schedulerの母集団が同じか検証する。
- DDL taskはV1/Flyway/H2/entity/MySQL smokeが同期し、既存データreconciliationとrollbackがあるか確認する。
- 外部APIはidempotency、timeout、retry/backoff、rate limit、相関ID、重複webhook、障害復旧を確認する。
- 金額単位、期間境界、timezone、法定項目、PII、file fail-closed、外部DTO allow-listを確認する。
- 自動test名だけで合格にせず、失敗条件と受入条件を実際にassertしているか読む。
- MのDemoが顧客効果と既存回帰を証明し、未実行環境が明記されているか確認する。

【出力】
1. P0/P1/P2問題。各問題にfile:line、再現条件、影響、最小修正範囲を記載。
2. task別 Requirements→実装→test→Demo→判定表。
3. migration/scope/security/外部障害/性能の横断判定。
4. 未検証環境と本番前条件。
5. spec総合判定: PASS / CONDITIONAL PASS / FAIL。
6. 次specまたは次Waveを開始してよいか。
7. review-ledgerと中央ledgerへ転記する短い結論。
問題がない場合は根拠を示してPASSとし、不要なrefactorを提案しないでください。
```

