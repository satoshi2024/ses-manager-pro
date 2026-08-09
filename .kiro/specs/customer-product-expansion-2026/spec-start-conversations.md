# 17spec単位・主実装AI着手対話

本書を通常の派工入口とする。1specにつき1つの主対話を維持し、tasks.mdの原子taskを順番に処理する。
T001は完了済みであり、本書の実行対象から除外する。`task-start-conversations.md` は例外的な再分割時だけ使う。

S03〜S17は `test-execution-policy-s03-s17.md` を必須適用する。通常TaskはL0〜L3の定向test・直接回帰で完了でき、
各specのM taskでL4全量を実行する。通常Taskへ無条件の全量testを要求してはならない。

## 使い方

1. `spec-execution-ledger.md` で開始条件と前WaveのReview合格を確認する。
2. 対象specの節にある対話を新しい実装対話へコピーする。
3. 主AIはtaskを順番に完了し、並行レーンだけ子Agentへ限定分派する。
4. spec実装が止まったら同じ対話で無理に進めず、ledgerへblockerを記録する。
5. M完了後、新しい対話で `spec-review-conversations.md` の同じspecを実行する。
6. S03〜S17では各TaskのTEST SCOPE DECISIONをreview-ledgerへ残し、次のL4 checkpointを明記する。

## S01 — `multi-company-tenant-isolation`

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T002: F1. tenant/legal entity DDLと既存データ移行
- T003: F2. TenantContextとMyBatis強制条件
- T004: F3. カスタムSQL・非HTTP経路
- T005: F4. 認証・platform管理・停止
- T006: F5. ファイル・export・backup
- T007: M. 全回帰と容量確認
- T001は完了済み。再実行、再勾選、再Reviewしない。

【開始条件】
- Wave: Wave 0
- Migration: 未定（V59は永久欠番。共有DB再開時に当時のFlyway最新番号`latest + 1`から再計画）
- 先行条件: T001完了済み。T001を再実行・再Reviewしない。共有DB再承認がない限り本対話は延期確認だけで終了する。
- Decision gate: G0は独立DBで決定済み。共有DB SaaSの再承認、当時latest+1の再採番、Docker/MySQL smoke環境が揃うまで残taskは延期。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【spec内の実行順】
- F1→F2→F3→F4→F5→M（現在は全て延期）
- 並行ルール: production変更の並行禁止。子Agentはread-only inventory、test matrix、diff reviewだけ。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/multi-company-tenant-isolation/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S02 — `organization-management-accounting`

```text
あなたはSES Manager Proの `organization-management-accounting` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T008: F1. 組織/所属/cost center/予算DDL
- T009: F2. OrganizationScopeService
- T010: A1. 組織管理画面
- T011: B1. 月次帰属snapshotと予算
- T012: B2. 管理会計ダッシュボード
- T013: M. 回帰

【開始条件】
- Wave: Wave 0
- Migration: V60
- 先行条件: T001完了と独立DB方式のtenant Gateがcurrent-mode完了として記録済みであること。T001の再Reviewは不要。
- Decision gate: 全体Gate G1〜G6の発注者方針を記録し、V59を将来補写しないことを文書化してから開始。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【spec内の実行順】
- F1→(F2 || A1)→B1→B2→M
- 並行ルール: F1 merge後、F2とA1を2レーン可。B1/B2とMは主担当が順次統合。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/organization-management-accounting/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S03 — `enterprise-identity-security`

```text
あなたはSES Manager Proの `enterprise-identity-security` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T014: 0. G1/脅威モデル/permission inventory
- T015: F1. identity/MFA/session/permission DDL
- T016: A1. OIDC login/provision/logout
- T017: A2. MFA/session管理
- T018: B1. action permission移行
- T019: B2. file quarantine/scan/fail-closed
- T020: M. セキュリティ回帰

【開始条件】
- Wave: Wave 0
- Migration: V63（organization-management-accountingの独立ReviewでV61/V62を使用したため、以前のV61予約から2つ後ろへ繰り上げ）
- 先行条件: organization-management-accounting完了・merge済み。
- Decision gate: G1（IdP、MFA、break-glass）が正式決定済み。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【spec内の実行順】
- 0→F1→[(A1→A2) || B1 || B2]→M
- 並行ルール: SecurityConfigは主担当1人。B1 permissionとB2 file scanはinterface固定後に別レーン可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/enterprise-identity-security/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S04 — `legal-document-ledger-archive`

```text
あなたはSES Manager Proの `legal-document-ledger-archive` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T021: 0. G2法務確認と既存file inventory
- T022: F1. 文書DDLとDocumentService
- T023: F2. Storage adapterとstream download
- T024: A1. 台帳検索/詳細/version UI
- T025: B1. 既存帳票/CloudSign統合
- T026: B2. 税務export/retention/disposal
- T027: M. 移行/回帰/復元

【開始条件】
- Wave: Wave 0
- Migration: V67（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: enterprise-identity-security完了・merge済み。
- Decision gate: G2の法務監修者、保存期間、訂正削除方針が正式決定済み。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || B1 || B2)→M
- 並行ルール: F2 merge後、台帳UI、既存帳票統合、export/retentionを3レーン可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/legal-document-ledger-archive/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S05 — `productivity-search-saved-view`

```text
あなたはSES Manager Proの `productivity-search-saved-view` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T028: F1. task/saved view基盤
- T029: A1. 横断検索
- T030: A2. ToDo/通知分離
- T031: B1. 保存ビュー/表示列
- T032: B2. 安全な一括操作
- T033: M. 回帰/負荷

【開始条件】
- Wave: Wave 0
- Migration: V68, V69（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: legal-document-ledger-archive完了・merge済み。
- Decision gate: blocking decisionなし。共通検索・scope・大量処理標準を確認。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- F1→(A1 || A2 || B1 || B2)→M
- 並行ルール: F1 merge後4機能を分離可。ただし同時子Agentは最大3、4本目は順送り。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/productivity-search-saved-view/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S06 — `bp-company-master-procurement-compliance`

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T034: 0. G2法務確認/既存自由入力profiling
- T035: F1. BP master/terms/contact/bank DDL
- T036: F2. 既存在庫/要員/支払移行
- T037: A1. BP管理画面
- T038: B1. 発注コンプライアンスrule/価格協議
- T039: B2. リスクdashboard/通知
- T040: M. 回帰/旧入力廃止判定

【開始条件】
- Wave: Wave 1
- Migration: V70, V71（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: Wave 0完了（2026-08-01にS05 PASSで成就）。CRMと並行開始できるがDDLはV70/V71→V73順にmerge。
- Decision gate: G2の法務監修と対象法令・帳票項目が正式決定済み。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || B1 || B2)→M
- 並行ルール: F2 merge後、管理UI、compliance rule、risk/通知を3レーン可。CRMとspec間並行可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/bp-company-master-procurement-compliance/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S07 — `approval-workflow-internal-control`

```text
あなたはSES Manager Proの `approval-workflow-internal-control` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T041: 0. G7と対象操作inventory
- T042: F1. route/request/action/delegation DDL
- T043: F2. 5 target adapters
- T044: A1. inbox/request/diff/history UI
- T045: A2. route/代理管理
- T046: B1. 通知/SLA/escalation
- T047: M. 対象画面統合/回帰

【開始条件】
- Wave: Wave 1
- Migration: V75〜V79（S07正式migration。V75は承認DDL、V76は承認menu seed、V77は`current_step_started_at`追加、V78はround/participant/version、V79はB1 notification outbox。着手時にmerge済み`db/migration`の実ファイルを再確認し、過去migrationの編集・欠番の補填・out-of-order適用は行わない。V59とV72は永久欠番）
- 先行条件: BP master V70/V71とCRM V73/V74が完了・merge済み。
- Decision gate: G7は決定値またはdecision-log推奨既定を明記。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || A2 || B1)→M
- 並行ルール: engine/5 adapters固定後、inbox、route/代理、SLA通知を3レーン可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/approval-workflow-internal-control/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S08 — `crm-contact-opportunity`

```text
あなたはSES Manager Proの `crm-contact-opportunity` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T048: F1. contact/lead/opportunity DDLと移行
- T049: F2. opportunity状態/変換/forecast排他
- T050: A1. 顧客contacts/timeline
- T051: A2. lead/opportunity UI
- T052: B1. CRM KPI
- T053: M. 回帰

【開始条件】
- Wave: Wave 1
- Migration: V73（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: Wave 0完了（2026-08-01にS05 PASSで成就）。BP V70/V71はmerge済み（`origin/main`）のため、本specのV73をその後にmerge。
- Decision gate: blocking decisionなし。forecast口径と既存contact移行を先に固定。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- F1→F2→(A1 || A2 || B1)→M
- 並行ルール: F2 merge後、contact/timeline、lead/opportunity、KPIを3レーン可。BPとspec間並行可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/crm-contact-opportunity/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S09 — `order-acceptance-workflow`

```text
あなたはSES Manager Proの `order-acceptance-workflow` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T054: F1. 注文/明細/検収DDL
- T055: F2. 見積→注文→契約
- T056: A1. 注文画面/注文請PDF/archive
- T057: B1. 月次検収service/UI
- T058: B2. 請求/月次締め/通知統合
- T059: M. 全通し

【開始条件】
- Wave: Wave 2
- Migration: V80（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: approval-workflow-internal-control完了・merge済み。
- Decision gate: 承認状態機械と帳票archive interfaceが固定済み。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- F1→F2→(A1 || B1)→B2→M
- 並行ルール: F2 merge後、注文/PDFと月次検収を2レーン可。B2はB1後。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/order-acceptance-workflow/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S10 — `dispatch-outsourcing-compliance-ledger`

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T060: 0. G2公式様式field mapping
- T061: F1. workplace/profile/finding/delivery DDL
- T062: F2. ComplianceRule分割/拡張
- T063: A1. 契約compliance profile/UI
- T064: B1. 法定帳票/交付/archive
- T065: B2. deadline/リスク運用
- T066: M. 法務受入/回帰

【開始条件】
- Wave: Wave 2
- Migration: V82（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: order-acceptance-workflow完了・merge済み。
- Decision gate: G2-DEV-GATE確定済み。T060は公式mapping+L0+独立Reviewで`PROVISIONAL_REVIEWED`へ進める。
  特定自然人の事前指名と実actor承認eventは開始条件ではなく、`ACTIVE`化/M/本番gateとする。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || B1 || B2)→M
- 並行ルール: F2 merge後、profile UI、法定帳票、deadline/riskを3レーン可。attendanceとspec間並行可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S11 — `attendance-leave-overtime-compliance`

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T067: 0. G6/36協定/就業規則確認
- T068: F1. calendar/attendance/month/leave/agreement DDL
- T069: F2. 集計/時間外calculator
- T070: A1. 本人/管理画面と月次状態
- T071: A2. 休暇/approval統合
- T072: B1. freee/provider sync
- T073: B2. 客先工数差異/通知
- T074: M. 回帰/法務受入

【開始条件】
- Wave: Wave 2
- Migration: V83（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: order-acceptance-workflow完了・merge済み。
- Decision gate: G6（雇用勤怠の正＝本システム）が正式決定済み。
- 時間外計算の値は `overtime-rules.md` で**確定済み**である。社労士確認と法人別36協定・就業規則の突合は
  **本番releaseのgate**であって開工条件ではない。未入手を理由に着手を止めない。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→[(F2→A1→B2) || A2 || B1]→M
- 並行ルール: calculatorは主担当。休暇とprovider syncを別レーン可。dispatchとspec間並行可。
- 時間外計算の唯一の正は `overtime-rules.md`。数値・境界の向き・休日労働の算入可否・優先順位・変更手順は同書に従い、
  本specで決め直さない。実装が守る構造制約は3点:
  (1) 判定式は `OvertimeComplianceCalculator` に1メソッド1ルールで書く。
  (2) 閾値をコードへ直書きせず `m_overtime_agreement`（法人別）→ `m_system_config`（overtime.*）→ 定数の順で解決する。
      協定行が無い法人は判定不能としてfindingを出し、既定値で「適合」にしない。
  (3) 休日労働の算入可否はcalculatorへ渡す入力の選択1箇所に閉じ、ルール内部へ条件を持ち込まない。
- 月100時間だけが `>=` 判定（法条は「100時間未満」）。他の上限は「以内」なので上限ちょうどは適合。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S12 — `staffing-capacity-planning`

```text
あなたはSES Manager Proの `staffing-capacity-planning` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T075: F1. position/allocation/scenario DDL
- T076: F2. proposal/contract/availability統合
- T077: A1. position board/allocation timeline
- T078: B1. 需給heatmap/KPI
- T079: B2. scenario compare
- T080: M. 回帰/性能

【開始条件】
- Wave: Wave 2
- Migration: V84（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: dispatchとattendanceが両方完了・merge済み。
- Decision gate: 募集枠、兼務、配賦率、scenarioの業務口径を確認。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- F1→F2→(A1 || B1 || B2)→M
- 並行ルール: F2 merge後、board、heatmap、scenarioを3レーン可。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/staffing-capacity-planning/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S13 — `external-customer-bp-portal`

```text
あなたはSES Manager Proの `external-customer-bp-portal` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T081: 0. G3/G8と公開field inventory
- T082: F1. portal org/user/invite/consent DDL
- T083: F2. 専用security chain/DTO boundary
- T084: A1. 顧客portal
- T085: A2. BP portal
- T086: B1. 管理/通知/利用規約
- T087: M. penetration/回帰/運用

【開始条件】
- Wave: Wave 3
- Migration: V85（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: Wave 2、identity、archive完了。engineer portalより先にsecurity chainをmerge。
- Decision gate: G3（domain/本人確認/利用規約）確定、G8は決定または推奨既定を記録。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || A2 || B1)→M
- 並行ルール: SecurityConfigと公開DTOは主担当。F2後に顧客、BP、管理/通知を3レーン可。
- 本specは `platform-invariants.md` §2（認可母集団）の既定解が適用できない**唯一のspec**である。portal userは
  `sys_user` ではなく、DataScope・組織scope・menu権限のいずれも持たない。母集団は
  `portal_org` → `customer_id`/`bp_company_id` から独立に導出し、既存scope serviceを流用しない。詳細はdesign.mdの決定表。
- `PortalLoginUser` を内部 `LoginUser` へ変換する経路を作らない。招待tokenの一回性はDB CAS
  （`UPDATE ... WHERE used_at IS NULL`）で保証し、アプリ側の「存在チェック→更新」にしない。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/external-customer-bp-portal/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S14 — `engineer-self-service-portal-v2`

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T088: F1. change/expense/1on1/survey DDL
- T089: A1. my dashboard/profile/skill申請
- T090: A2. 本人給与/勤怠導線
- T091: B1. 経費申請/承認/archive
- T092: B2. 1on1/survey/privacy
- T093: M. 回帰

【開始条件】
- Wave: Wave 3
- Migration: V86（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: external portalのsecurity chainが先にmerge済み。attendance/staffingの公開interface固定済み。
- Decision gate: G9は決定または推奨既定を記録。給与・勤怠・privacyの本人scopeを固定。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- F1→(A1 || A2 || B1 || B2)→M
- 並行ルール: F1後4機能を分離可。最大3子Agent、4本目は順送り。SecurityConfigは変更せずportal側ownerへ依頼。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/engineer-self-service-portal-v2/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S15 — `accounting-payment-integration`

```text
あなたはSES Manager Proの `accounting-payment-integration` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T094: 0. G4/API spike/canonical mapping
- T095: F1. connection/mapping/job DDLと既存connection移行
- T096: F2. AccountingProvider/freee/CSV
- T097: A1. mapping/preview/job管理UI
- T098: B1. 売上/取消連携
- T099: B2. BP/経費/支払連携
- T100: B3. 月次照合/closing
- T101: M. 回帰/障害訓練

【開始条件】
- Wave: Wave 3
- Migration: V87（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: portal系、order、BP、archive完了・merge済み。
- Decision gate: G4（freee plan/API/仕訳の正）確定、G9の経費方針を記録。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(A1 || B1 || B2)→B3→M
- 並行ルール: provider/job coreは主担当。F2後、UI、売上、BP/経費を3レーン可。B3はB1+B2後。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/accounting-payment-integration/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S16 — `jp-pint-digital-invoice`

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T102: 0. G5/provider/spec version spike
- T103: F1. participant/digital invoice/event DDL
- T104: F2. CanonicalInvoice/renderer/validator
- T105: B1. provider送信/status/webhook
- T106: A1. 設定/送信/状態UI
- T107: B2. 受信review
- T108: M. provider受入/回帰

【開始条件】
- Wave: Wave 3
- Migration: V88（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: accounting-payment-integration完了・merge済み。
- Decision gate: G5（Certified Service Provider、sandbox、認証、文書種別）が正式決定済み。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(B1 || A1)→B2→M
- 並行ルール: canonical model/rendererは主担当。F2後、provider送信とUIを2レーン可。受信はprovider契約固定後。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/jp-pint-digital-invoice/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

## S17 — `ai-feedback-learning`

```text
あなたはSES Manager Proの `ai-feedback-learning` を最後まで管理する主実装AIです。
このspec専用の長期対話として、担当原子taskをtasks.mdの順序と本指示の依存に従って処理してください。

【担当原子task】
- T109: 0. G10/use case/PII/metric確定
- T110: F1. version/run/item/feedback/outcome/evaluation DDL
- T111: F2. AiExecutionGateway/PII mask
- T112: B1. feedback/outcome連携
- T113: B2. offline evaluation/version promotion
- T114: A1. evaluation dashboard
- T115: M. 回帰/安全性

【開始条件】
- Wave: Wave 4
- Migration: V89（着手時にmerge済み`db/migration`の最新を再確認する。衝突していれば後発である本specを上へ繰り上げ、前の欠番は埋めない。V59は永久欠番）
- 先行条件: CRM、proposal、staffing、outcome sourceが完了・merge済み。
- Decision gate: G10はmock/rule継続または実provider/DPA/PII許可を記録。
未達ならproduction変更をせず、blocker、影響task、必要な発注者回答、再開条件を報告して停止してください。

【最初に完全に読むもの】
- AGENTS.md
- .kiro/specs/README.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/execution-review-handbook.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/customer-product-expansion-2026/subagent-delegation-summary.md
- .kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【既定解と決定表】
- `platform-invariants.md` はcheck listではなく**既定解の表**である。時間/履歴/明示NULL、認可母集団の結合規則、
  transaction/cache、期間代数、Migration 5形状、金額/CSVの答えがそこにある。既定解を各specで再発明しない。
- 本specの `design.md` の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）は**確定済み**である。
  実装中に読み替えたり決め直したりしない。
- 既定解から外れる場合だけ「逸脱と根拠」を書く。書いていなければ既定解をそのまま実装する。
- 決定表にもplatform-invariantsにも無い論点が出たら、推測実装せずspecを具体化する提案を出して停止する。

【spec内の実行順】
- 0→F1→F2→(B1 || B2)→A1→M
- 並行ルール: gateway/PII/versionは主担当。F2後、feedback/outcomeとevaluationを2レーン可。dashboardはevaluation API後。
- task開始前にObjective、requirements ID、実装ガイダンス、テスト要件、Demoを短く提示する。
- 1回に完了扱いにするのは1taskだけ。成功条件を満たしたtaskだけtasks.mdを - [x] にする。
- 子Agentを使う場合はtask、許可file、禁止共有file、入力commit、完了条件を先に宣言する。
- migration、SecurityConfig、共通entity/service、message bundle、sidebar、tasks.md、M taskは主担当が所有する。
- Mは全featureレーンmerge後に主担当が単独実行する。

【実装共通ルール】
- 担当spec外のrefactor、別specの先取り、予約外migration、既存変更の上書きを禁止する。
- DDLはV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assert、entityを同じtaskで同期する。
- CSRF、監査、状態機械、楽観ロック、4言語i18n、tenant/data/organization/file scopeを同一境界で確認する。
- list/detail/count/export/download/notification/schedulerの認可母集団を一致させる。
- 外部APIはtransaction外で呼び、idempotency、correlation ID、timeout、retry/backoff、障害復旧を実装する。
- 内部entityをportal/AIへ直接公開しない。未知file/scan障害はfail-closed。

【記録と完了報告】
- `.kiro/specs/ai-feedback-learning/review-ledger.md` を作成または更新し、task、requirements、変更file、test、Demo、commit、riskを1行ずつ記録する。
- 完了時はtask別対応表、実行test、Demo、未検証事項、rollback、base/head commit、Review開始条件を報告する。
- Review合格前に次specを開始済みと記録しない。
```

